package cloud.dopp.homes

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.ausloeser.AusloeserActivity
import cloud.dopp.homes.ausloeser.NfcSchreiber
import cloud.dopp.homes.ausloeser.TagInhalt
import cloud.dopp.homes.ausloeser.TagSchreiber
import cloud.dopp.homes.ausloeser.WlanTag
import cloud.dopp.homes.ausloeser.WlanUebernahme
import cloud.dopp.homes.data.TokenStore
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A written tag held to the Auslöser screen says what is on it before
 * anything is offered (owner 2026-11-27: „Bau alles“): a Homes tag, a WLAN
 * tag, anything else; and nothing that holds something is written over
 * without „Der Inhalt geht verloren.“
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TagInhaltTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private fun msg(vararg r: NdefRecord) = NdefMessage(r)

    // --- what is on it ----------------------------------------------------------------

    @Test
    fun aWlanRecordReadsBackAsWritten() {
        val n = WlanTag.lesen(WlanTag.nutzlast("Zuhause", "geheim123", WlanTag.Schutz.WPA2))!!
        assertEquals("Zuhause", n.ssid)
        assertEquals("geheim123", n.passwort)
        assertEquals(WlanTag.Schutz.WPA2, n.schutz)
        assertEquals("WPA2", n.art)
        assertFalse("its text never carries the password", n.toString().contains("geheim123"))
        val offen = WlanTag.lesen(WlanTag.nutzlast("Gast", "", WlanTag.Schutz.OFFEN))!!
        assertEquals(WlanTag.Schutz.OFFEN, offen.schutz)
        assertEquals("", offen.passwort)
        assertEquals("offen, ohne Passwort", offen.art)
        assertNull("cut off: no network", WlanTag.lesen(WlanTag.nutzlast("Zuhause", "geheim123", WlanTag.Schutz.WPA2).copyOf(9)))
        assertNull(WlanTag.lesen(ByteArray(0)))
    }

    @Test
    fun aHomesTagInEveryForm() {
        assertEquals(TagInhalt.Homes("homes-0123456789ab"), TagInhalt.lesen(msg(NdefRecord.createUri("https://homes.example/tag/homes-0123456789ab")), "homes.example"))
        assertEquals(TagInhalt.Homes("homes-0123456789ab"), TagInhalt.lesen(msg(NdefRecord.createUri("cloud.dopp.homes://tag/homes-0123456789ab")), null))
        val ha = msg(NdefRecord.createUri("https://www.home-assistant.io/tag/abc"), NdefRecord.createApplicationRecord("io.homeassistant.companion.android"))
        assertEquals(TagInhalt.Homes("abc"), TagInhalt.lesen(ha, null))
        assertTrue("another box's address is not ours", TagInhalt.lesen(msg(NdefRecord.createUri("https://evil.example/tag/abc")), "homes.example") is TagInhalt.Fremd)
    }

    @Test
    fun aWlanTag() {
        val i = TagInhalt.lesen(WlanTag.nachricht("Zuhause", "geheim123", WlanTag.Schutz.WPA2), null)
        assertEquals("Zuhause", (i as TagInhalt.Wlan).netz.ssid)
    }

    @Test
    fun anythingElseInPlainWords() {
        fun eine(r: NdefRecord) = (TagInhalt.lesen(msg(r), null) as TagInhalt.Fremd).zeilen.single()
        assertEquals("Eine Internetadresse: https://example.org/x", eine(NdefRecord.createUri("https://example.org/x")))
        assertEquals("Eine Telefonnummer: +4930123", eine(NdefRecord.createUri("tel:+4930123")))
        assertEquals("Ein Text: „Hallo Welt“", eine(NdefRecord.createTextRecord("de", "Hallo Welt")))
        assertEquals(
            "Eine Visitenkarte: Anna Muster",
            eine(NdefRecord.createMime("text/vcard", "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Anna Muster\r\nEND:VCARD".toByteArray())),
        )
        assertEquals("Daten der Art application/octet-stream (3 Bytes)", eine(NdefRecord.createMime("application/octet-stream", byteArrayOf(1, 2, 3))))
        assertEquals("Öffnet die App com.example.app", eine(NdefRecord.createApplicationRecord("com.example.app")))
        assertEquals("Daten einer App (example.com:ding, 2 Bytes)", eine(NdefRecord.createExternal("example.com", "ding", byteArrayOf(1, 2))))
        val zwei = TagInhalt.lesen(msg(NdefRecord.createUri("https://example.org"), NdefRecord.createTextRecord("en", "Hi")), null) as TagInhalt.Fremd
        assertEquals(listOf("Eine Internetadresse: https://example.org", "Ein Text: „Hi“"), zwei.zeilen)
        val lang = eine(NdefRecord.createTextRecord("de", "x".repeat(500)))
        assertTrue("cut: ${lang.length}", lang.length < 140 && lang.contains("…"))
    }

    @Test
    fun onlyABlankTagOrTheOneSaidYesForIsWritten() {
        val id = byteArrayOf(4, 1, 2, 3)
        val voll = TagSchreiber.Ergebnis.Gelesen(msg(NdefRecord.createUri("https://example.org")), id)
        assertTrue(TagSchreiber.darf(TagSchreiber.Ergebnis.Leer, id, null))
        assertFalse("holds something, nobody said yes", TagSchreiber.darf(voll, id, null))
        assertTrue(TagSchreiber.darf(voll, id, byteArrayOf(4, 1, 2, 3)))
        assertFalse("another tag", TagSchreiber.darf(voll, id, byteArrayOf(9)))
        assertFalse("a tag without an id is never the one", TagSchreiber.darf(voll, null, id))
    }

    @Test
    fun androidBelowElevenTakesNoWlanFromAnApp() {
        val n = WlanTag.lesen(WlanTag.nutzlast("Zuhause", "geheim123", WlanTag.Schutz.WPA2))!!
        assertNotNull(WlanUebernahme.warumNicht(n, Build.VERSION_CODES.Q))
        assertNull(WlanUebernahme.warumNicht(n, Build.VERSION_CODES.R))
    }

    // --- the screen -------------------------------------------------------------------

    private inner class Homes : Dispatcher() {
        val anfragen = CopyOnWriteArrayList<Pair<String, String>>()
        override fun dispatch(request: RecordedRequest): MockResponse {
            anfragen += "${request.method} ${request.path}" to request.body.readUtf8()
            return when (request.path) {
                "/napi/ausloeser" -> MockResponse().setBody("""{"ok":true,"name":"Bürotür"}""")
                "/napi/ausloeser/tag" -> MockResponse().setBody("""{"ok":true,"tag":"homes-0123456789ab","uri":"cloud.dopp.homes://tag/homes-0123456789ab"}""")
                "/napi/ausloeser/tags" -> MockResponse().setBody(
                    """{"ok":true,"tags":[{"tag":"homes-0123456789ab","name":"Bürotür","automationen":[{"id":"a1","name":"Bürolicht an"}]}],"vorschlaege":[]}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private val homes = Homes()
    private val server = MockWebServer().also { it.dispatcher = homes }

    private class Schreiber : TagSchreiber {
        var ergebnis: ((TagSchreiber.Ergebnis) -> Unit)? = null
        var uriVon: (() -> String?)? = null
        var erlaubt: ByteArray? = null
        override fun bereit(ctx: Context) = TagSchreiber.Bereit.JA
        override fun an(activity: Activity, uri: () -> String?, ergebnis: (TagSchreiber.Ergebnis) -> Unit) {
            uriVon = uri
            this.ergebnis = ergebnis
        }
        override fun aus(activity: Activity) {
            ergebnis = null
        }
        override fun erlauben(id: ByteArray?) {
            erlaubt = id
        }
    }

    private val schreiber = Schreiber()
    private val id = byteArrayOf(4, 0x22, 0x33, 0x44)

    @Before
    fun gekoppelt() {
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

    private fun warte(bis: () -> Boolean) {
        val ende = System.currentTimeMillis() + 10_000
        while (!bis() && System.currentTimeMillis() < ende) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The screen with HA's list read. */
    private fun bildschirm(): AusloeserActivity {
        val a = Robolectric.buildActivity(AusloeserActivity::class.java).setup().get()
        val liste = a.findViewById<LinearLayout>(R.id.ausloeser_liste)
        warte { (liste.getChildAt(0) as? TextView)?.text?.startsWith("Bürotür") == true }
        return a
    }

    private fun halte(a: AusloeserActivity, n: NdefMessage) {
        schreiber.ergebnis!!(TagSchreiber.Ergebnis.Gelesen(n, id))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.ausloeser_inhalt).visibility)
    }

    private fun AusloeserActivity.titel() = findViewById<TextView>(R.id.inhalt_titel).text.toString()
    private fun AusloeserActivity.zeilen() = findViewById<TextView>(R.id.inhalt_text).text.toString()
    private fun AusloeserActivity.eins() = findViewById<Button>(R.id.inhalt_eins)
    private fun AusloeserActivity.zwei() = findViewById<Button>(R.id.inhalt_zwei)
    private fun AusloeserActivity.karte() = findViewById<TextView>(R.id.inhalt_stand).text.toString()

    /** The question's message, and its „Überschreiben“ row. */
    private fun frage(): Pair<String, View> {
        val d = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(d.isShowing)
        val stapel = d.findViewById<LinearLayout>(R.id.ad_items)!!
        val ja = (0 until stapel.childCount).map { stapel.getChildAt(it) }
            .first { it.findViewById<TextView>(R.id.ar_label)?.text?.toString() == "Überschreiben" }
        return d.findViewById<TextView>(R.id.ad_message)!!.text.toString() to ja
    }

    @Test
    fun aHomesTagSaysItsNameAndAutomationsAndTriggers() {
        val a = bildschirm()
        halte(a, msg(NdefRecord.createUri("cloud.dopp.homes://tag/homes-0123456789ab")))
        assertEquals("Homes-Tag „Bürotür“", a.titel())
        assertTrue(a.zeilen(), a.zeilen().contains("Bürolicht an"))
        assertEquals("Bearbeiten", a.eins().text.toString())
        assertEquals("Auslösen", a.zwei().text.toString())
        a.zwei().performClick()
        assertEquals("answers on itself at once", "Löst aus …", a.zwei().text.toString())
        assertFalse(a.zwei().isEnabled)
        warte { a.karte().isNotEmpty() }
        assertEquals("Bürotür – gemeldet", a.karte())
        assertEquals("Auslösen", a.zwei().text.toString())
        val (was, body) = homes.anfragen.first { it.first == "POST /napi/ausloeser" }
        assertEquals("POST /napi/ausloeser", was)
        assertEquals("homes-0123456789ab", JSONObject(body).getString("tag"))
    }

    @Test
    fun editOpensItInTheList() {
        val a = bildschirm()
        halte(a, msg(NdefRecord.createUri("cloud.dopp.homes://tag/homes-0123456789ab")))
        a.eins().performClick()
        assertEquals(View.GONE, a.findViewById<View>(R.id.ausloeser_inhalt).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.ausloeser_formular).visibility)
        assertEquals("Bürotür", a.findViewById<EditText>(R.id.ausloeser_name).text.toString())
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.ausloeser_entfernen).visibility)
    }

    @Test
    fun aHomesTagHaDoesNotKnow() {
        val a = bildschirm()
        halte(a, msg(NdefRecord.createUri("cloud.dopp.homes://tag/homes-ffffffffffff")))
        assertEquals("Ein Homes-Tag, den Home Assistant nicht kennt", a.titel())
    }

    @Test
    fun aWlanTagHidesItsPasswordUntilAskedAndAsksBeforeWritingAnew() {
        val a = bildschirm()
        halte(a, WlanTag.nachricht("Zuhause", "geheim123", WlanTag.Schutz.WPA2))
        assertEquals("WLAN-Tag", a.titel())
        assertEquals("Netz: Zuhause\nSchutz: WPA2", a.zeilen())
        val pw = a.findViewById<TextView>(R.id.inhalt_passwort)
        assertFalse(pw.text.contains("geheim123"))
        a.findViewById<Button>(R.id.inhalt_zeigen).performClick()
        assertEquals("Passwort: geheim123", pw.text.toString())
        assertEquals("Neu beschreiben", a.eins().text.toString())
        assertEquals("Ins Telefon übernehmen", a.zwei().text.toString())
        a.eins().performClick()
        val (frage, ja) = frage()
        assertEquals("Der Inhalt geht verloren.", frage)
        assertNull("nothing allowed before the yes", schreiber.erlaubt)
        ja.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(id.contentEquals(schreiber.erlaubt))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.wlan_formular).visibility)
        assertEquals("Zuhause", a.findViewById<EditText>(R.id.wlan_ssid).text.toString())
        assertEquals("closed: the password forgotten", "", pw.text.toString())
    }

    @Test
    fun takingTheWlanIntoThePhoneIsAndroidsQuestion() {
        val a = bildschirm()
        halte(a, WlanTag.nachricht("Zuhause", "geheim123", WlanTag.Schutz.WPA2))
        a.zwei().performClick()
        assertEquals("Android fragt …", a.zwei().text.toString())
        val gestartet = shadowOf(a).nextStartedActivityForResult
        assertEquals(Settings.ACTION_WIFI_ADD_NETWORKS, gestartet.intent.action)
        shadowOf(a).receiveResult(gestartet.intent, Activity.RESULT_OK, Intent())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("„Zuhause“ ist im Telefon gespeichert.", a.karte())
        a.zwei().performClick()
        val wieder = shadowOf(a).nextStartedActivityForResult
        shadowOf(a).receiveResult(wieder.intent, Activity.RESULT_CANCELED, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(a.karte(), a.karte().startsWith("Nicht übernommen"))
    }

    @Test
    fun anythingElseIsSaidAndOverwrittenOnlyAfterTheQuestion() {
        val a = bildschirm()
        halte(a, msg(NdefRecord.createTextRecord("de", "Hallo")))
        assertEquals("Auf diesem Tag steht etwas anderes", a.titel())
        assertEquals("Ein Text: „Hallo“", a.zeilen())
        assertEquals("Überschreiben", a.eins().text.toString())
        assertEquals(View.GONE, a.zwei().visibility)
        a.eins().performClick()
        (ShadowDialog.getLatestDialog() as AlertDialog).cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("no: nothing allowed", schreiber.erlaubt)
        a.eins().performClick()
        frage().second.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(id.contentEquals(schreiber.erlaubt))
        assertEquals("Neuer Inhalt für diesen Tag", a.findViewById<TextView>(R.id.ausloeser_name_titel).text.toString())
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.ausloeser_formular).visibility)
    }

    @Test
    fun whileWaitingAWrittenTagIsShownNotWritten() {
        val a = bildschirm()
        a.findViewById<TextView>(R.id.ausloeser_neu_zeile).performClick()
        a.findViewById<EditText>(R.id.ausloeser_name).setText("Flur")
        a.findViewById<Button>(R.id.ausloeser_schreiben).performClick()
        warte { schreiber.uriVon?.invoke() != null }
        halte(a, msg(NdefRecord.createUri("https://example.org")))
        assertEquals("still waiting", "Abbrechen", a.findViewById<Button>(R.id.ausloeser_schreiben).text.toString())
        a.eins().performClick()
        frage().second.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "Gut. Halte den Tag noch einmal ans Telefon, dann wird er überschrieben.",
            a.findViewById<TextView>(R.id.ausloeser_stand).text.toString(),
        )
        assertTrue(id.contentEquals(schreiber.erlaubt))
    }

    @Test
    fun aBlankTagStaysAsBefore() {
        val a = bildschirm()
        schreiber.ergebnis!!(TagSchreiber.Ergebnis.Leer)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, a.findViewById<View>(R.id.ausloeser_inhalt).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.ausloeser_formular).visibility)
    }
}
