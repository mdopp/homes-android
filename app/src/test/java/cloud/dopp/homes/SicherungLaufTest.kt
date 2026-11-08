package cloud.dopp.homes

import cloud.dopp.homes.data.Napi.Quelle
import cloud.dopp.homes.data.SicherungClient
import cloud.dopp.homes.sicherung.Gedaechtnis
import cloud.dopp.homes.sicherung.Gesendet
import cloud.dopp.homes.sicherung.Lauf
import cloud.dopp.homes.sicherung.Meldung
import cloud.dopp.homes.sicherung.Ordner
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

/** [Gedaechtnis] in memory: what [cloud.dopp.homes.sicherung.SicherungStore] keeps in prefs. */
class MapGedaechtnis : Gedaechtnis {
    val gesendet = HashMap<Quelle, Gesendet>()
    val meldungen = HashMap<Quelle, Meldung>()
    val uebertragen = HashMap<Quelle, Pair<Long, Int>>()
    private val uploads = HashMap<String, String>()

    override fun gesendet(quelle: Quelle) = gesendet[quelle]
    override fun merkeGesendet(quelle: Quelle, g: Gesendet) { gesendet[quelle] = g }
    override fun merkeUebertragen(quelle: Quelle, zeit: Long, dateien: Int) { uebertragen[quelle] = zeit to dateien }
    override fun melde(quelle: Quelle, meldung: Meldung) { meldungen[quelle] = meldung }
    override fun upload(quelle: Quelle, pfad: String) = uploads["${quelle.wire}|$pfad"]
    override fun merkeUpload(quelle: Quelle, pfad: String, zeile: String) { uploads["${quelle.wire}|$pfad"] = zeile }
    override fun vergissUpload(quelle: Quelle, pfad: String) { uploads.remove("${quelle.wire}|$pfad") }
}

