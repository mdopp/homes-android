package cloud.dopp.homes

import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.Napi.Quelle
import cloud.dopp.homes.data.SicherungClient
import cloud.dopp.homes.data.SicherungClient.LetzteDatei
import cloud.dopp.homes.sicherung.Anzeige
import cloud.dopp.homes.sicherung.Fortschritt
import cloud.dopp.homes.sicherung.Rueckgedaechtnis
import cloud.dopp.homes.sicherung.Rueckholung
import cloud.dopp.homes.sicherung.Ziel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** A [Ziel] on disk: a folder as SafZiel writes it on the phone. */
class DateiZiel(val wurzel: File) : Ziel {
    fun datei(pfad: String) = File(wurzel, pfad)
    override fun groesse(pfad: String): Long? = datei(pfad).takeIf { it.isFile }?.length()
    override fun schreiben(pfad: String, ab: Long): OutputStream {
        val f = datei(pfad)
        f.parentFile!!.mkdirs()
        RandomAccessFile(f, "rw").use { it.setLength(ab) }
        return FileOutputStream(f, true)
    }
}

class MapRueckgedaechtnis : Rueckgedaechtnis {
    val m = HashMap<String, Pair<Rueckgedaechtnis.Stand, String>>()
    override fun unser(quelle: Quelle, pfad: String) = m["${quelle.wire}|$pfad"]
    override fun merke(quelle: Quelle, pfad: String, stand: Rueckgedaechtnis.Stand, etag: String) {
        m["${quelle.wire}|$pfad"] = stand to etag
    }
}

/**
 * Homes' `/napi/sicherung/letzte/<quelle>/<pfad>` as homes#209 builds it
 * (mdopp/homes#219): the bytes from `Range` on with `Content-Range` and an
 * ETag; a WhatsApp medium 423 while the archive is closed. Knobs: cut the
 * next answer halfway, a closed archive.
 */
class FakeLetzte : Dispatcher() {
    val server = MockWebServer().also { it.dispatcher = this }
    val base: String get() = server.url("/").toString().trimEnd('/')
    /** As the phone's client: a connection the box dropped is not used again. */
    val http: OkHttpClient = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    /** `quelle|pfad` → (bytes, ETag) */
    val dateien = LinkedHashMap<String, Pair<ByteArray, String>>()
    val anfragen = ArrayList<Pair<String, String?>>() // path, Range

    @Volatile var archivZu = false

    /** archiv#64: the box keeps every file encrypted for the archive, so a closed one gives nothing. */
    @Volatile var allesImArchiv = false
    @Volatile var schneide = false

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        synchronized(this) { anfragen += path to request.getHeader("Range") }
        if (request.getHeader("Authorization") != "Bearer homes_device_TEST") return MockResponse().setResponseCode(401)
        val rest = path.removePrefix("/napi/sicherung/letzte/")
        val quelle = rest.substringBefore('/')
        val pfad = rest.substringAfter('/').split('/').joinToString("/") { URLDecoder.decode(it, "UTF-8") }
        if (archivZu && (allesImArchiv || quelle == "whatsapp" && pfad.startsWith("Media/"))) {
            return fehler(423, Rueckholung.MEDIEN_ZU)
        }
        val (b, etag) = dateien["$quelle|$pfad"] ?: return fehler(404, "Diese Sicherung gibt es auf der Box nicht (mehr).")
        val ab = Regex("""bytes=(\d+)-""").matchEntire(request.getHeader("Range").orEmpty())?.groupValues?.get(1)?.toLong() ?: 0
        val teil = b.copyOfRange(ab.toInt().coerceAtMost(b.size), b.size)
        val r = MockResponse().setResponseCode(if (request.getHeader("Range") != null) 206 else 200)
            .setHeader("ETag", etag).setBody(Buffer().write(teil))
        if (request.getHeader("Range") != null) r.setHeader("Content-Range", "bytes $ab-${b.size - 1}/${b.size}")
        if (schneide) {
            // half the bytes, then the line drops: the phone reads fewer than Content-Length says
            schneide = false
            r.setBody(Buffer().write(teil.copyOfRange(0, teil.size / 2))).setHeader("Content-Length", teil.size)
            r.socketPolicy = SocketPolicy.DISCONNECT_AT_END
        }
        return r
    }

    private fun fehler(code: Int, satz: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(JSONObject().put("fehler", satz).toString())

    fun lege(quelle: Quelle, pfad: String, b: ByteArray, etag: String = "\"e1\""): LetzteDatei {
        dateien["${quelle.wire}|$pfad"] = b to etag
        return LetzteDatei(pfad, b.size.toLong(), 1_762_142_400_000)
    }
}

