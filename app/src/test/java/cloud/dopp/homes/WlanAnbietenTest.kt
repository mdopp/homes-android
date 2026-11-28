package cloud.dopp.homes

import android.os.Looper
import android.widget.Button
import android.widget.TextView
import cloud.dopp.homes.ausloeser.Typ4Tag
import cloud.dopp.homes.ausloeser.WlanAnbietenActivity
import cloud.dopp.homes.ausloeser.WlanAnbietenDienst
import cloud.dopp.homes.ausloeser.WlanAngebot
import cloud.dopp.homes.ausloeser.WlanTag
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.Duration

/**
 * „WLAN anbieten“ (owner 2026-11-27: „Bau alles“): the phone answers a reader
 * as a read-only NFC Forum Type 4 Tag with the WLAN tag's own record — only
 * while the screen offers, at most two minutes, the credential in memory only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WlanAnbietenTest {

    private val nachricht = WlanTag.nachricht("Zuhause", "geheim123", WlanTag.Schutz.WPA2)
    private val ndef = nachricht.toByteArray()

    @After
    fun zu() {
        WlanAngebot.vergessen()
        WlanAnbietenActivity.warum = WARUM
    }

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun tag() = Typ4Tag { WlanAngebot.nachricht(T0 + 1_000) }

    private val ok = hex("9000")
    private val nichtDa = hex("6A82")

    @Test
    fun aReaderReadsTheWlanTagsRecordByteForByte() {
        WlanAngebot.vorbereiten("Zuhause", nachricht)
        WlanAngebot.anfangen(T0)
        val t = tag()
        assertArrayEquals("SELECT the NDEF application", ok, t.antwort(hex("00 A4 04 00 07 D2760000850101 00")))
        assertArrayEquals("SELECT the CC", ok, t.antwort(hex("00 A4 00 0C 02 E103")))
        val laenge = ndef.size + 2
        val cc = hex("000F 20 003B 0034 04 06 E104") + byteArrayOf((laenge shr 8).toByte(), laenge.toByte()) + hex("00 FF")
        assertArrayEquals("READ BINARY the CC: read only", cc + ok, t.antwort(hex("00 B0 0000 0F")))
        assertArrayEquals("SELECT the NDEF file", ok, t.antwort(hex("00 A4 00 0C 02 E104")))
        val nlen = t.antwort(hex("00 B0 0000 02"))
        assertArrayEquals(byteArrayOf((ndef.size shr 8).toByte(), ndef.size.toByte()) + ok, nlen)
        // the message in the reader's steps of MLe (0x3B)
        val gelesen = ByteArrayOutputStream()
        var ab = 2
        while (ab < ndef.size + 2) {
            val a = t.antwort(byteArrayOf(0x00, 0xB0.toByte(), (ab shr 8).toByte(), ab.toByte(), 0x3B))
            assertArrayEquals(ok, a.copyOfRange(a.size - 2, a.size))
            gelesen.write(a, 0, a.size - 2)
            ab += a.size - 2
        }
        assertArrayEquals("the WLAN tag's own record", ndef, gelesen.toByteArray())
        assertArrayEquals("never written", hex("6982"), t.antwort(hex("00 D6 0000 01 00")))
    }

    @Test
    fun nothingIsAnsweredWhileNothingIsOffered() {
        val t = tag()
        listOf("00 A4 04 00 07 D2760000850101 00", "00 A4 00 0C 02 E103", "00 B0 0000 0F").forEach {
            assertArrayEquals("not prepared: $it", nichtDa, t.antwort(hex(it)))
        }
        WlanAngebot.vorbereiten("Zuhause", nachricht)
        assertArrayEquals("prepared, the screen not in front yet", nichtDa, t.antwort(hex("00 A4 04 00 07 D2760000850101 00")))
        WlanAngebot.anfangen(T0)
        assertArrayEquals(ok, t.antwort(hex("00 A4 04 00 07 D2760000850101 00")))
        WlanAngebot.pause()
        assertArrayEquals("the screen went", nichtDa, t.antwort(hex("00 A4 00 0C 02 E103")))
        assertArrayEquals("another application", nichtDa, Typ4Tag { ndef }.antwort(hex("00 A4 04 00 07 A0000000041010 00")))
        assertArrayEquals("a file before the application", nichtDa, Typ4Tag { ndef }.antwort(hex("00 A4 00 0C 02 E104")))
    }

    @Test
    fun twoMinutesAtMostHoweverOftenTheScreenComesBack() {
        WlanAngebot.vorbereiten("Zuhause", nachricht)
        WlanAngebot.anfangen(T0)
        assertNotNull(WlanAngebot.nachricht(T0 + WlanAngebot.DAUER_MS - 1))
        WlanAngebot.pause()
        assertTrue("back within the two minutes", WlanAngebot.anfangen(T0 + 60_000))
        assertEquals("the end does not move", T0 + WlanAngebot.DAUER_MS, WlanAngebot.bis)
        assertNull(WlanAngebot.nachricht(T0 + WlanAngebot.DAUER_MS))
        assertArrayEquals(nichtDa, Typ4Tag { WlanAngebot.nachricht(T0 + WlanAngebot.DAUER_MS) }.antwort(hex("00 A4 04 00 07 D2760000850101 00")))
    }

    @Test
    fun theServiceAnswersOnlyWhileOffered() {
        val dienst = Robolectric.setupService(WlanAnbietenDienst::class.java)
        val select = hex("00 A4 04 00 07 D2760000850101 00")
        assertArrayEquals(nichtDa, dienst.processCommandApdu(select, null))
        WlanAngebot.vorbereiten("Zuhause", nachricht)
        WlanAngebot.anfangen()
        assertArrayEquals(ok, dienst.processCommandApdu(select, null))
        WlanAngebot.vergessen()
        assertArrayEquals(nichtDa, dienst.processCommandApdu(select, null))
    }

    @Test
    fun theScreenOffersThenStopsByItselfAndSaysSo() {
        WlanAnbietenActivity.warum = { null }
        WlanAngebot.vorbereiten("Zuhause", nachricht)
        val a = Robolectric.buildActivity(WlanAnbietenActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Halte das andere Telefon an meins", a.findViewById<TextView>(R.id.anbieten_halte).text.toString())
        assertEquals("Netz: Zuhause", a.findViewById<TextView>(R.id.anbieten_netz).text.toString())
        val stand = a.findViewById<TextView>(R.id.anbieten_stand)
        assertTrue(stand.text.toString(), stand.text.startsWith("Wird angeboten – noch 2:00"))
        assertNotNull("offered", WlanAngebot.nachricht())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertTrue(stand.text.toString(), stand.text.startsWith("Wird angeboten – noch 0:59"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertTrue(stand.text.toString(), stand.text.startsWith("Die zwei Minuten sind um"))
        assertNull("over: nothing answered", WlanAngebot.nachricht())
        assertNull("and forgotten", WlanAngebot.ssid)
        assertEquals("Schließen", a.findViewById<Button>(R.id.anbieten_beenden).text.toString())
    }

    @Test
    fun goneFromTheFrontNothingIsAnsweredAndClosedItIsForgotten() {
        WlanAnbietenActivity.warum = { null }
        WlanAngebot.vorbereiten("Zuhause", nachricht)
        val c = Robolectric.buildActivity(WlanAnbietenActivity::class.java).setup()
        assertNotNull(WlanAngebot.nachricht())
        c.pause()
        assertNull(WlanAngebot.nachricht())
        c.resume()
        assertNotNull(WlanAngebot.nachricht())
        c.get().findViewById<Button>(R.id.anbieten_beenden).performClick()
        assertNull(WlanAngebot.ssid)
        assertNull(WlanAngebot.nachricht())
    }

    @Test
    fun aPhoneThatCannotSaysSoAndOffersNothing() {
        WlanAnbietenActivity.warum = { "Dieses Telefon kann sich nicht als Tag ausgeben." }
        WlanAngebot.vorbereiten("Zuhause", nachricht)
        val a = Robolectric.buildActivity(WlanAnbietenActivity::class.java).setup().get()
        assertEquals("Dieses Telefon kann sich nicht als Tag ausgeben.", a.findViewById<TextView>(R.id.anbieten_stand).text.toString())
        assertNull(WlanAngebot.nachricht())
    }

    @Test
    fun theWlanFormOffersInsteadOfWriting() {
        val a = Robolectric.buildActivity(cloud.dopp.homes.ausloeser.AusloeserActivity::class.java).setup().get()
        a.findViewById<TextView>(R.id.ausloeser_wlan_zeile).performClick()
        a.findViewById<android.widget.EditText>(R.id.wlan_ssid).setText("Zuhause")
        a.findViewById<android.widget.EditText>(R.id.wlan_passwort).setText("kurz")
        a.findViewById<Button>(R.id.wlan_anbieten).performClick()
        assertNull("a wrong password offers nothing", shadowOf(a).nextStartedActivity)
        assertTrue(a.findViewById<TextView>(R.id.wlan_stand).text.contains("8 bis 63 Zeichen"))
        a.findViewById<android.widget.EditText>(R.id.wlan_passwort).setText("geheim123")
        a.findViewById<Button>(R.id.wlan_anbieten).performClick()
        assertEquals(WlanAnbietenActivity::class.java.name, shadowOf(a).nextStartedActivity?.component?.className)
        assertEquals("Zuhause", WlanAngebot.ssid)
        assertEquals("the form forgot it", "", a.findViewById<android.widget.EditText>(R.id.wlan_passwort).text.toString())
        WlanAngebot.anfangen()
        assertArrayEquals("the record the form would write", ndef, WlanAngebot.nachricht())
    }

    private companion object {
        const val T0 = 1_000_000L
        val WARUM = WlanAnbietenActivity.warum
    }
}
