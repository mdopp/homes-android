package cloud.dopp.homes

import cloud.dopp.homes.data.Napi.Quelle
import cloud.dopp.homes.data.SicherungClient
import cloud.dopp.homes.sicherung.Age
import cloud.dopp.homes.sicherung.Datei
import cloud.dopp.homes.sicherung.Uploader
import cloud.dopp.homes.sicherung.Uploader.Ausgang
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

/**
 * The resumable upload (#194) against a fake Homes that keeps the contract:
 * a file goes in pieces, and after any interruption it goes on where Homes
 * stands — a connection cut mid-piece, a write Homes lost (409), an upload Homes
 * forgot (404) — and stops with Homes' sentence when the box is full.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SicherungUploadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var homes: FakeHomes
    private lateinit var ordner: DateiOrdner
    private val merker = MapGedaechtnis()

    private fun client() = SicherungClient(homes.base, FakeHomes.TOKEN, homes.http)

    private fun uploader(teil: Int = TEIL) = Uploader(client(), merker, teil)

    @Before
    fun auf() {
        homes = FakeHomes().apply { nurAge = false } // the mechanics alone; encrypted: SicherungLaufTest
        ordner = DateiOrdner(tmp.root)
    }

    @After
    fun zu() = homes.schliessen()

    private fun datei(n: Int, pfad: String = "signal-2026-11-01-03-00-00.backup"): Pair<Datei, ByteArray> {
        val inhalt = FakeHomes.bytes(n)
        val f = ordner.lege(pfad, inhalt)
        return Datei(pfad, f.length(), f.lastModified()) to inhalt
    }

    @Test
    fun aFileGoesInPiecesAndArrivesWhole() {
        val (d, inhalt) = datei(5 * TEIL + 123)
        assertEquals(Ausgang.Fertig, uploader().hochladen(Quelle.SIGNAL, ordner, d))
        assertArrayEquals(inhalt, homes.fertig.getValue("signal|${d.pfad}"))
        val patches = homes.patches()
        assertEquals(6, patches.size)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L).map { (it * TEIL).toString() }, patches.map { it.offset })
        patches.forEach {
            assertEquals("application/offset+octet-stream", it.typ)
            assertEquals("Bearer homes_device_TEST", it.auth)
            assertTrue(it.laenge <= TEIL)
        }
        assertNull("a finished upload is forgotten", merker.upload(Quelle.SIGNAL, d.pfad))
    }

    /** The piece is 8 MiB on the phone; Homes takes up to 16. */
    @Test
    fun aPieceIsEightMebibytes() {
        assertEquals(8 * 1024 * 1024, Uploader.TEIL)
        val (d, inhalt) = datei(Uploader.TEIL * 2 + 5)
        assertEquals(Ausgang.Fertig, Uploader(client(), merker).hochladen(Quelle.SIGNAL, ordner, d))
        assertEquals(listOf(Uploader.TEIL.toLong(), Uploader.TEIL.toLong(), 5L), homes.patches().map { it.laenge })
        assertArrayEquals(inhalt, homes.fertig.getValue("signal|${d.pfad}"))
    }

    /** WLAN gone mid-piece: Homes kept half of it; the next run asks where Homes stands and goes on from there. */
    @Test
    fun aCutMidFileGoesOnWhereHomesStands() {
        val (d, inhalt) = datei(6 * TEIL)
        homes.schneideNach = TEIL / 2
        var gesendet = 0
        val erster = uploader().hochladen(Quelle.SIGNAL, ordner, d, halt = { false }) { gesendet++ }
        // the first piece is cut: no answer, the run ends there
        assertEquals(Ausgang.Fehler(0, null), erster)
        val id = homes.uploads.keys.single()
        assertEquals((TEIL / 2).toLong(), homes.uploads.getValue(id).offset)
        assertTrue("the id outlives the run", merker.upload(Quelle.SIGNAL, d.pfad)!!.endsWith("|$id"))

        val vorher = homes.anfragen.size
        assertEquals(Ausgang.Fertig, uploader().hochladen(Quelle.SIGNAL, ordner, d))
        val danach = homes.anfragen.drop(vorher)
        assertEquals("the next run asks first", "HEAD", danach.first().method)
        assertEquals((TEIL / 2).toString(), danach[1].offset)
        assertTrue("no new upload", danach.none { it.method == "POST" })
        assertArrayEquals(inhalt, homes.fertig.getValue("signal|${d.pfad}"))
    }

    /**
     * archiv#64: encrypted to the archive as it is read, a backup survives the
     * same cuts — mid-piece, a lost write (409), an upload Homes forgot (404,
     * a new encryption from the start) — and the archive reads it whole.
     */
    @Test
    fun anEncryptedFileSurvivesCutsAndArrivesReadable() {
        homes.nurAge = true
        val an = Age.empfaenger(AgeTest.EMPFAENGER)
        val zumArchiv = { Uploader(client(), merker, TEIL, an) }
        val lesen = { b: ByteArray -> AgeLeser.entschluesseln(AgeLeser.identitaet(AgeTest.IDENTITAET), b.inputStream()) }

        val (d, inhalt) = datei(6 * TEIL + 5)
        homes.schneideNach = TEIL / 2 + 3
        assertEquals(Ausgang.Fehler(0, null), zumArchiv().hochladen(Quelle.SIGNAL, ordner, d, "${d.pfad}.age"))
        var stueck = 0
        assertEquals(
            Ausgang.Fertig,
            zumArchiv().hochladen(Quelle.SIGNAL, ordner, d, "${d.pfad}.age", halt = {
                if (++stueck == 3) homes.verliere = TEIL / 3
                false
            }),
        )
        assertEquals(Age.groesse(d.groesse), homes.fertig.getValue("signal|${d.pfad}.age").size.toLong())
        assertArrayEquals(inhalt, lesen(homes.fertig.getValue("signal|${d.pfad}.age")))
        assertNull("the randomness goes with the finished upload", merker.upload(Quelle.SIGNAL, "${d.pfad}.age"))

        val (d2, inhalt2) = datei(3 * TEIL, "signal-2026-11-02-03-00-00.backup")
        val bisher = homes.patches().size
        assertEquals(Ausgang.Angehalten, zumArchiv().hochladen(Quelle.SIGNAL, ordner, d2, "${d2.pfad}.age", halt = { homes.patches().size >= bisher + 2 }))
        homes.vergiss()
        assertEquals(Ausgang.Fertig, zumArchiv().hochladen(Quelle.SIGNAL, ordner, d2, "${d2.pfad}.age"))
        assertArrayEquals(inhalt2, lesen(homes.fertig.getValue("signal|${d2.pfad}.age")))
    }

    /** Homes lost the last write: the PATCH gets 409 and the upload goes on from Homes' offset, in the same run. */
    @Test
    fun aConflictGoesOnFromHomesOffset() {
        val (d, inhalt) = datei(4 * TEIL + 77)
        val u = uploader()
        var stueck = 0
        homes.verliere = null
        val a = u.hochladen(Quelle.SIGNAL, ordner, d, halt = {
            stueck++
            if (stueck == 3) homes.verliere = TEIL + TEIL / 3 // before the third piece
            false
        })
        assertEquals(Ausgang.Fertig, a)
        val p = homes.patches()
        assertTrue("a 409 came", p.zip(p.drop(1)).any { (x, y) -> y.offset!!.toLong() < x.offset!!.toLong() + TEIL })
        assertArrayEquals(inhalt, homes.fertig.getValue("signal|${d.pfad}"))
    }

    /** Homes forgot the partial upload: HEAD answers 404 and the upload starts anew. */
    @Test
    fun aForgottenUploadStartsAnew() {
        val (d, inhalt) = datei(3 * TEIL)
        assertEquals(Ausgang.Angehalten, uploader().hochladen(Quelle.SIGNAL, ordner, d, halt = { homes.patches().size >= 2 }))
        homes.vergiss()
        val vorher = homes.anfragen.size
        assertEquals(Ausgang.Fertig, uploader().hochladen(Quelle.SIGNAL, ordner, d))
        val danach = homes.anfragen.drop(vorher).map { it.method }
        assertEquals(listOf("HEAD", "POST", "PATCH", "PATCH", "PATCH"), danach)
        assertArrayEquals(inhalt, homes.fertig.getValue("signal|${d.pfad}"))
    }

    /** Forgotten in the middle of a run: the PATCH answers 404 and a new upload begins at once. */
    @Test
    fun aPatchToAForgottenUploadStartsAnew() {
        val (d, inhalt) = datei(3 * TEIL)
        var n = 0
        val a = uploader().hochladen(Quelle.SIGNAL, ordner, d, halt = {
            if (++n == 2) homes.vergiss()
            false
        })
        assertEquals(Ausgang.Fertig, a)
        assertEquals(2, homes.anfragen.count { it.method == "POST" })
        assertArrayEquals(inhalt, homes.fertig.getValue("signal|${d.pfad}"))
    }

    /** Stopped between two pieces (constraints lost): the next run goes on, nothing is sent twice. */
    @Test
    fun aStopEndsBetweenTwoPiecesAndTheNextRunGoesOn() {
        val (d, inhalt) = datei(4 * TEIL)
        assertEquals(Ausgang.Angehalten, uploader().hochladen(Quelle.SIGNAL, ordner, d, halt = { homes.patches().size >= 2 }))
        assertEquals(Ausgang.Fertig, uploader().hochladen(Quelle.SIGNAL, ordner, d))
        assertEquals(4, homes.patches().size)
        assertArrayEquals(inhalt, homes.fertig.getValue("signal|${d.pfad}"))
    }

    /** A remembered id for another version of the file (other size or time) is not resumed. */
    @Test
    fun aChangedFileIsNotResumedOnTheOldId() {
        val (d, _) = datei(3 * TEIL)
        assertEquals(Ausgang.Angehalten, uploader().hochladen(Quelle.SIGNAL, ordner, d, halt = { homes.patches().isNotEmpty() }))
        val (d2, inhalt2) = datei(2 * TEIL)
        val vorher = homes.anfragen.size
        assertEquals(Ausgang.Fertig, uploader().hochladen(Quelle.SIGNAL, ordner, d2))
        assertTrue("no HEAD on the old id", homes.anfragen.drop(vorher).none { it.method == "HEAD" })
        assertArrayEquals(inhalt2, homes.fertig.getValue("signal|${d2.pfad}"))
    }

    /**
     * The last piece arrived but its answer was lost: Homes keeps the finished
     * upload known, HEAD says offset = size, and that is done — nothing is sent again.
     */
    @Test
    fun aLostLastAnswerIsDoneAfterHead() {
        val (d, inhalt) = datei(3 * TEIL)
        var n = 0
        val erster = uploader().hochladen(Quelle.SIGNAL, ordner, d, halt = {
            if (++n == 3) homes.schneideNach = TEIL // the whole last piece lands, the answer does not
            false
        })
        assertEquals(Ausgang.Fehler(0, null), erster)
        assertArrayEquals("Homes has it whole", inhalt, homes.fertig.getValue("signal|${d.pfad}"))

        val vorher = homes.anfragen.size
        assertEquals(Ausgang.Fertig, uploader().hochladen(Quelle.SIGNAL, ordner, d))
        assertEquals("one HEAD, no piece again", listOf("HEAD"), homes.anfragen.drop(vorher).map { it.method })
        assertNull(merker.upload(Quelle.SIGNAL, d.pfad))
    }

    /** A busy box (429) is no answer about the file: the run ends and the next one goes on, nothing lost. */
    @Test
    fun aBusyBoxIsTriedAgainLater() {
        val (d, inhalt) = datei(3 * TEIL)
        homes.beschaeftigt = 1
        assertEquals(Ausgang.Fehler(429, "Die Box nimmt gerade schon andere Sicherungen an. Versuch es gleich noch einmal."), uploader().hochladen(Quelle.SIGNAL, ordner, d))
        assertEquals(Ausgang.Fertig, uploader().hochladen(Quelle.SIGNAL, ordner, d))
        assertArrayEquals(inhalt, homes.fertig.getValue("signal|${d.pfad}"))
    }

    @Test
    fun aFullBoxStopsWithHomesSentence() {
        val (d, _) = datei(2 * TEIL)
        homes.voll = true
        assertEquals(Ausgang.Voll("Auf der Box ist kein Platz mehr frei."), uploader().hochladen(Quelle.SIGNAL, ordner, d))
        assertEquals(1, homes.patches().size)
    }

    @Test
    fun theReplyShapesReadAsTheContractSaysThem() {
        assertEquals(SicherungClient.Teil.Weiter(8, true), SicherungClient.teilAus(200, """{"offset":8,"fertig":true}"""))
        assertEquals(SicherungClient.Teil.Versatz(3), SicherungClient.teilAus(409, """{"fehler":"x","offset":3}"""))
        // a cut while Homes wrote: 408 with how far it got
        assertEquals(SicherungClient.Teil.Versatz(5), SicherungClient.teilAus(408, """{"fehler":"x","offset":5}"""))
        assertEquals(SicherungClient.Teil.Unbekannt, SicherungClient.teilAus(404, """{"fehler":"x"}"""))
        assertEquals(SicherungClient.Teil.Voll("Voll."), SicherungClient.teilAus(507, """{"fehler":"Voll."}"""))
        val stand = SicherungClient.standAus(
            200,
            """{"quellen":{"whatsapp":{"zuletzt":"2026-11-01T03:12:00Z","dateien":2,"bytes":30,"wartend":1},"signal":{"zuletzt":null,"dateien":0,"bytes":0,"wartend":0}},"frei_bytes":99}""",
        ) as SicherungClient.Stand.Da
        assertEquals(SicherungClient.QuelleStand("2026-11-01T03:12:00Z", 2, 30, 1), stand.quellen[Quelle.WHATSAPP])
        assertEquals(SicherungClient.QuelleStand(null, 0, 0, 0), stand.quellen[Quelle.SIGNAL])
        assertEquals(99L, stand.freiBytes)
        assertEquals(
            SicherungClient.Stand.Fehler("Kein Archiv."),
            SicherungClient.standAus(503, """{"fehler":"Kein Archiv."}"""),
        )
        assertEquals(SicherungClient.Stand.NichtGekoppelt, SicherungClient.standAus(401, """{"error":"unauthorized"}"""))
    }

    /** One dot-name in one path fails Homes' whole question: the client refuses to ask it. */
    @Test
    fun noDotNameIsAskedAbout() {
        val c = client()
        listOf("Media/.Statuses/x.jpg", ".nomedia", "Media/WhatsApp Images/Sent/.x/IMG.jpg").forEach { p ->
            val e = runCatching { c.fehlend(Quelle.WHATSAPP, listOf("Media/a.jpg" to 1L, p to 1L)) }.exceptionOrNull()
            assertTrue("$p: $e", e is IllegalArgumentException)
        }
        assertTrue("nothing went out", homes.anfragen.isEmpty())
        assertEquals(setOf("Media/a.jpg"), c.fehlend(Quelle.WHATSAPP, listOf("Media/a.jpg" to 1L)))
    }

    @Test
    fun standAgainstTheFakeHomes() {
        val s = client().stand() as SicherungClient.Stand.Da
        assertEquals(setOf(Quelle.WHATSAPP, Quelle.SIGNAL, Quelle.SMS), s.quellen.keys)
        homes.keinArchiv = true
        assertEquals(
            SicherungClient.Stand.Fehler("Auf dieser Box ist noch kein Nachrichten-Archiv eingerichtet."),
            client().stand(),
        )
    }

    private companion object {
        /** Small pieces in the tests; [aPieceIsEightMebibytes] holds the real size. */
        const val TEIL = 1024
    }
}