/**
 * Sicherung zurückholen (homes-android#197): what is fetched and in which
 * order, that a cut or a stop goes on where it stopped, that nothing of the
 * person's is written over without asking, and the totals the screen shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RueckholungTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var homes: FakeLetzte
    private lateinit var ziel: DateiZiel
    private val g = MapRueckgedaechtnis()
    private val gemeldet = ArrayList<Fortschritt>()

    @Before
    fun auf() {
        homes = FakeLetzte()
        ziel = DateiZiel(tmp.newFolder("WhatsApp"))
    }

    @After
    fun zu() = homes.server.shutdown()

    private fun r(halt: () -> Boolean = { false }) =
        Rueckholung(SicherungClient(homes.base, FakeHomes.TOKEN, homes.http), g, halt, { gemeldet += it }, puffer = 1000)

    private fun whatsapp(): List<Rueckholung.Eintrag> {
        val db = homes.lege(Quelle.WHATSAPP, "Databases/msgstore.db.crypt15", FakeHomes.bytes(5000, 1))
        val bild = homes.lege(Quelle.WHATSAPP, "Media/WhatsApp Images/IMG 1.jpg", FakeHomes.bytes(3000, 2))
        val ton = homes.lege(Quelle.WHATSAPP, "Media/WhatsApp Voice Notes/PTT-1.opus", FakeHomes.bytes(2000, 3))
        return Rueckholung.eintraege(Quelle.WHATSAPP, listOf(db, bild, ton))
    }

    @Test
    fun aNewPhoneGetsEverythingTheDatabaseLast() {
        val e = whatsapp()
        val plan = r().planen(e, ziel)
        assertEquals(3, plan.holen.size)
        val a = r().laufen(plan, ziel)
        assertEquals(Rueckholung.Art.FERTIG, a.art)
        assertEquals(3, a.geholt)
        homes.dateien.forEach { (k, v) -> assertArrayEquals(k, v.first, ziel.datei(k.substringAfter('|')).readBytes()) }
        assertTrue("the database last", homes.anfragen.last().first.endsWith("/Databases/msgstore.db.crypt15"))
        assertTrue("a path keeps its slashes, a space is %20", homes.anfragen.any { it.first == "/napi/sicherung/letzte/whatsapp/Media/WhatsApp%20Images/IMG%201.jpg" })
        // the totals, not one file's bytes: the last word is all of it
        val last = gemeldet.last()
        assertEquals(Fortschritt(Quelle.WHATSAPP, "msgstore.db.crypt15", 3, 3, 10_000, 10_000), last)
        assertTrue("bytes only grow", gemeldet.zipWithNext().all { (x, y) -> y.bytes >= x.bytes })
        // again: everything there, nothing asked
        val vorher = homes.anfragen.size
        val wieder = r().planen(e, ziel)
        assertEquals(3, wieder.schon.size)
        assertEquals(Rueckholung.Art.FERTIG, r().laufen(wieder, ziel).art)
        assertEquals(vorher, homes.anfragen.size)
    }

    @Test
    fun whatIsThereIsNotOverwrittenWithoutAsking() {
        val e = whatsapp()
        // the person's own database (another size) and a photo the same as the box's
        ziel.schreiben("Databases/msgstore.db.crypt15", 0).use { it.write("MEINE".toByteArray()) }
        ziel.schreiben("Media/WhatsApp Images/IMG 1.jpg", 0).use { it.write(homes.dateien.getValue("whatsapp|Media/WhatsApp Images/IMG 1.jpg").first) }
        val plan = r().planen(e, ziel)
        assertEquals(listOf("Databases/msgstore.db.crypt15"), plan.konflikte.map { it.ziel })
        assertEquals(listOf("Media/WhatsApp Images/IMG 1.jpg"), plan.schon.map { it.ziel })
        assertEquals(listOf("Media/WhatsApp Voice Notes/PTT-1.opus"), plan.holen.map { it.ziel })

        // „Behalten“: the rest comes, the person's file stays as it was
        assertEquals(Rueckholung.Art.FERTIG, r().laufen(plan, ziel).art)
        assertEquals("MEINE", ziel.datei("Databases/msgstore.db.crypt15").readText())
        assertTrue(homes.anfragen.none { it.first.endsWith("msgstore.db.crypt15") || it.first.endsWith("IMG%201.jpg") })

        // „Ersetzen“: now it is the box's, from its first byte
        val noch = r().planen(e, ziel)
        assertEquals(1, noch.konflikte.size)
        assertEquals(Rueckholung.Art.FERTIG, r().laufen(noch, ziel, ersetzen = setOf("Databases/msgstore.db.crypt15")).art)
        assertArrayEquals(homes.dateien.getValue("whatsapp|Databases/msgstore.db.crypt15").first, ziel.datei("Databases/msgstore.db.crypt15").readBytes())
        assertNull(homes.anfragen.last().second?.takeIf { it != "bytes=0-" })
    }

    @Test
    fun aCutGoesOnWhereItStopped() {
        val gross = FakeHomes.bytes(300_000, 9)
        val d = homes.lege(Quelle.SIGNAL, "signal-2026-11-02-03-00-00.backup", gross)
        val e = Rueckholung.eintraege(Quelle.SIGNAL, listOf(d))
        homes.schneide = true
        val erst = r().laufen(r().planen(e, ziel), ziel)
        assertEquals(Rueckholung.Art.UNERREICHBAR, erst.art)
        val halb = ziel.groesse("signal-2026-11-02-03-00-00.backup")!!
        assertTrue("some arrived: $halb", halb in 1 until gross.size)
        assertEquals(Rueckgedaechtnis.Stand.LAEUFT, g.unser(Quelle.SIGNAL, "signal-2026-11-02-03-00-00.backup")?.first)

        // ours and not whole: fetched, not a conflict, from where it stopped
        val plan = r().planen(e, ziel)
        assertEquals(1, plan.holen.size)
        val zweit = r().laufen(plan, ziel)
        assertEquals(Rueckholung.Art.FERTIG, zweit.art)
        assertEquals("bytes=$halb-", homes.anfragen.last().second)
        assertArrayEquals(gross, ziel.datei("signal-2026-11-02-03-00-00.backup").readBytes())
        assertEquals(Rueckgedaechtnis.Stand.FERTIG, g.unser(Quelle.SIGNAL, "signal-2026-11-02-03-00-00.backup")?.first)
    }

    @Test
    fun aStopGoesOnAndAReplacedBackupStartsOver() {
        val alt = FakeHomes.bytes(50_000, 4)
        val d = homes.lege(Quelle.WHATSAPP, "Databases/msgstore.db.crypt15", alt, "\"alt\"")
        val e = Rueckholung.eintraege(Quelle.WHATSAPP, listOf(d))
        var n = 0
        val angehalten = r(halt = { ++n > 5 }).laufen(r().planen(e, ziel), ziel)
        assertEquals(Rueckholung.Art.ANGEHALTEN, angehalten.art)
        assertTrue(ziel.groesse("Databases/msgstore.db.crypt15")!! < alt.size)

        // the next night's backup, same name and size, another ETag: not glued onto the old piece
        val neu = FakeHomes.bytes(50_000, 5)
        homes.lege(Quelle.WHATSAPP, "Databases/msgstore.db.crypt15", neu, "\"neu\"")
        assertEquals(Rueckholung.Art.FERTIG, r().laufen(r().planen(e, ziel), ziel).art)
        assertArrayEquals(neu, ziel.datei("Databases/msgstore.db.crypt15").readBytes())
    }

    @Test
    fun aClosedArchiveKeepsTheMediaButNotTheChats() {
        val e = whatsapp()
        homes.archivZu = true
        val a = r().laufen(r().planen(e, ziel), ziel)
        assertEquals(Rueckholung.Art.FERTIG, a.art)
        assertEquals(Rueckholung.MEDIEN_ZU, a.medienSatz)
        assertEquals("the media are not asked one by one", 1, homes.anfragen.count { it.first.contains("/Media/") })
        assertArrayEquals(homes.dateien.getValue("whatsapp|Databases/msgstore.db.crypt15").first, ziel.datei("Databases/msgstore.db.crypt15").readBytes())
        assertNull(ziel.groesse("Media/WhatsApp Images/IMG 1.jpg"))

        // the archive open: the media come, the database is there already
        homes.archivZu = false
        val plan = r().planen(e, ziel)
        assertEquals(listOf("Databases/msgstore.db.crypt15"), plan.schon.map { it.ziel })
        assertEquals(Rueckholung.Art.FERTIG, r().laufen(plan, ziel).art)
        assertEquals(3000L, ziel.groesse("Media/WhatsApp Images/IMG 1.jpg"))
    }

    /**
     * archiv#64: Signal's backup and WhatsApp's database too lie encrypted for
     * the archive: while it is closed nothing comes, the run stops with Homes'
     * sentence (and the screen's „Archiv öffnen“), and nothing is written.
     */
    @Test
    fun aClosedArchiveSinceArchiv64GivesNothing() {
        val e = whatsapp()
        homes.archivZu = true
        homes.allesImArchiv = true
        val a = r().laufen(r().planen(e, ziel), ziel)
        assertEquals(Rueckholung.Art.HOMES, a.art)
        assertEquals(Rueckholung.MEDIEN_ZU, a.medienSatz)
        assertNull(a.satz)
        assertNull(ziel.groesse("Databases/msgstore.db.crypt15"))
        val s = homes.lege(Quelle.SIGNAL, "signal-1.backup", FakeHomes.bytes(10, 4))
        val b = r().laufen(r().planen(Rueckholung.eintraege(Quelle.SIGNAL, listOf(s)), ziel), ziel)
        assertEquals(Rueckholung.Art.HOMES to Rueckholung.MEDIEN_ZU, b.art to b.medienSatz)
    }

    @Test
    fun aBackupGoneFromTheBoxAsksForTheListAgain() {
        val d = LetzteDatei("signal-alt.backup", 10, 0)
        val a = r().laufen(r().planen(Rueckholung.eintraege(Quelle.SIGNAL, listOf(d)), ziel), ziel)
        assertEquals(Rueckholung.Art.VERALTET, a.art)
        assertEquals(Rueckholung.VERALTET, a.satz)
    }

    @Test
    fun whatsappsFolderAsTheyGrantIt() {
        assertEquals("", Rueckholung.whatsappUnter("primary:Android/media/com.whatsapp/WhatsApp"))
        assertEquals("WhatsApp/", Rueckholung.whatsappUnter("primary:Android/media/com.whatsapp"))
        assertEquals("com.whatsapp/WhatsApp/", Rueckholung.whatsappUnter("primary:Android/media"))
        assertNull(Rueckholung.whatsappUnter("primary:Download"))
        assertNull(Rueckholung.whatsappUnter("primary:"))
        val d = LetzteDatei("Databases/msgstore.db.crypt15", 1, 0)
        assertEquals("com.whatsapp/WhatsApp/Databases/msgstore.db.crypt15", Rueckholung.eintraege(Quelle.WHATSAPP, listOf(d), "com.whatsapp/WhatsApp/").single().ziel)
        assertEquals("s.backup", Rueckholung.eintraege(Quelle.SIGNAL, listOf(LetzteDatei("s.backup", 1, 0)), "x/").single().ziel)
    }

    @Test
    fun theArchiveBesideHomes() {
        assertEquals("https://archiv.dopp.cloud/", Rueckholung.archivAdresse("https://homes.dopp.cloud"))
        assertEquals("https://archiv.dopp.cloud/", Rueckholung.archivAdresse("https://www.dopp.cloud")) // Homes since 2026-11-07
        assertEquals("https://archiv.example.org/", Rueckholung.archivAdresse("https://homes.example.org/"))
        // whatever the first label is: the old address, another name, a port
        assertEquals("https://archiv.dopp.cloud/", Rueckholung.archivAdresse("https://haus.dopp.cloud/start"))
        assertEquals("https://archiv.example.org/", Rueckholung.archivAdresse("https://box.example.org"))
        assertEquals("https://archiv.example.org:8443/", Rueckholung.archivAdresse("https://homes.example.org:8443"))
        assertNull(Rueckholung.archivAdresse("http://homes.example.org"))
        assertNull("a host of one label", Rueckholung.archivAdresse("https://homes"))
        assertNull("an IP address", Rueckholung.archivAdresse("https://192.168.178.10"))
    }

    @Test
    fun theTotalsReadAsAPersonReadsThem() {
        assertEquals("1,2 von 9,8 GB", Anzeige.anteil(1_200_000_000, 9_800_000_000))
        assertEquals("0 von 120 MB", Anzeige.anteil(0, 120_000_000))
        assertEquals("120 von 120 MB", Anzeige.anteil(120_000_000, 120_000_000))
        assertEquals("500 von 999 B", Anzeige.anteil(500, 999))
    }

    @Test
    fun theListAndTheRangeAsHomesSendsThem() {
        val l = SicherungClient.letzteAus(
            200,
            """{"quellen":{"signal":[{"pfad":"signal-1.backup","groesse":6,"geaendert":1762142400000}],"whatsapp":[{"pfad":"Media/a.jpg","groesse":3,"geaendert":0},{"pfad":"","groesse":1}]}}""",
        ) as SicherungClient.Letzte.Da
        assertEquals(listOf(LetzteDatei("signal-1.backup", 6, 1762142400000)), l.quellen[Quelle.SIGNAL])
        assertEquals(listOf(LetzteDatei("Media/a.jpg", 3, 0)), l.quellen[Quelle.WHATSAPP])
        assertEquals(SicherungClient.Letzte.NichtGekoppelt, SicherungClient.letzteAus(401, null))
        assertEquals("Kein Archiv.", (SicherungClient.letzteAus(503, """{"fehler":"Kein Archiv."}""") as SicherungClient.Letzte.Fehler).satz)
        assertEquals(1048576L, SicherungClient.startAus("bytes 1048576-3221225471/3221225472"))
        assertNull(SicherungClient.startAus("bytes */3221225472"))
        assertEquals(
            "/napi/sicherung/letzte/whatsapp/Media/WhatsApp%20Images/a%2Bb.jpg",
            Napi.SICHERUNG_LETZTE_DATEI.with("whatsapp", "Media/WhatsApp Images/a+b.jpg").path,
        )
    }
}