/**
 * One run of Daten sichern (#194) over two folders on disk against the
 * fake Homes: what goes, what stays, what the screen is told — and that a run
 * without a granted folder asks Homes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SicherungLaufTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var homes: FakeHomes
    private lateinit var whatsapp: DateiOrdner
    private lateinit var signal: DateiOrdner
    private val g = MapGedaechtnis()

    @Before
    fun auf() {
        homes = FakeHomes()
        whatsapp = DateiOrdner(File(tmp.root, "WhatsApp").apply { mkdirs() })
        signal = DateiOrdner(File(tmp.root, "Signal").apply { mkdirs() })
    }

    @After
    fun zu() = homes.schliessen()

    private fun lauf(ordner: (Quelle) -> Ordner? = { q -> if (q == Quelle.SIGNAL) signal else whatsapp }, halt: () -> Boolean = { false }) =
        Lauf(SicherungClient(homes.base, FakeHomes.TOKEN, homes.http), g, ordner, halt = halt, teil = 1024)

    private fun legeWhatsapp(): Map<String, ByteArray> {
        val dateien = mapOf(
            "Databases/msgstore.db.crypt15" to FakeHomes.bytes(5000, 1),
            "Databases/msgstore-2026-10-31.1.db.crypt15" to FakeHomes.bytes(4900, 2),
            "Media/WhatsApp Images/IMG-20261101-WA0001.jpg" to FakeHomes.bytes(3000, 3),
            "Media/WhatsApp Images/Sent/IMG-20261101-WA0002.jpg" to FakeHomes.bytes(2000, 4),
            "Media/WhatsApp Video/VID-20261101-WA0003.mp4" to FakeHomes.bytes(2500, 5),
            "Media/.Statuses/status.jpg" to FakeHomes.bytes(100, 6),
            "Media/WhatsApp Images/.nomedia" to FakeHomes.bytes(1, 7),
        )
        dateien.forEach { (p, b) -> whatsapp.lege(p, b) }
        return dateien
    }

    @Test
    fun withoutAGrantedFolderNothingIsAsked() {
        assertEquals(Lauf.Ergebnis.NICHTS, lauf(ordner = { null }).laufen())
        assertTrue(homes.anfragen.isEmpty())
    }

    @Test
    fun whatsappSendsTheDatabaseAndTheMissingMediaOnly() {
        val dateien = legeWhatsapp()
        homes.vorhanden += "whatsapp|Media/WhatsApp Video/VID-20261101-WA0003.mp4"
        assertEquals(Lauf.Ergebnis.FERTIG, lauf(ordner = { q -> if (q == Quelle.WHATSAPP) whatsapp else null }).laufen())
        assertEquals(
            setOf(
                "whatsapp|Databases/msgstore.db.crypt15",
                "whatsapp|Media/WhatsApp Images/IMG-20261101-WA0001.jpg",
                "whatsapp|Media/WhatsApp Images/Sent/IMG-20261101-WA0002.jpg",
            ),
            homes.fertig.keys,
        )
        assertArrayEquals(dateien["Databases/msgstore.db.crypt15"], homes.fertig["whatsapp|Databases/msgstore.db.crypt15"])
        assertEquals(Meldung.Art.OK, g.meldungen[Quelle.WHATSAPP]?.art)
        assertEquals(3, g.uebertragen[Quelle.WHATSAPP]?.second)

        // the next run: the same msgstore is not sent again, and Homes has the media
        val vorher = homes.anfragen.size
        assertEquals(Lauf.Ergebnis.FERTIG, lauf(ordner = { q -> if (q == Quelle.WHATSAPP) whatsapp else null }).laufen())
        val danach = homes.anfragen.drop(vorher)
        assertEquals(listOf("/napi/sicherung/fehlend"), danach.map { it.path })
    }

    /** Owner, 2026-11-01: „3809 von 18 017 Dateien, 1,2 von 9,8 GB“, not the bytes of one file. */
    @Test
    fun theProgressIsTheTotalOfTheRun() {
        legeWhatsapp()
        val gemeldet = ArrayList<cloud.dopp.homes.sicherung.Fortschritt>()
        val l = Lauf(
            SicherungClient(homes.base, FakeHomes.TOKEN, homes.http), g, { q -> if (q == Quelle.WHATSAPP) whatsapp else null },
            melde = { gemeldet += it }, teil = 1024,
        )
        assertEquals(Lauf.Ergebnis.FERTIG, l.laufen())
        // the database and three media: 5000 + 3000 + 2000 + 2500 bytes
        assertTrue(gemeldet.isNotEmpty())
        assertTrue(gemeldet.all { it.von == 4 && it.gesamt == 12_500L })
        assertEquals(12_500L, gemeldet.last().bytes)
        assertEquals(3, gemeldet.last().fertig) // the last file under way: three behind it
        assertTrue("bytes only grow", gemeldet.zipWithNext().all { (a, b) -> b.bytes >= a.bytes && b.fertig >= a.fertig })
        assertEquals(listOf(0, 1, 2, 3), gemeldet.map { it.fertig }.distinct())
    }

    @Test
    fun signalSendsTheNewestBackupOnceAndThenOnlyANewerOne() {
        val stunde = 60 * 60 * 1000L
        signal.lege("signal-2026-10-31-03-00-00.backup", FakeHomes.bytes(3000, 1), alterMs = 26 * stunde)
        val heute = FakeHomes.bytes(3500, 2)
        signal.lege("signal-2026-11-01-03-00-00.backup", heute, alterMs = 2 * stunde)
        val nurSignal: (Quelle) -> Ordner? = { q -> if (q == Quelle.SIGNAL) signal else null }

        assertEquals(Lauf.Ergebnis.FERTIG, lauf(nurSignal).laufen())
        assertEquals(setOf("signal|signal-2026-11-01-03-00-00.backup"), homes.fertig.keys)
        assertArrayEquals(heute, homes.fertig.values.single())

        val vorher = homes.anfragen.size
        assertEquals(Lauf.Ergebnis.FERTIG, lauf(nurSignal).laufen())
        assertEquals("nothing new, nothing asked", vorher, homes.anfragen.size)

        signal.lege("signal-2026-11-02-03-00-00.backup", FakeHomes.bytes(3600, 3), alterMs = stunde)
        assertEquals(Lauf.Ergebnis.FERTIG, lauf(nurSignal).laufen())
        assertTrue("signal|signal-2026-11-02-03-00-00.backup" in homes.fertig.keys)
        assertEquals(2, homes.fertig.size)
    }

    @Test
    fun aWithdrawnFolderSaysSoInGerman() {
        legeWhatsapp()
        whatsapp.entzogen = true
        assertEquals(Lauf.Ergebnis.FERTIG, lauf(ordner = { q -> if (q == Quelle.WHATSAPP) whatsapp else null }).laufen())
        assertEquals(
            Meldung.Art.ORDNER to "WhatsApp-Ordner nicht mehr freigegeben.",
            g.meldungen[Quelle.WHATSAPP]!!.let { it.art to it.satz },
        )
        assertTrue(homes.anfragen.isEmpty())
    }

    @Test
    fun anEmptyFolderPointsToTheGuide() {
        assertEquals(Lauf.Ergebnis.FERTIG, lauf().laufen())
        assertEquals(Meldung.Art.KEINE, g.meldungen[Quelle.SIGNAL]?.art)
        assertEquals(Meldung.Art.KEINE, g.meldungen[Quelle.WHATSAPP]?.art)
    }

    @Test
    fun noArchiveOnTheBoxStopsWithHomesSentence() {
        legeWhatsapp()
        homes.keinArchiv = true
        assertEquals(Lauf.Ergebnis.HALT, lauf().laufen())
        assertEquals(
            Meldung.Art.HOMES to "Auf dieser Box ist noch kein Nachrichten-Archiv eingerichtet.",
            g.meldungen[Quelle.WHATSAPP]!!.let { it.art to it.satz },
        )
        assertTrue(homes.fertig.isEmpty())
    }

    @Test
    fun aFullBoxStopsTheRun() {
        legeWhatsapp()
        signal.lege("signal-2026-11-01-03-00-00.backup", FakeHomes.bytes(3500, 2))
        homes.voll = true
        assertEquals(Lauf.Ergebnis.HALT, lauf().laufen())
        assertEquals(Meldung.Art.HOMES, g.meldungen[Quelle.WHATSAPP]?.art)
        assertNull("Signal is not tried once the box is full", g.meldungen[Quelle.SIGNAL])
    }

    /** Stopped mid-run (WLAN gone): the next run goes on and sends nothing twice. */
    @Test
    fun aStoppedRunGoesOnNextTime() {
        val dateien = legeWhatsapp()
        var stop = true
        assertEquals(Lauf.Ergebnis.WEITER, lauf(halt = { stop && homes.patches().size >= 3 }).laufen())
        assertEquals(Meldung.Art.SPAETER, g.meldungen[Quelle.WHATSAPP]?.art)
        stop = false
        assertEquals(Lauf.Ergebnis.FERTIG, lauf().laufen())
        assertArrayEquals(dateien["Databases/msgstore.db.crypt15"], homes.fertig["whatsapp|Databases/msgstore.db.crypt15"])
        val gesendet = homes.patches().sumOf { it.laenge }
        val noetig = listOf(
            "Databases/msgstore.db.crypt15",
            "Media/WhatsApp Images/IMG-20261101-WA0001.jpg",
            "Media/WhatsApp Images/Sent/IMG-20261101-WA0002.jpg",
            "Media/WhatsApp Video/VID-20261101-WA0003.mp4",
        ).sumOf { dateien.getValue(it).size.toLong() }
        assertEquals("every byte once", noetig, gesendet)
    }

    /**
     * Homes refuses a whole `fehlend` for one dot-name (400): even from a folder
     * reader that lists them, no path with a dot-name at any level is asked
     * about or sent, and the run goes through.
     */
    @Test
    fun dotNamesAtAnyLevelAreNeverSent() {
        legeWhatsapp()
        whatsapp.lege("Media/WhatsApp Images/Sent/.thumbs/t.jpg", FakeHomes.bytes(50, 8))
        whatsapp.lege("Media/.trash/x.jpg", FakeHomes.bytes(50, 9))
        whatsapp.zeigtVersteckte = true
        assertEquals(Lauf.Ergebnis.FERTIG, lauf(ordner = { q -> if (q == Quelle.WHATSAPP) whatsapp else null }).laufen())
        assertTrue(homes.fertig.keys.none { k -> k.substringAfter('|').split('/').any { it.startsWith(".") } })
        assertEquals(4, homes.fertig.size)
        assertEquals(Meldung.Art.OK, g.meldungen[Quelle.WHATSAPP]?.art)
    }

    /** A busy box (429) ends the run with "soon again"; the next run sends the rest. */
    @Test
    fun aBusyBoxEndsTheRunForNow() {
        legeWhatsapp()
        homes.beschaeftigt = 1
        assertEquals(Lauf.Ergebnis.BESCHAEFTIGT, lauf().laufen())
        assertEquals(Lauf.BESCHAEFTIGT, g.meldungen[Quelle.WHATSAPP]?.satz)
        assertEquals(Lauf.Ergebnis.FERTIG, lauf().laufen())
        assertEquals(4, homes.fertig.count { it.key.startsWith("whatsapp|") })
    }

    @Test
    fun aDeviceOfNobodyStopsWithHomesSentence() {
        assertEquals(
            "Dieses Gerät gehört zu keiner Person: kopple die Homes-App neu, angemeldet mit deinem Namen.",
            Lauf.satzFuer(403, null),
        )
        assertEquals(Lauf.NICHT_GEKOPPELT, Lauf.satzFuer(401, "unauthorized"))
    }

    @Test
    fun homesUnreachableWaitsForTheNextRun() {
        legeWhatsapp()
        val base = homes.base // read before the shutdown: MockWebServer starts again on url()
        homes.schliessen()
        val lauf = Lauf(SicherungClient(base, FakeHomes.TOKEN, homes.http), g, { q -> if (q == Quelle.SIGNAL) signal else whatsapp }, teil = 1024)
        assertEquals(Lauf.Ergebnis.UNERREICHBAR, lauf.laufen())
        assertEquals(Lauf.UNERREICHBAR, g.meldungen[Quelle.WHATSAPP]?.satz)
    }
}
