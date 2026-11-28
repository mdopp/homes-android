package cloud.dopp.homes

import android.app.Dialog
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.ausloeser.WlanAnbietenActivity
import cloud.dopp.homes.ausloeser.WlanAngebot
import cloud.dopp.homes.ausloeser.WlanTag
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.teilen.Austausch
import cloud.dopp.homes.teilen.AustauschActivity
import cloud.dopp.homes.teilen.AustauschClient
import cloud.dopp.homes.teilen.Behalten
import cloud.dopp.homes.teilen.Teilen
import cloud.dopp.homes.teilen.TeilenActivity
import cloud.dopp.homes.teilen.Tresor
import cloud.dopp.homes.teilen.Visitenkarte
import cloud.dopp.homes.teilen.Vorne
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
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
import org.robolectric.shadows.ShadowDialog
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

/**
 * „Kontakt austauschen“ (owner approved 2026-11-28; mdopp/homes#398): the
 * session starts on [NFC], [QR] or a touch, its link goes out at once,
 * „Was teilen?“ releases exactly the switched fields and follows the page —
 * in each state: starting, armed, shared, opened, filled in, refused, over,
 * ended, timed out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AustauschTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()
    private val schluessel = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val vorher = Tresor.schluessel

    private inner class Homes : Dispatcher() {
        var neu: () -> MockResponse = { MockResponse().setResponseCode(201).setBody(NEU) }
        var setzen: () -> MockResponse = { MockResponse().setBody("""{"ok":true}""") }
        var stand: () -> MockResponse = { MockResponse().setBody(STAND) }
        val anfragen = CopyOnWriteArrayList<Pair<String, String>>()
        override fun dispatch(request: RecordedRequest): MockResponse {
            anfragen += "${request.method} ${request.path}" to request.body.readUtf8()
            return when ("${request.method} ${request.path}") {
                "POST /napi/teilen" -> neu()
                "PUT /napi/teilen/s1" -> setzen()
                "GET /napi/teilen/s1" -> stand()
                "DELETE /napi/teilen/s1" -> MockResponse().setBody("""{"ok":true}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private val homes = Homes()
    private val server = MockWebServer().also { it.dispatcher = homes }

    @Before
    fun an() {
        Tresor.schluessel = { schluessel }
        WlanAnbietenActivity.warum = { null }
        ctx().getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit()
            .putString("base_url", server.url("/").toString().trimEnd('/')).commit()
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_TESTTOKEN123")
        val k = Visitenkarte.Kontakt("Anna Muster", "+49 30 1234", "anna@example.org", "Hauptstr. 1", null, mobil = "+49 170 9999", geburtstag = "1990-04-01")
        Behalten.setzeVisitenkarte(ctx(), Visitenkarte.vcard(k, Visitenkarte.Feld.entries.toSet()))
    }

    @After
    fun zu() {
        Tresor.schluessel = vorher
        WlanAngebot.vergessen()
        Austausch.jetzt = null
        Austausch.http = null
        Teilen.zuletzt = 0
        listOf("tresor", "austausch", "homes_server").forEach { ctx().getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
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

    private fun was(wie: String?): AustauschActivity {
        val i = Intent(ctx(), AustauschActivity::class.java).apply { wie?.let { putExtra(AustauschActivity.WIE, it) } }
        return Robolectric.buildActivity(AustauschActivity::class.java, i).setup().get()
    }

    private fun AustauschActivity.stand() = findViewById<TextView>(R.id.austausch_stand).text.toString()
    private fun AustauschActivity.meldung() = findViewById<TextView>(R.id.austausch_meldung).text.toString()
    private fun AustauschActivity.schalter(): List<SwitchCompat> =
        findViewById<ViewGroup>(R.id.austausch_felder).let { g -> (0 until g.childCount).map { g.getChildAt(it) as SwitchCompat } }

    private fun puts() = homes.anfragen.filter { it.first == "PUT /napi/teilen/s1" }.map { JSONObject(it.second) }

    @Test
    fun theFirstRowOfTeilenOpensWasTeilen() {
        val a = Robolectric.buildActivity(TeilenActivity::class.java).setup().get()
        val liste = a.findViewById<ViewGroup>(R.id.teilen_liste)
        val erste = liste.getChildAt(0)
        assertEquals("Kontakt austauschen", erste.findViewById<TextView>(R.id.teile_titel).text.toString())
        val schalter = a.findViewById<SwitchCompat>(R.id.teilen_beruehrung)
        assertEquals(View.VISIBLE, schalter.visibility)
        assertTrue("on by default", schalter.isChecked)
        schalter.isChecked = false
        assertFalse(Austausch.beiBeruehrung(ctx()))
        erste.findViewById<Button>(R.id.teile_qr).performClick()
        val i = shadowOf(a).nextStartedActivity
        assertEquals(AustauschActivity::class.java.name, i.component?.className)
        assertEquals(AustauschActivity.QR, i.getStringExtra(AustauschActivity.WIE))
        assertTrue("nothing asked before „Was teilen?“ starts it", homes.anfragen.isEmpty())
    }

    @Test
    fun nfcStartsTheSessionAndArmsItsLinkAtOnce() {
        homes.neu = { MockResponse().setResponseCode(201).setBody(NEU).setBodyDelay(300, TimeUnit.MILLISECONDS) }
        val a = was(AustauschActivity.NFC)
        assertEquals("Homes macht den Link …", a.stand())
        warte { Austausch.jetzt != null }
        assertEquals("POST /napi/teilen", homes.anfragen.first().first)
        assertEquals("the card's first name", "Anna", JSONObject(homes.anfragen.first().second).getString("vorname"))
        assertEquals("Halte das andere Telefon an deins … noch 2:00", a.stand())
        assertEquals("https://www.box.test/t/tok", NdefMessage(WlanAngebot.nachricht()!!).records.single().toUri().toString())
        assertTrue(WlanAngebot.einmal)
        WlanAngebot.gelesen()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(a.stand(), a.stand().startsWith("Link geteilt"))
    }

    @Test
    fun qrShowsTheLinkLargeAndBright() {
        was(AustauschActivity.QR)
        warte { ShadowDialog.getLatestDialog()?.isShowing == true }
        val d = ShadowDialog.getLatestDialog() as Dialog
        assertEquals("Kontakt austauschen", d.findViewById<TextView>(R.id.qr_titel).text.toString())
        assertEquals(1f, d.window!!.attributes.screenBrightness)
        assertNull("QR: nothing armed", WlanAngebot.nachricht())
    }

    @Test
    fun theFirstNameAlwaysTheFieldsSwitchedAndRemembered() {
        val a = was(AustauschActivity.QR)
        warte { Austausch.jetzt != null }
        assertEquals("Dein Vorname steht immer da: Anna", a.findViewById<TextView>(R.id.austausch_vorname).text.toString())
        val s = a.schalter()
        assertEquals(
            listOf("Nachname: Muster", "Mobil: +49 170 9999", "Telefon: +49 30 1234", "Mail: anna@example.org", "Adresse: Hauptstr. 1", "Geburtstag: 1990-04-01"),
            s.map { it.text.toString() },
        )
        s[2].isChecked = false
        s[4].isChecked = false
        assertFalse("remembered", Austausch.feldAn(ctx(), Visitenkarte.Feld.TELEFON))
        a.findViewById<Button>(R.id.austausch_freigeben).performClick()
        assertEquals("pressed: at once", "Gibt frei …", a.findViewById<Button>(R.id.austausch_freigeben).text.toString())
        warte { a.meldung().isNotEmpty() }
        assertEquals("Freigegeben. Die Seite zeigt es jetzt.", a.meldung())
        val p = puts().single()
        assertEquals("Anna", p.getString("vorname"))
        val k = p.getJSONObject("karte")
        assertEquals(setOf("name", "mobil", "mail", "geburtstag"), k.keys().asSequence().toSet())
        assertEquals("Anna Muster", k.getString("name"))
        assertTrue("no guest Wi-Fi set: none", p.isNull("wlan"))
        assertFalse(p.getBoolean("eintragen"))
        val b = was(null)
        assertFalse("the next time as left", b.schalter()[2].isChecked)
    }

    @Test
    fun theGuestWifiAndPressingAgainUpdatesThePage() {
        Behalten.setzeGastWlan(ctx(), WlanTag.Netz("Gast", WlanTag.Schutz.WPA2.authentifizierung, "geheim123"))
        val a = was(AustauschActivity.QR)
        warte { Austausch.jetzt != null }
        val wlan = a.findViewById<SwitchCompat>(R.id.austausch_wlan)
        assertEquals("Gast-WLAN „Gast“", wlan.text.toString())
        a.schalter().forEach { it.isChecked = false }
        wlan.isChecked = true
        a.findViewById<Button>(R.id.austausch_freigeben).performClick()
        warte { puts().size == 1 && a.meldung().isNotEmpty() }
        assertTrue("no field: no card", puts()[0].isNull("karte"))
        assertEquals("""{"ssid":"Gast","kennwort":"geheim123"}""", puts()[0].getJSONObject("wlan").toString())
        wlan.isChecked = false
        a.findViewById<Button>(R.id.austausch_freigeben).performClick()
        warte { puts().size == 2 }
        assertTrue("taken back", puts()[1].isNull("wlan"))
    }

    @Test
    fun theFormIsAnAdminsHomesSaysNoOtherwise() {
        homes.setzen = { MockResponse().setResponseCode(403).setBody("""{"ok":false,"error":"Kontakte des Hauses lässt nur ein Admin prüfen."}""") }
        val a = was(AustauschActivity.QR)
        warte { Austausch.jetzt != null }
        val e = a.findViewById<SwitchCompat>(R.id.austausch_eintragen)
        e.isChecked = true
        a.findViewById<Button>(R.id.austausch_freigeben).performClick()
        warte { a.meldung().isNotEmpty() }
        assertEquals("Kontakte des Hauses lässt nur ein Admin prüfen.", a.meldung())
        assertTrue(puts().single().getBoolean("eintragen"))
        assertFalse("switched off again", e.isChecked)
    }

    @Test
    fun theStatusFollowsThePageEvery3Seconds() {
        homes.stand = { MockResponse().setBody("""{"ok":true,"geoeffnet":true,"eingetragen":false,"gueltig_bis":"2099-01-01T00:00:00Z"}""") }
        val a = was(AustauschActivity.QR)
        warte { a.stand().contains("Seite geöffnet") }
        assertEquals("Link geteilt · Seite geöffnet", a.stand())
        homes.stand = { MockResponse().setBody("""{"ok":true,"geoeffnet":true,"eingetragen":true,"gueltig_bis":"2099-01-01T00:00:00Z"}""") }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        warte { a.stand().contains("hat eingetragen") }
        assertEquals("Link geteilt · Seite geöffnet · hat eingetragen", a.stand())
    }

    @Test
    fun overOnceHomesSaysSo() {
        homes.stand = { MockResponse().setResponseCode(410).setBody("""{"ok":false,"error":"Dieses Teilen ist schon vorbei. Starte ein neues."}""") }
        val a = was(AustauschActivity.QR)
        warte { a.stand().contains("vorbei") }
        assertEquals("Dieses Teilen ist schon vorbei. Starte ein neues.", a.stand())
        assertNull(Austausch.jetzt)
        assertFalse(a.findViewById<Button>(R.id.austausch_freigeben).isEnabled)
        assertEquals("Schließen", a.findViewById<Button>(R.id.austausch_beenden).text.toString())
    }

    @Test
    fun itsTimeIsUpByItself() {
        assertTrue(AustauschActivity.abgelaufen("2026-11-28T10:00:00Z", 1_795_860_000_000))
        assertFalse(AustauschActivity.abgelaufen("2026-11-28T10:00:00Z", 1_795_849_000_000))
        assertFalse("unreadable: not", AustauschActivity.abgelaufen("bald", 0))
    }

    @Test
    fun beendenEndsItOnHomes() {
        val a = was(AustauschActivity.QR)
        warte { Austausch.jetzt != null }
        a.findViewById<Button>(R.id.austausch_beenden).performClick()
        warte { a.stand().startsWith("Beendet") }
        assertTrue(homes.anfragen.any { it.first == "DELETE /napi/teilen/s1" })
        assertNull(Austausch.jetzt)
        a.findViewById<Button>(R.id.austausch_beenden).performClick()
        assertTrue("then it closes", a.isFinishing)
    }

    @Test
    fun tooManyAndNoAnswerAreSaid() {
        homes.neu = { MockResponse().setResponseCode(429).setBody("""{"ok":false,"error":"Du teilst gerade schon fünfmal. Beende eins davon, dann geht es wieder."}""") }
        val a = was(AustauschActivity.NFC)
        warte { !a.stand().startsWith("Homes macht") }
        assertEquals("Du teilst gerade schon fünfmal. Beende eins davon, dann geht es wieder.", a.stand())
        assertNull(WlanAngebot.nachricht())
        Austausch.http = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        homes.neu = { MockResponse().setResponseCode(201).setBody(NEU).setBodyDelay(2, TimeUnit.SECONDS) }
        val b = was(AustauschActivity.NFC)
        warte { !b.stand().startsWith("Homes macht") }
        assertEquals("Homes hat nicht rechtzeitig geantwortet. Bitte gleich noch einmal tippen.", b.stand())
    }

    @Test
    fun aTouchStartsTheSessionItsLinkAnswersAndTheQuestionOpensWasTeilen() {
        Teilen.zuletzt = 0
        Teilen.anfrage(ctx(), 90_000_000)
        val nm = shadowOf(ctx().getSystemService(NotificationManager::class.java))
        warte { Austausch.jetzt != null && WlanAngebot.ssid != null && nm.allNotifications.isNotEmpty() }
        assertEquals("https://www.box.test/t/tok", NdefMessage(WlanAngebot.nachricht()!!).records.single().toUri().toString())
        val n = nm.allNotifications.single()
        assertEquals("Kontakt austauschen: Was teilen?", shadowOf(n).contentTitle.toString())
        assertEquals(AustauschActivity::class.java.name, shadowOf(n.contentIntent).savedIntent.component?.className)
        WlanAngebot.gelesen()
        val a = was(null)
        assertEquals("one session, not two", 1, homes.anfragen.count { it.first == "POST /napi/teilen" })
        assertTrue(a.stand(), a.stand().startsWith("Link geteilt"))
    }

    @Test
    fun aTouchWhileAScreenOfTheAppIsInFrontOpensWasTeilenAtOnce() {
        val vorn = Robolectric.buildActivity(TeilenActivity::class.java).setup().get()
        assertEquals("the resumed screen counts, screen on and unlocked", vorn, Vorne.aktivitaet())
        Teilen.zuletzt = 0
        Teilen.anfrage(ctx(), 92_000_000)
        warte { shadowOf(vorn).peekNextStartedActivity() != null }
        assertEquals(AustauschActivity::class.java.name, shadowOf(vorn).nextStartedActivity.component?.className)
        assertTrue("no notification needed", shadowOf(ctx().getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
        assertEquals("https://www.box.test/t/tok", NdefMessage(WlanAngebot.nachricht()!!).records.single().toUri().toString())
    }

    @Test
    fun aScreenGoneToTheBackgroundOnlyGetsTheNotification() {
        val c = Robolectric.buildActivity(TeilenActivity::class.java).setup()
        c.pause().stop()
        assertNull("paused: not in front", Vorne.aktivitaet())
        Teilen.zuletzt = 0
        Teilen.anfrage(ctx(), 93_000_000)
        val nm = shadowOf(ctx().getSystemService(NotificationManager::class.java))
        warte { nm.allNotifications.isNotEmpty() }
        assertEquals("Kontakt austauschen: Was teilen?", shadowOf(nm.allNotifications.single()).contentTitle.toString())
        assertNull("nothing started from the background", shadowOf(c.get()).peekNextStartedActivity())
    }

    @Test
    fun aLockedPhoneIsNotInFront() {
        val vorn = Robolectric.buildActivity(TeilenActivity::class.java).setup().get()
        shadowOf(ctx().getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        assertNull(Vorne.aktivitaet())
        shadowOf(ctx().getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        shadowOf(ctx().getSystemService(PowerManager::class.java)).setIsInteractive(false)
        assertNull("screen off", Vorne.aktivitaet())
        shadowOf(ctx().getSystemService(PowerManager::class.java)).setIsInteractive(true)
        assertEquals(vorn, Vorne.aktivitaet())
    }

    @Test
    fun withTheSwitchOffATouchOnlyAsks() {
        Austausch.setzeBeiBeruehrung(ctx(), false)
        Teilen.zuletzt = 0
        Teilen.anfrage(ctx(), 91_000_000)
        shadowOf(Looper.getMainLooper()).idle()
        val n = shadowOf(ctx().getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals("Ein Telefon möchte etwas von dir. Was teilen?", shadowOf(n).contentTitle.toString())
        assertTrue(homes.anfragen.isEmpty())
        assertNull(WlanAngebot.nachricht())
    }

    @Test
    fun theRepliesByStatus() {
        val gut: (JSONObject) -> AustauschClient.Antwort? = { AustauschClient.Antwort.Ok }
        assertEquals(AustauschClient.Antwort.Vorbei("weg"), AustauschClient.antwortAus(404, """{"ok":false,"error":"weg"}""", gut))
        assertEquals(AustauschClient.Antwort.Abgelehnt(400, "Ein Feld passt nicht."), AustauschClient.antwortAus(400, """{"ok":false,"error":"Ein Feld passt nicht."}""", gut))
        assertEquals(AustauschClient.Antwort.NichtGekoppelt, AustauschClient.antwortAus(401, null, gut))
        assertNull("a birthday Homes cannot read is left out", Austausch.karteJson(mapOf(Visitenkarte.Feld.GEBURTSTAG to "1.4.1990"), setOf(Visitenkarte.Feld.GEBURTSTAG)))
        assertEquals("""{"ssid":"Gast","offen":true}""", Austausch.wlanJson(WlanTag.Netz("Gast", WlanTag.Schutz.OFFEN.authentifizierung, "")).toString())
    }

    private companion object {
        const val NEU = """{"ok":true,"id":"s1","link":"https://www.box.test/t/tok","gueltig_bis":"2099-01-01T00:00:00Z"}"""
        const val STAND = """{"ok":true,"geoeffnet":false,"eingetragen":false,"gueltig_bis":"2099-01-01T00:00:00Z"}"""
    }
}
