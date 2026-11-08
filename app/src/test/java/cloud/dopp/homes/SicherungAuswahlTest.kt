package cloud.dopp.homes

import cloud.dopp.homes.sicherung.Anzeige
import cloud.dopp.homes.sicherung.Auswahl
import cloud.dopp.homes.sicherung.Auswahl.Wahl
import cloud.dopp.homes.sicherung.Datei
import cloud.dopp.homes.sicherung.Gesendet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * What is new in the granted folders (#194): WhatsApp's current database
 * backup, Signal's newest backup only when newer than the one sent last, the
 * media Homes lacks, never a dot-name — and nothing still being written.
 */
class SicherungAuswahlTest {

    private val jetzt = 1_800_000_000_000L
    private val stunde = 60 * 60 * 1000L

    private fun d(pfad: String, groesse: Long = 100, alter: Long = stunde) = Datei(pfad, groesse, jetzt - alter)

    @Test
    fun whatsappSendsTheCurrentMsgstoreNotTheDatedOnes() {
        val db = listOf(
            d("Databases/msgstore-2026-10-30.1.db.crypt15", alter = 50 * stunde),
            d("Databases/msgstore.db.crypt15", groesse = 4000),
            d("Databases/msgstore-2026-10-31.1.db.crypt15", alter = 26 * stunde),
            d("Databases/wa.db.crypt15"),
        )
        val w = Auswahl.msgstore(db, null, jetzt)
        assertEquals(Wahl.Senden(d("Databases/msgstore.db.crypt15", groesse = 4000)), w)
        assertEquals("Databases/msgstore.db.crypt15", Auswahl.MSGSTORE_PFAD)
    }

    @Test
    fun whatsappSendsTheSameMsgstoreOnlyOnce() {
        val heute = d("Databases/msgstore.db.crypt15", groesse = 4000)
        assertEquals(Wahl.Schon, Auswahl.msgstore(listOf(heute), Gesendet.von(heute), jetzt))
        val morgen = d("Databases/msgstore.db.crypt15", groesse = 4100, alter = stunde / 2)
        assertEquals(Wahl.Senden(morgen), Auswahl.msgstore(listOf(morgen), Gesendet.von(heute), jetzt))
    }

    @Test
    fun noMsgstoreIsNone() {
        assertEquals(Wahl.Keine, Auswahl.msgstore(listOf(d("Databases/wa.db.crypt15")), null, jetzt))
        assertEquals(Wahl.Keine, Auswahl.msgstore(listOf(d("Databases/msgstore.db.crypt15", groesse = 0)), null, jetzt))
    }

    @Test
    fun aBackupStillBeingWrittenWaits() {
        val frisch = d("Databases/msgstore.db.crypt15", alter = 60_000)
        assertEquals(Wahl.Wartet(frisch), Auswahl.msgstore(listOf(frisch), null, jetzt))
        val signal = d("signal-2026-11-01-03-00-00.backup", alter = 60_000)
        assertEquals(Wahl.Wartet(signal), Auswahl.signal(listOf(signal), null, jetzt))
    }

    @Test
    fun signalTakesOnlyTheNewestBackup() {
        val alt = d("signal-2026-10-31-03-00-00.backup", groesse = 3_000_000_000, alter = 26 * stunde)
        val neu = d("signal-2026-11-01-03-00-00.backup", groesse = 3_100_000_000, alter = 2 * stunde)
        val andere = listOf(d("notes.txt", alter = 1), d("Sub/signal-2026-11-01-04-00-00.backup", alter = 1 * stunde))
        assertEquals(Wahl.Senden(neu), Auswahl.signal(listOf(alt, neu) + andere, null, jetzt))
    }

    @Test
    fun signalTakesTheNewNamingToo() {
        val neu = d("signal-backup-2026-11-01-03-00-00.backup")
        assertEquals(Wahl.Senden(neu), Auswahl.signal(listOf(neu), null, jetzt))
    }

    @Test
    fun signalSendsOnlyWhatIsNewerThanTheLastOneSent() {
        val gestern = d("signal-2026-10-31-03-00-00.backup", alter = 26 * stunde)
        val heute = d("signal-2026-11-01-03-00-00.backup", alter = 2 * stunde)
        assertEquals("the one sent last", Wahl.Schon, Auswahl.signal(listOf(gestern, heute), Gesendet.von(heute), jetzt))
        assertEquals("an older one left behind", Wahl.Schon, Auswahl.signal(listOf(gestern), Gesendet.von(heute), jetzt))
        assertEquals("a newer one", Wahl.Senden(heute), Auswahl.signal(listOf(gestern, heute), Gesendet.von(gestern), jetzt))
        assertEquals(Wahl.Keine, Auswahl.signal(emptyList(), Gesendet.von(heute), jetzt))
    }

    @Test
    fun whatIsSentIsRememberedByNameSizeAndTime() {
        val g = Gesendet("signal-2026-11-01-03-00-00.backup", 3_100_000_000, 1_800_000_000_123)
        assertEquals(g, Gesendet.aus(g.zeile()))
        assertNull(Gesendet.aus(null))
        assertNull(Gesendet.aus("kaputt"))
        val mitStrich = Gesendet("a|b.backup", 1, 2)
        assertEquals(mitStrich, Gesendet.aus(mitStrich.zeile()))
    }

    @Test
    fun mediaSkipStatusesNomediaAndEveryDotName() {
        val alle = listOf(
            d("Media/WhatsApp Images/IMG-20261101-WA0001.jpg"),
            d("Media/WhatsApp Images/Sent/IMG-20261101-WA0002.jpg"),
            d("Media/.Statuses/abc.jpg"),
            d("Media/WhatsApp Images/.nomedia", groesse = 0),
            d("Media/WhatsApp Video/.hidden.mp4"),
            d("Media/.trash/x.jpg"),
            d("Media/WhatsApp Voice Notes/202644/PTT-20261101-WA0003.opus"),
            d("Media/WhatsApp Documents/leer.pdf", groesse = 0),
            d("Media/WhatsApp Images/IMG-jetzt.jpg", alter = 1000),
            d("Databases/msgstore.db.crypt15"),
            d("Backups/stickers.db.crypt15"),
        )
        assertEquals(
            listOf(
                "Media/WhatsApp Images/IMG-20261101-WA0001.jpg",
                "Media/WhatsApp Images/Sent/IMG-20261101-WA0002.jpg",
                "Media/WhatsApp Voice Notes/202644/PTT-20261101-WA0003.opus",
            ),
            Auswahl.medien(alle, jetzt).map { it.pfad },
        )
        assertTrue(Auswahl.versteckt("Media/.Statuses/a.jpg"))
        assertTrue(Auswahl.versteckt(".nomedia"))
        assertFalse(Auswahl.versteckt("Media/WhatsApp Images/a.jpg"))
    }

    @Test
    fun mediaMinusWhatHomesHas() {
        val medien = listOf(d("Media/a.jpg"), d("Media/b.jpg"), d("Media/c.jpg"))
        assertEquals(listOf("Media/a.jpg", "Media/c.jpg"), Auswahl.fehlende(medien, setOf("Media/c.jpg", "Media/a.jpg", "Media/x.jpg")).map { it.pfad })
        assertEquals(emptyList<Datei>(), Auswahl.fehlende(medien, emptySet()))
    }

    @Test
    fun timesAndSizesReadAsGerman() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("01.11.2026, 03:12", Anzeige.zeit(1_793_502_720_000, utc))
        assertEquals("3,4 GB", Anzeige.groesse(3_400_000_000))
        assertEquals("120 MB", Anzeige.groesse(120_000_000))
        assertEquals("12 B", Anzeige.groesse(12))
    }

    @Test
    fun theFolderReadsAsInTheFilesApp() {
        assertEquals(
            "Interner Speicher › Android › media › com.whatsapp › WhatsApp",
            Anzeige.ordner("primary:Android/media/com.whatsapp/WhatsApp"),
        )
        assertEquals("Interner Speicher", Anzeige.ordner("primary:"))
        assertEquals("Speicherkarte › Signal › Backups", Anzeige.ordner("1A2B-3C4D:Signal/Backups"))
        assertEquals("/storage/emulated/0/Download/x", Anzeige.ordner("raw:/storage/emulated/0/Download/x"))
        assertEquals("msf:1234", Anzeige.ordner("msf:1234"))
        assertEquals("ohne-doppelpunkt", Anzeige.ordner("ohne-doppelpunkt"))
    }

    @Test
    fun whatTheScreenShowsAsFound() {
        val alt = d("signal-2026-10-30-02-00-00.backup", alter = 48 * stunde)
        val neu = d("signal-2026-11-01-02-00-00.backup", alter = 2 * stunde)
        assertEquals(neu, Auswahl.neuesteSignal(listOf(alt, neu, d("notizen.txt"), d("Unter/signal-2026-11-02.backup"))))
        assertNull(Auswahl.neuesteSignal(listOf(d("notizen.txt"), d("signal-leer.backup", groesse = 0))))
        val db = d("Databases/msgstore.db.crypt15")
        assertEquals(db, Auswahl.msgstoreDatei(listOf(d("Databases/msgstore-2026-10-31.1.db.crypt15"), db)))
        assertNull(Auswahl.msgstoreDatei(listOf(d("Databases/msgstore-2026-10-31.1.db.crypt15"))))
    }
}
