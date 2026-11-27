package cloud.dopp.homes

import android.app.Activity
import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.ausloeser.AusloeserActivity
import cloud.dopp.homes.ausloeser.NfcSchreiber
import cloud.dopp.homes.ausloeser.TagSchreiber
import cloud.dopp.homes.ausloeser.WlanTag
import cloud.dopp.homes.data.TokenStore
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A WLAN tag (the owner, 2026-11-27: „Ja WLAN schreiben“): the Wi-Fi Simple
 * Configuration credential byte by byte, the form's states — idle, running,
 * done, failed, timed out — and that the password goes nowhere: no request,
 * no preference, no log line, not the screen's saved state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WlanTagTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private companion object {
        const val PASSWORT = "Gast-Netz-2026"

        fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
        fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
    }

    // --- the bytes ---------------------------------------------------------------------------

    /** The vector built from the WSC spec's attributes by hand: type (2), length (2), value. */
    @Test
    fun aWpa2CredentialByteForByte() {
        val innen = bytes(0x10, 0x26, 0x00, 0x01, 0x01) + // Network Index 1
            bytes(0x10, 0x45, 0x00, 0x04) + ascii("Gast") + // SSID
            bytes(0x10, 0x03, 0x00, 0x02, 0x00, 0x20) + // Authentication Type WPA2-Personal
            bytes(0x10, 0x0F, 0x00, 0x02, 0x00, 0x08) + // Encryption Type AES
            bytes(0x10, 0x27, 0x00, 0x09) + ascii("geheim123") + // Network Key
            bytes(0x10, 0x20, 0x00, 0x06, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF) // MAC Address, any
        assertEquals(48, innen.size)
        val erwartet = bytes(0x10, 0x0E, 0x00, 0x30) + innen // Credential
        assertArrayEquals(erwartet, WlanTag.nutzlast("Gast", "geheim123", WlanTag.Schutz.WPA2))
    }

    @Test
    fun anOpenCredentialHasNoKey() {
        val erwartet = bytes(0x10, 0x0E, 0x00, 0x23) +
            bytes(0x10, 0x26, 0x00, 0x01, 0x01) +
            bytes(0x10, 0x45, 0x00, 0x04) + ascii("Gast") +
            bytes(0x10, 0x03, 0x00, 0x02, 0x00, 0x01) + // Open
            bytes(0x10, 0x0F, 0x00, 0x02, 0x00, 0x01) + // None
            bytes(0x10, 0x20, 0x00, 0x06, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)
        assertArrayEquals(erwartet, WlanTag.nutzlast("Gast", "ignored", WlanTag.Schutz.OFFEN))
    }

    @Test
    fun theTagsOneRecordIsWscAndFitsAnNtag213() {
        val n = WlanTag.nachricht("FRITZ!Box Gastzugang", PASSWORT, WlanTag.Schutz.WPA2)
        val r = n.records.single()
        assertEquals(NdefRecord.TNF_MIME_MEDIA, r.tnf)
        assertEquals("application/vnd.wfa.wsc", String(r.type, Charsets.US_ASCII))
        assertArrayEquals(WlanTag.nutzlast("FRITZ!Box Gastzugang", PASSWORT, WlanTag.Schutz.WPA2), r.payload)
        assertTrue("a guest network fits 144 bytes: ${n.byteArrayLength}", n.byteArrayLength <= 144)
        val gross = WlanTag.nachricht("x".repeat(32), "y".repeat(63), WlanTag.Schutz.WPA2)
        assertTrue("the longest does not: ${gross.byteArrayLength}", gross.byteArrayLength > 144)
    }

    @Test
    fun whatTheFormRefuses() {
        assertTrue(WlanTag.pruefen("", PASSWORT, WlanTag.Schutz.WPA2)!!.contains("Namen des WLANs"))
        assertTrue(WlanTag.pruefen("x".repeat(33), PASSWORT, WlanTag.Schutz.WPA2)!!.contains("zu lang"))
        assertTrue(WlanTag.pruefen("Gast", "kurz", WlanTag.Schutz.WPA2)!!.contains("8 bis 63"))
        assertTrue(WlanTag.pruefen("Gast", "Grüße-und-mehr", WlanTag.Schutz.WPA2)!!.contains("ohne Umlaute"))
        assertNull(WlanTag.pruefen("Gast", PASSWORT, WlanTag.Schutz.WPA2))
        assertNull("64 hex digits are a key", WlanTag.pruefen("Gast", "ab".repeat(32), WlanTag.Schutz.WPA2))
        assertNull("open: no password", WlanTag.pruefen("Gast", "", WlanTag.Schutz.OFFEN))
    }

    /** Touched later the tag is Android's (it offers to join): it is no blank tag for Homes, and no tag of Homes'. */
    @Test
    fun aWlanTagIsNotHomes() {
        val n = WlanTag.nachricht("Gast", PASSWORT, WlanTag.Schutz.WPA2)
        assertFalse(TagSchreiber.leer(formatierbar = false, ndef = true, nachricht = n))
        val manifest = File(RepoFiles.main, "AndroidManifest.xml").readText()
        assertFalse("no filter for the WSC type", manifest.contains("vnd.wfa.wsc"))
    }

    // --- the form ------------------------------------------------------------------------------

    private inner class Homes : Dispatcher() {
        val anfragen = CopyOnWriteArrayList<String>()
        override fun dispatch(request: RecordedRequest): MockResponse {
            anfragen += "${request.method} ${request.path} ${request.body.readUtf8()}"
            return if (request.path == "/napi/ausloeser/tags") {
                MockResponse().setBody("""{"ok":true,"tags":[],"vorschlaege":[],"aktionen":[]}""")
            } else {
                MockResponse().setResponseCode(404)
            }
        }
    }

    private val homes = Homes()
    private val server = MockWebServer().also { it.dispatcher = homes }

    private class Schreiber : TagSchreiber {
        var uriVon: (() -> String?)? = null
        var nachricht: NdefMessage? = null
        var zuKlein: ((Int, Int) -> String)? = null
        var ergebnis: ((TagSchreiber.Ergebnis) -> Unit)? = null
        override fun bereit(ctx: Context) = TagSchreiber.Bereit.JA
        override fun an(activity: Activity, uri: () -> String?, ergebnis: (TagSchreiber.Ergebnis) -> Unit) {
            uriVon = uri
            nachricht = null
            this.ergebnis = null
        }
        override fun aus(activity: Activity) {
            uriVon = null
            nachricht = null
            ergebnis = null
        }
        override fun anNachricht(activity: Activity, nachricht: NdefMessage, zuKlein: (Int, Int) -> String, ergebnis: (TagSchreiber.Ergebnis) -> Unit) {
            uriVon = null
            this.nachricht = nachricht
            this.zuKlein = zuKlein
            this.ergebnis = ergebnis
        }
    }

    private val schreiber = Schreiber()

    @Before
    fun gekoppelt() {
        ShadowLog.clear()
        ctx().getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit()
            .putString("base_url", server.url("/").toString().trimEnd('/')).commit()
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_TESTTOKEN123")
        AusloeserActivity.schreiber = schreiber
    }

    @After
    fun zu() {
        AusloeserActivity.schreiber = NfcSchreiber
        ctx().getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit().clear().commit()
        TokenStore.clear(ctx())
        runCatching { server.shutdown() }
    }

    private fun bildschirm(): AusloeserActivity {
        val a = Robolectric.buildActivity(AusloeserActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return a
    }

    private fun AusloeserActivity.formular() = findViewById<View>(R.id.wlan_formular)
    private fun AusloeserActivity.knopf() = findViewById<Button>(R.id.wlan_schreiben)
    private fun AusloeserActivity.wlanStand() = findViewById<TextView>(R.id.wlan_stand).text.toString()
    private fun AusloeserActivity.stand() = findViewById<TextView>(R.id.ausloeser_stand).text.toString()

    private fun AusloeserActivity.ausfuellen(ssid: String = "Gast", passwort: String = PASSWORT) {
        findViewById<TextView>(R.id.ausloeser_wlan_zeile).performClick()
        findViewById<EditText>(R.id.wlan_ssid).setText(ssid)
        findViewById<EditText>(R.id.wlan_passwort).setText(passwort)
    }

    @Test
    fun theFormIsClosedUntilAskedForAndSaysItsWarnings() {
        val a = bildschirm()
        assertEquals("idle: closed", View.GONE, a.formular().visibility)
        assertEquals("+ WLAN-Tag beschreiben", a.findViewById<TextView>(R.id.ausloeser_wlan_zeile).text.toString())
        a.findViewById<TextView>(R.id.ausloeser_neu_zeile).performClick()
        a.findViewById<TextView>(R.id.ausloeser_wlan_zeile).performClick()
        assertEquals(View.VISIBLE, a.formular().visibility)
        assertEquals("one form at a time", View.GONE, a.findViewById<View>(R.id.ausloeser_formular).visibility)
        val texte = (0 until (a.formular() as android.view.ViewGroup).childCount)
            .mapNotNull { ((a.formular() as android.view.ViewGroup).getChildAt(it) as? TextView)?.text?.toString() }
        assertTrue(texte.toString(), "Das Passwort steht lesbar auf dem Tag. Nimm das Gastnetz der FRITZ!Box, nicht euer Hauptnetz." in texte)
        assertTrue(texte.toString(), "iPhones können WLAN nicht von NFC lesen – dort hilft der QR-Code der FRITZ!Box." in texte)
        val passwort = a.findViewById<EditText>(R.id.wlan_passwort)
        assertFalse("not in the screen's saved state", passwort.isSaveEnabled)
        a.findViewById<CheckBox>(R.id.wlan_zeigen).isChecked = true
        assertFalse("shown on asking", passwort.transformationMethod is android.text.method.PasswordTransformationMethod)
        a.findViewById<android.widget.RadioButton>(R.id.wlan_offen).performClick()
        assertEquals("open: no password", View.GONE, passwort.visibility)
    }

    @Test
    fun aWlanTagRunsAndEndsDone() {
        val a = bildschirm()
        a.ausfuellen()
        a.knopf().performClick()
        assertEquals("running at once", "Halte den Tag ans Telefon … („Gast“)", a.wlanStand())
        assertEquals("Abbrechen", a.knopf().text.toString())
        assertArrayEquals(WlanTag.nutzlast("Gast", PASSWORT, WlanTag.Schutz.WPA2), schreiber.nachricht!!.records.single().payload)
        assertNull("the screen's own writing is off meanwhile", schreiber.uriVon)
        schreiber.ergebnis!!(TagSchreiber.Ergebnis.Fertig)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, a.formular().visibility)
        assertEquals("WLAN-Tag „Gast“ ist fertig. Ein Android-Telefon fragt beim Berühren, ob es sich verbinden soll.", a.stand())
        assertEquals("the password forgotten", "", a.findViewById<EditText>(R.id.wlan_passwort).text.toString())
        assertTrue("the screen's NFC is back", schreiber.uriVon != null)
    }

    @Test
    fun aTagTooSmallIsSaidWithItsSizes() {
        val a = bildschirm()
        a.ausfuellen()
        a.knopf().performClick()
        val satz = schreiber.zuKlein!!(160, 137)
        assertTrue(satz, satz.contains("Bytes nötig: 160") && satz.contains("auf dem Tag: 137") && satz.contains("NTAG215"))
        schreiber.ergebnis!!(TagSchreiber.Ergebnis.Fehler(satz))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("failed: said in the form", satz, a.wlanStand())
        assertEquals(View.VISIBLE, a.formular().visibility)
        assertEquals("Tag beschreiben", a.knopf().text.toString())
    }

    @Test
    fun noTagWithinAMinuteIsSaid() {
        val a = bildschirm()
        a.ausfuellen()
        a.knopf().performClick()
        shadowOf(Looper.getMainLooper()).idleFor(61, TimeUnit.SECONDS)
        assertEquals("Kein Tag gekommen. Tippe noch einmal auf „Tag beschreiben“ und halte ihn ans Telefon.", a.wlanStand())
        assertTrue("the screen's NFC is back", schreiber.uriVon != null)
    }

    @Test
    fun aWrongPasswordIsSaidAndNothingWaits() {
        val a = bildschirm()
        a.ausfuellen(passwort = "kurz")
        a.knopf().performClick()
        assertTrue(a.wlanStand(), a.wlanStand().contains("8 bis 63"))
        assertNull("nothing to write", schreiber.nachricht)
    }

    @Test
    fun abbrechenWhileWaitingWritesNothing() {
        val a = bildschirm()
        a.ausfuellen()
        a.knopf().performClick()
        a.findViewById<Button>(R.id.wlan_abbrechen).performClick()
        assertEquals(View.GONE, a.formular().visibility)
        assertEquals("Abgebrochen. Es wurde nichts geschrieben.", a.stand())
        assertNull(schreiber.nachricht)
    }

    /** The password goes nowhere: no request, no preference file, no log line. */
    @Test
    fun thePasswordStaysOnThePhoneAndTheTag() {
        val a = bildschirm()
        a.ausfuellen()
        a.knopf().performClick()
        schreiber.ergebnis!!(TagSchreiber.Ergebnis.Fertig)
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(200)
        assertTrue("no request carries it: ${homes.anfragen}", homes.anfragen.none { it.contains(PASSWORT) })
        assertTrue("only the list was asked for", homes.anfragen.all { it.startsWith("GET /napi/ausloeser/tags") })
        val prefs = File(ctx().applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
        prefs.forEach { f -> assertFalse("${f.name} holds it", f.readText().contains(PASSWORT)) }
        ctx().getSharedPreferences("ausloeser", Context.MODE_PRIVATE).all.values.forEach { assertFalse(it.toString().contains(PASSWORT)) }
        assertTrue("no log line holds it", ShadowLog.getLogs().none { it.msg?.contains(PASSWORT) == true })
    }
}
