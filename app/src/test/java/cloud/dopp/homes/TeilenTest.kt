package cloud.dopp.homes

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.NotificationManager
import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.ausloeser.AusloeserActivity
import cloud.dopp.homes.ausloeser.Karte
import cloud.dopp.homes.ausloeser.NfcSchreiber
import cloud.dopp.homes.ausloeser.TagBefund
import cloud.dopp.homes.ausloeser.TagSchreiber
import cloud.dopp.homes.ausloeser.Typ4Tag
import cloud.dopp.homes.ausloeser.WlanAnbietenActivity
import cloud.dopp.homes.ausloeser.WlanAnbietenDienst
import cloud.dopp.homes.ausloeser.WlanAngebot
import cloud.dopp.homes.ausloeser.WlanTag
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.kann.KannStore
import cloud.dopp.homes.teilen.Behalten
import cloud.dopp.homes.teilen.EinladenClient
import cloud.dopp.homes.teilen.GastWlanActivity
import cloud.dopp.homes.teilen.Teilen
import cloud.dopp.homes.teilen.TeilenActivity
import cloud.dopp.homes.teilen.Tresor
import cloud.dopp.homes.teilen.Visitenkarte
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

/**
 * „Teilen per Berührung“ (owner approved 2026-11-28: „Ja, aber auch Karten
 * die ich ausgelesen habe“): nothing is answered without a choice, a choice
 * answers one reader within two minutes, the question comes at most every
 * 30 s, the card is a vCard 3.0, and what is kept is encrypted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TeilenTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()
    private val hex = TagBefund::hex
    private val select = hex("00A4040007D276000085010100")
    private val ok = hex("9000")
    private val nichtDa = hex("6A82")
    private val uri = NdefMessage(arrayOf(NdefRecord.createUri("https://example.org/speisekarte")))
    private val schluessel = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val vorher = Tresor.schluessel

    @Before
    fun an() {
        Tresor.schluessel = { schluessel } // Robolectric has no Keystore: the same AES-GCM with a key in memory
        WlanAnbietenActivity.warum = { null }
    }

    @After
    fun zu() {
        Tresor.schluessel = vorher
        WlanAngebot.vergessen()
        AusloeserActivity.schreiber = NfcSchreiber
        ctx().getSharedPreferences("tresor", Context.MODE_PRIVATE).edit().clear().commit()
        TeilenActivity.http = null
        ctx().getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit().clear().commit()
        TokenStore.clear(ctx())
        KannStore.clear(ctx())
        runCatching { server.shutdown() }
    }

    // --- the answer -------------------------------------------------------------------------------

    /** A reader: SELECT, the NDEF file, its length and the message in steps of MLe. */
    private fun lies(t: Typ4Tag): ByteArray? {
        if (!t.antwort(select).contentEquals(ok)) return null
        t.antwort(hex("00A4000C02E104"))
        val nlen = t.antwort(hex("00B0000002"))
        val laenge = (nlen[0].toInt() and 0xFF shl 8) or (nlen[1].toInt() and 0xFF)
        var ab = 2
        var out = ByteArray(0)
        while (ab < laenge + 2) {
            val a = t.antwort(byteArrayOf(0, 0xB0.toByte(), (ab shr 8).toByte(), ab.toByte(), 0x3B))
            out += a.copyOf(a.size - 2)
            ab += a.size - 2
        }
        return out
    }

    private fun tag() = Typ4Tag(WlanAngebot::gelesen) { WlanAngebot.nachricht(T0 + 1_000) }

    /** A reader now, for the screen's own offer. */
    private fun tag2() = Typ4Tag(WlanAngebot::gelesen) { WlanAngebot.nachricht() }

    @Test
    fun nothingIsAnsweredWithoutAChoice() {
        assertArrayEquals(nichtDa, tag().antwort(select))
        assertNull(lies(tag()))
    }

    @Test
    fun aChoiceAnswersOneReaderThenNothing() {
        WlanAngebot.vorbereiten("Speisekarte", uri, nurEinmal = true)
        WlanAngebot.anfangen(T0)
        assertArrayEquals("the first reader gets it byte for byte", uri.toByteArray(), lies(tag()))
        assertTrue(WlanAngebot.geteilt)
        assertNull("then it is over", WlanAngebot.nachricht(T0 + 2_000))
        assertArrayEquals("the next reader gets nothing", nichtDa, tag().antwort(select))
    }

    @Test
    fun theWlanOfferStaysForMoreReaders() {
        WlanAngebot.vorbereiten("Zuhause", WlanTag.nachricht("Zuhause", "geheim123", WlanTag.Schutz.WPA2))
        WlanAngebot.anfangen(T0)
        assertNotNull(lies(tag()))
        assertFalse(WlanAngebot.geteilt)
        assertNotNull("not „einmal“: still offered", lies(tag()))
    }

    @Test
    fun aChoiceExpiresAfterTwoMinutes() {
        WlanAngebot.vorbereiten("Speisekarte", uri, nurEinmal = true)
        WlanAngebot.anfangen(T0)
        assertNull(WlanAngebot.nachricht(T0 + WlanAngebot.DAUER_MS))
    }

    // --- the question -----------------------------------------------------------------------------

    @Test
    fun theQuestionComesAtMostEvery30Seconds() {
        assertTrue(Teilen.fragt(1_000, 0))
        assertFalse(Teilen.fragt(30_999, 1_000))
        assertTrue(Teilen.fragt(31_000, 1_000))
        val nm = shadowOf(ctx().getSystemService(NotificationManager::class.java))
        Teilen.anfrage(ctx(), 50_000_000)
        assertEquals(1, nm.allNotifications.size)
        ctx().getSystemService(NotificationManager::class.java).cancelAll()
        Teilen.anfrage(ctx(), 50_010_000)
        assertEquals("10 s later: not again", 0, nm.size())
        Teilen.anfrage(ctx(), 50_030_000)
        assertEquals("30 s later: again", 1, nm.size())
        val n = nm.allNotifications.single()
        assertEquals("Ein Telefon möchte etwas von dir. Was teilen?", shadowOf(n).contentTitle.toString())
        assertEquals(TeilenActivity::class.java.name, shadowOf(n.contentIntent).savedIntent.component?.className)
    }

    @Test
    fun aReaderAskingWithNothingChosenPostsTheQuestionAndGetsNothing() {
        val dienst = Robolectric.setupService(WlanAnbietenDienst::class.java)
        assertArrayEquals(nichtDa, dienst.processCommandApdu(select, null))
        assertTrue(Typ4Tag.waehltNdef(select))
        assertFalse(Typ4Tag.waehltNdef(hex("00A4000C02E104")))
    }

    // --- what is shared -----------------------------------------------------------------------------

    private val anna = Visitenkarte.Kontakt("Anna Muster", "+49 30 1234", "anna@example.org", "Hauptstr. 1, 10115 Berlin", "Muster; GmbH")

    @Test
    fun theCardIsAVcard30OfTheTickedFields() {
        val v = Visitenkarte.vcard(anna, setOf(Visitenkarte.Feld.NAME, Visitenkarte.Feld.TELEFON, Visitenkarte.Feld.FIRMA))
        assertEquals(
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Anna Muster\r\nN:Muster;Anna;;;\r\nTEL;TYPE=CELL:+49 30 1234\r\nORG:Muster\\; GmbH\r\nEND:VCARD\r\n",
            v,
        )
        assertFalse("not ticked: not in it", v.contains("anna@example.org") || v.contains("Hauptstr"))
        val r = Visitenkarte.ndef(v).records.single()
        assertEquals(NdefRecord.TNF_MIME_MEDIA, r.tnf)
        assertEquals("text/vcard", String(r.type))
        assertArrayEquals(v.toByteArray(), r.payload)
    }

    @Test
    fun aContactFromItsRows() {
        val k = Visitenkarte.kontakt(
            listOf(
                "vnd.android.cursor.item/name" to "Anna Muster", "vnd.android.cursor.item/phone_v2" to "+49 30 1234",
                "vnd.android.cursor.item/phone_v2" to "+49 30 9999", "vnd.android.cursor.item/email_v2" to "anna@example.org",
            ),
        )
        assertEquals(Visitenkarte.Kontakt("Anna Muster", "+49 30 1234", "anna@example.org", null, null), k)
    }

    @Test
    fun aWlanForTheIphoneCamera() {
        val n = WlanTag.lesen(WlanTag.nutzlast("Gast;1", "ge:heim12", WlanTag.Schutz.WPA2))!!
        assertEquals("WIFI:T:WPA;S:Gast\\;1;P:ge\\:heim12;;", Visitenkarte.wifi(n))
        val offen = WlanTag.lesen(WlanTag.nutzlast("Gast", "", WlanTag.Schutz.OFFEN))!!
        assertEquals("WIFI:T:nopass;S:Gast;;", Visitenkarte.wifi(offen))
        assertEquals(200, Visitenkarte.qrBild(Visitenkarte.wifi(n), 200).width)
    }

    // --- what is kept -----------------------------------------------------------------------------------

    @Test
    fun theTresorKeepsItEncryptedAndOpensItAgain() {
        Tresor.schreiben(ctx(), "x", "geheim123")
        val roh = ctx().getSharedPreferences("tresor", Context.MODE_PRIVATE).getString("x", null)!!
        assertFalse("never in clear", roh.contains("geheim123"))
        assertEquals("geheim123", Tresor.lesen(ctx(), "x"))
        assertFalse("a fresh IV each time", Tresor.verschluesseln("a".toByteArray()) == Tresor.verschluesseln("a".toByteArray()))
        Tresor.schluessel = { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        assertNull("another key opens nothing", Tresor.lesen(ctx(), "x"))
    }

    @Test
    fun keptTagsKeepTheirBytesAndGo() {
        Behalten.dazu(ctx(), "Speisekarte", uri)
        val wlan = WlanTag.nachricht("Gast", "geheim123", WlanTag.Schutz.WPA2)
        Behalten.dazu(ctx(), "Gast-WLAN", wlan)
        val alle = Behalten.alle(ctx())
        assertEquals(listOf("Speisekarte", "Gast-WLAN"), alle.map { it.name })
        assertArrayEquals(wlan.toByteArray(), alle[1].nachricht.toByteArray())
        assertFalse("its text names it, not its content", alle[1].toString().contains("geheim"))
        Behalten.weg(ctx(), 0)
        assertEquals(listOf("Gast-WLAN"), Behalten.alle(ctx()).map { it.name })
    }

    // --- „Teilen“: one row per topic, [NFC] and [QR] ------------------------------------------------------------

    private inner class Homes : Dispatcher() {
        var antwort: () -> MockResponse = { MockResponse().setBody(LINK) }
        val anfragen = CopyOnWriteArrayList<String>()
        override fun dispatch(request: RecordedRequest): MockResponse {
            anfragen += "${request.method} ${request.path} ${request.body.readUtf8()}"
            return if (request.path == "/napi/kontakte/einladen") antwort() else MockResponse().setResponseCode(404)
        }
    }

    private val homes = Homes()
    private val server = MockWebServer().also { it.dispatcher = homes }

    private fun gekoppelt() {
        ctx().getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit()
            .putString("base_url", server.url("/").toString().trimEnd('/')).commit()
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_TESTTOKEN123")
    }

    private fun teilen(): TeilenActivity = Robolectric.buildActivity(TeilenActivity::class.java).setup().get()

    private fun reihen(a: Activity): List<View> = a.findViewById<ViewGroup>(R.id.teilen_liste).let { g -> (0 until g.childCount).map { g.getChildAt(it) } }

    private fun reihe(a: Activity, titel: String): View = reihen(a).single { it.findViewById<TextView>(R.id.teile_titel).text.toString() == titel }

    private fun View.text(id: Int) = findViewById<TextView>(id).let { if (it.visibility == View.VISIBLE) it.text.toString() else null }

    private fun View.knopf(id: Int) = findViewById<Button>(id)

    private fun warte(bis: () -> Boolean) {
        val ende = System.currentTimeMillis() + 10_000
        while (!bis() && System.currentTimeMillis() < ende) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun oneRowPerTopicWithNfcAndQrOrFestlegen() {
        Behalten.setzeVisitenkarte(ctx(), Visitenkarte.vcard(anna, setOf(Visitenkarte.Feld.NAME)))
        Behalten.dazu(ctx(), "Speisekarte", uri)
        Behalten.dazu(ctx(), "Fremd", NdefMessage(arrayOf(NdefRecord.createMime("application/x-fremd", byteArrayOf(1, 2)))))
        val a = teilen()
        assertEquals("Teilen", a.findViewById<TextView>(R.id.teilen_ueberschrift).text.toString())
        assertEquals(
            "not paired: no invitation row",
            listOf("Meine Kontaktdaten", "Gast-WLAN", "Speisekarte", "Fremd"),
            reihen(a).map { it.findViewById<TextView>(R.id.teile_titel).text.toString() },
        )
        val karte = reihe(a, "Meine Kontaktdaten")
        assertEquals("iPhone: QR-Code", karte.text(R.id.teile_unter))
        assertEquals(View.VISIBLE, karte.knopf(R.id.teile_nfc).visibility)
        assertTrue(karte.knopf(R.id.teile_qr).isEnabled)
        val gast = reihe(a, "Gast-WLAN")
        assertEquals("not set: only „Festlegen“", View.VISIBLE, gast.knopf(R.id.teile_festlegen).visibility)
        assertEquals(View.GONE, gast.knopf(R.id.teile_nfc).visibility)
        assertEquals(View.GONE, gast.knopf(R.id.teile_qr).visibility)
        assertTrue("a link: QR", reihe(a, "Speisekarte").knopf(R.id.teile_qr).isEnabled)
        val fremd = reihe(a, "Fremd")
        assertFalse("no sensible QR form: off", fremd.knopf(R.id.teile_qr).isEnabled)
        assertTrue(fremd.text(R.id.teile_unter)!!.contains("Nur per NFC, ohne QR-Code"))
    }

    @Test
    fun noCardYetSaysFestlegen() {
        val a = teilen()
        val karte = reihe(a, "Meine Kontaktdaten")
        assertEquals("Noch nicht festgelegt", karte.text(R.id.teile_unter))
        karte.knopf(R.id.teile_festlegen).performClick()
        assertEquals(cloud.dopp.homes.teilen.VisitenkarteActivity::class.java.name, shadowOf(a).nextStartedActivity.component?.className)
    }

    @Test
    fun noGuestWifiOpensTheFormThenItIsArmed() {
        val a = teilen()
        reihe(a, "Gast-WLAN").knopf(R.id.teile_festlegen).performClick()
        val form = shadowOf(a).nextStartedActivityForResult
        assertEquals(GastWlanActivity::class.java.name, form.intent.component?.className)
        val g = Robolectric.buildActivity(GastWlanActivity::class.java, form.intent).setup().get()
        g.findViewById<EditText>(R.id.gast_ssid).setText("Gast")
        g.findViewById<EditText>(R.id.gast_passwort).setText("kurz")
        g.findViewById<Button>(R.id.gast_sichern).performClick()
        assertTrue(g.findViewById<TextView>(R.id.gast_stand).text.contains("8 bis 63 Zeichen"))
        assertNull("not saved", Behalten.gastWlan(ctx()))
        g.findViewById<EditText>(R.id.gast_passwort).setText("geheim123")
        g.findViewById<Button>(R.id.gast_sichern).performClick()
        assertTrue("from „Teilen“: closes once saved", g.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(g).resultCode)
        assertEquals(WlanTag.Netz("Gast", WlanTag.Schutz.WPA2.authentifizierung, "geheim123"), Behalten.gastWlan(ctx()))
        shadowOf(a).receiveResult(form.intent, Activity.RESULT_OK, null)
        shadowOf(Looper.getMainLooper()).idle()
        val gast = reihe(a, "Gast-WLAN")
        assertEquals("WLAN „Gast“ · iPhone: QR-Code", gast.text(R.id.teile_unter))
        assertEquals("armed at once", "Halte das andere Telefon an deins … noch 2:00", gast.text(R.id.teile_stand))
        assertTrue(WlanAngebot.einmal)
        assertArrayEquals(WlanTag.nachricht("Gast", "geheim123", WlanTag.Schutz.WPA2).toByteArray(), WlanAngebot.nachricht())
    }

    @Test
    fun aSetGuestWifiIsArmedAtOnceThenGeteiltOrAbgelaufen() {
        Behalten.setzeGastWlan(ctx(), WlanTag.Netz("Gast", WlanTag.Schutz.OFFEN.authentifizierung, ""))
        val a = teilen()
        val gast = reihe(a, "Gast-WLAN")
        gast.knopf(R.id.teile_nfc).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Halte das andere Telefon an deins … noch 2:00", gast.text(R.id.teile_stand))
        assertArrayEquals(WlanTag.nachricht("Gast", "", WlanTag.Schutz.OFFEN).toByteArray(), lies(tag2()))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals("in place", "Geteilt.", gast.text(R.id.teile_stand))
        assertNull("once", WlanAngebot.nachricht())
        gast.knopf(R.id.teile_nfc).performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(121))
        assertEquals("Abgelaufen.", gast.text(R.id.teile_stand))
        assertNull(WlanAngebot.nachricht())
    }

    @Test
    fun goneFromTheFrontNothingIsAnswered() {
        Behalten.setzeGastWlan(ctx(), WlanTag.Netz("Gast", WlanTag.Schutz.OFFEN.authentifizierung, ""))
        val c = Robolectric.buildActivity(TeilenActivity::class.java).setup()
        reihe(c.get(), "Gast-WLAN").knopf(R.id.teile_nfc).performClick()
        c.pause()
        assertNull(WlanAngebot.nachricht())
        c.resume()
        assertNotNull("back in front, within the two minutes", WlanAngebot.nachricht())
        c.pause().stop().destroy()
    }

    @Test
    fun theQrCodeIsLargeAndBright() {
        Behalten.setzeGastWlan(ctx(), WlanTag.Netz("Gast", WlanTag.Schutz.WPA2.authentifizierung, "geheim123"))
        val a = teilen()
        reihe(a, "Gast-WLAN").knopf(R.id.teile_qr).performClick()
        val d = ShadowDialog.getLatestDialog() as Dialog
        assertTrue(d.isShowing)
        assertEquals("Gast-WLAN", d.findViewById<TextView>(R.id.qr_titel).text.toString())
        assertEquals(1f, d.window!!.attributes.screenBrightness)
        assertNotNull(d.findViewById<android.widget.ImageView>(R.id.qr_bild).drawable)
        assertNull("QR only: nothing armed", WlanAngebot.nachricht())
        d.findViewById<Button>(R.id.qr_zu).performClick()
        assertFalse(d.isShowing)
    }

    @Test
    fun theGuestWifiIsChangedAndRemovedUnderDiesesTelefon() {
        Behalten.setzeGastWlan(ctx(), WlanTag.Netz("Gast", WlanTag.Schutz.WPA2.authentifizierung, "geheim123"))
        val g = Robolectric.buildActivity(GastWlanActivity::class.java).setup().get()
        assertEquals("Gast", g.findViewById<EditText>(R.id.gast_ssid).text.toString())
        g.findViewById<RadioGroup>(R.id.gast_schutz).check(R.id.gast_offen)
        assertEquals(View.GONE, g.findViewById<EditText>(R.id.gast_passwort).visibility)
        assertEquals(View.GONE, g.findViewById<CheckBox>(R.id.gast_zeigen).visibility)
        g.findViewById<Button>(R.id.gast_sichern).performClick()
        assertFalse("from „Dieses Telefon“: it stays", g.isFinishing)
        assertEquals(WlanTag.Schutz.OFFEN, Behalten.gastWlan(ctx())!!.schutz)
        g.findViewById<Button>(R.id.gast_entfernen).performClick()
        assertNull(Behalten.gastWlan(ctx()))
        assertEquals("Entfernt.", g.findViewById<TextView>(R.id.gast_stand).text.toString())
    }

    @Test
    fun theGuestWifiIsKeptEncrypted() {
        Behalten.setzeGastWlan(ctx(), WlanTag.Netz("Gast", WlanTag.Schutz.WPA2.authentifizierung, "geheim123"))
        val roh = ctx().getSharedPreferences("tresor", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(roh.contains("geheim123") || roh.contains("Gast"))
    }

    @Test
    fun aKeptTagsQrCode() {
        assertEquals("https://example.org/speisekarte", Visitenkarte.qrText(uri))
        val v = Visitenkarte.vcard(anna, setOf(Visitenkarte.Feld.NAME))
        assertEquals(v, Visitenkarte.qrText(Visitenkarte.ndef(v)))
        assertEquals("WIFI:T:nopass;S:Gast;;", Visitenkarte.qrText(WlanTag.nachricht("Gast", "", WlanTag.Schutz.OFFEN)))
        assertNull(Visitenkarte.qrText(NdefMessage(arrayOf(NdefRecord.createMime("application/x-fremd", byteArrayOf(1))))))
        assertNull("a phone number is no QR of ours", Visitenkarte.qrText(NdefMessage(arrayOf(NdefRecord.createUri("tel:+4930123")))))
    }

    // --- „Kontaktdaten eintragen lassen“ ---------------------------------------------------------------------

    @Test
    fun theInvitationLinkIsFetchedOnNfcThenArmed() {
        gekoppelt()
        val a = teilen()
        val r = reihe(a, "Kontaktdaten eintragen lassen")
        assertTrue("fetched only when chosen", homes.anfragen.isEmpty())
        homes.antwort = { MockResponse().setBody(LINK).setBodyDelay(300, TimeUnit.MILLISECONDS) }
        r.knopf(R.id.teile_nfc).performClick()
        assertEquals("Homes macht einen Link …", r.text(R.id.teile_stand))
        assertFalse("pressed: not twice", r.knopf(R.id.teile_nfc).isEnabled)
        warte { WlanAngebot.ssid != null }
        assertEquals(listOf("POST /napi/kontakte/einladen {}"), homes.anfragen)
        assertEquals("Halte das andere Telefon an deins … noch 2:00", r.text(R.id.teile_stand))
        val rec = NdefMessage(WlanAngebot.nachricht()!!).records.single()
        assertEquals("https://www.box.test/kontakt/abc123", rec.toUri().toString())
        assertTrue(r.knopf(R.id.teile_nfc).isEnabled)
    }

    @Test
    fun theInvitationAsQr() {
        gekoppelt()
        val a = teilen()
        reihe(a, "Kontaktdaten eintragen lassen").knopf(R.id.teile_qr).performClick()
        warte { ShadowDialog.getLatestDialog()?.isShowing == true }
        assertEquals("Kontaktdaten eintragen lassen", ShadowDialog.getLatestDialog().findViewById<TextView>(R.id.qr_titel).text.toString())
        assertNull(WlanAngebot.nachricht())
    }

    @Test
    fun aRefusalSaysHomesSentence() {
        gekoppelt()
        homes.antwort = { MockResponse().setResponseCode(403).setBody("""{"ok":false,"error":"Nur ein Admin kann Kontaktdaten eintragen lassen."}""") }
        val r = reihe(teilen(), "Kontaktdaten eintragen lassen")
        r.knopf(R.id.teile_nfc).performClick()
        warte { r.text(R.id.teile_stand) != "Homes macht einen Link …" }
        assertEquals("Nur ein Admin kann Kontaktdaten eintragen lassen.", r.text(R.id.teile_stand))
        assertNull("nothing armed", WlanAngebot.nachricht())
        assertTrue("may try again", r.knopf(R.id.teile_nfc).isEnabled)
    }

    @Test
    fun noAnswerInTimeIsSaid() {
        gekoppelt()
        TeilenActivity.http = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        homes.antwort = { MockResponse().setBody(LINK).setBodyDelay(2, TimeUnit.SECONDS) }
        val r = reihe(teilen(), "Kontaktdaten eintragen lassen")
        r.knopf(R.id.teile_nfc).performClick()
        warte { r.text(R.id.teile_stand) != "Homes macht einen Link …" }
        assertEquals("Homes hat nicht rechtzeitig geantwortet. Bitte gleich noch einmal tippen.", r.text(R.id.teile_stand))
        assertNull(WlanAngebot.nachricht())
    }

    @Test
    fun theInvitationRowFollowsTheBoxsContacts() {
        gekoppelt()
        fun kann(an: Boolean) = """{"version":1,"module":[{"name":"kontakte","titel":"Kontakte","an":$an,""" +
            """"bietet":{"widgets":[],"teilen":[],"konto":[],"sicherung":[],"mehr":[]}}],""" +
            """"app":{"widgets":[],"teilen":[],"konto":[],"sicherung":[],"mehr":[]}}"""
        KannStore.neu(ctx(), kann(false), null)
        assertTrue(reihen(teilen()).none { it.text(R.id.teile_titel) == "Kontaktdaten eintragen lassen" })
        KannStore.neu(ctx(), kann(true), null)
        assertTrue(reihen(teilen()).any { it.text(R.id.teile_titel) == "Kontaktdaten eintragen lassen" })
    }

    @Test
    fun aLinkThatIsNotHttpsIsNoLink() {
        assertEquals(
            EinladenClient.Ausgang.Abgelehnt(200, null),
            EinladenClient.ausgangAus(200, """{"ok":true,"link":"http://www.box.test/kontakt/abc"}"""),
        )
        assertEquals(EinladenClient.Ausgang.NichtGekoppelt, EinladenClient.ausgangAus(401, null))
        assertEquals(EinladenClient.Ausgang.Abgelehnt(503, "Das Adressbuch ist gerade nicht erreichbar."), EinladenClient.ausgangAus(503, """{"ok":false,"error":"Das Adressbuch ist gerade nicht erreichbar."}"""))
    }

    // --- „Im Telefon behalten“ on the Tag-Inhalt card -----------------------------------------------------

    private class Schreiber : TagSchreiber {
        var ergebnis: ((TagSchreiber.Ergebnis) -> Unit)? = null
        override fun bereit(ctx: Context) = TagSchreiber.Bereit.JA
        override fun an(activity: Activity, uri: () -> String?, ergebnis: (TagSchreiber.Ergebnis) -> Unit) {
            this.ergebnis = ergebnis
        }
        override fun aus(activity: Activity) = Unit
    }

    private fun halte(e: TagSchreiber.Ergebnis): AusloeserActivity {
        val s = Schreiber()
        AusloeserActivity.schreiber = s
        val a = Robolectric.buildActivity(AusloeserActivity::class.java).setup().get()
        s.ergebnis!!(e)
        shadowOf(Looper.getMainLooper()).idle()
        return a
    }

    @Test
    fun anNdefTagIsKeptUnderAName() {
        val a = halte(TagSchreiber.Ergebnis.Gelesen(uri, byteArrayOf(1)))
        val behalten = a.findViewById<Button>(R.id.inhalt_drei)
        assertEquals(View.VISIBLE, behalten.visibility)
        assertEquals("Im Telefon behalten", behalten.text.toString())
        behalten.performClick()
        val d = ShadowDialog.getLatestDialog() as AlertDialog
        (shadowOf(d).view as EditText).setText("Speisekarte")
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Speisekarte", Behalten.alle(ctx()).single().name)
        assertEquals("„Speisekarte“ ist im Telefon behalten.", a.findViewById<TextView>(R.id.inhalt_stand).text.toString())
    }

    @Test
    fun aDataCardCannotBeReplayed() {
        val a = halte(TagSchreiber.Ergebnis.EigeneDaten(Karte("MIFARE Classic 1K", byteArrayOf(4, 1, 2, 3))))
        assertTrue(a.findViewById<TextView>(R.id.inhalt_text).text.contains("Diese Karte kann ein Telefon nicht nachspielen."))
        assertEquals("no keep button", View.GONE, a.findViewById<Button>(R.id.inhalt_drei).visibility)
    }

    private companion object {
        const val T0 = 1_000_000L
        const val LINK = """{"ok":true,"link":"https://www.box.test/kontakt/abc123","text":"Trag hier deine Kontaktdaten ein:""" +
            """ https://www.box.test/kontakt/abc123","gueltig_bis":"2026-12-05T12:00:00Z"}"""
    }
}
