package cloud.dopp.homes

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.fragen.FragenClient
import cloud.dopp.homes.fragen.FragenClient.Ausgang
import cloud.dopp.homes.fragen.GeteilteKarte
import cloud.dopp.homes.fragen.KontaktFragenActivity
import cloud.dopp.homes.fragen.Weitergeben
import cloud.dopp.homes.kann.Kann
import cloud.dopp.homes.kann.Teilen
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
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Share → „Homes: Daten erfragen“ (owner 2026-11-28): the card a contacts app
 * shares is read tolerantly (Samsung's vCard 2.1 with QUOTED-PRINTABLE,
 * Google's 3.0), Homes' answer becomes one dialog in each state — reading,
 * running, done (WhatsApp, SMS, any app), not found („aufnehmen und
 * fragen“), more than one (pick), failed, timed out, not paired — and the
 * target is switched by the box's `teilen` `kontakt-fragen`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KontaktFragenTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private inner class Homes : Dispatcher() {
        var antwort: () -> MockResponse = { MockResponse().setBody(GEFUNDEN) }
        val anfragen = CopyOnWriteArrayList<Pair<String, String>>()
        override fun dispatch(request: RecordedRequest): MockResponse {
            anfragen += "${request.method} ${request.path}" to request.body.readUtf8()
            return if (request.path == "/napi/kontakte/fragen") antwort() else MockResponse().setResponseCode(404)
        }
    }

    private val homes = Homes()
    private val server = MockWebServer().also { it.dispatcher = homes }
    private val base get() = server.url("/").toString().trimEnd('/')

    @Before
    fun gekoppelt() {
        // the plain test server as the paired Homes (ServerStore keeps https only)
        ctx().getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit().putString("base_url", base).commit()
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_TESTTOKEN123")
    }

    @After
    fun zu() {
        KontaktFragenActivity.http = null
        ctx().getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit().clear().commit()
        TokenStore.clear(ctx())
        Teilen.schalten(ctx(), null)
        runCatching { server.shutdown() }
    }

    // --- the card ---------------------------------------------------------------

    @Test
    fun samsungsVcard21WithQuotedPrintableIsRead() {
        val k = GeteilteKarte.lies(SAMSUNG)!!
        assertEquals("Dr. Jürgen Müller-Lüdenscheid von und zu Großenhain", k.name)
        assertEquals("each number once, in the card's order", listOf("+49 170 1234567", "030 1234567"), k.telefon)
        assertEquals(listOf("juergen@example.de"), k.mail)
    }

    @Test
    fun googlesVcard30IsRead() {
        val k = GeteilteKarte.lies(GOOGLE)!!
        assertEquals("Anna Schäfer", k.name)
        assertEquals(listOf("+49 151 2345678", "+49 89 123456-0"), k.telefon)
        assertEquals(listOf("anna@example.org", "anna.schaefer@firma.example"), k.mail)
    }

    @Test
    fun theFirstCardOfSeveralAndItsFoldsCount() {
        val zwei = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Maximilian von Hohenzollern-Sigmar\r\n ingen\r\nTEL;VALUE=uri;TYPE=cell:tel:+49-170-1\r\n" +
            "END:VCARD\r\nBEGIN:VCARD\r\nVERSION:3.0\r\nFN:Zweite Karte\r\nTEL:0800 2\r\nEND:VCARD\r\n"
        val k = GeteilteKarte.lies(zwei)!!
        assertEquals("Maximilian von Hohenzollern-Sigmaringen", k.name)
        assertEquals(listOf("+49-170-1"), k.telefon)
    }

    @Test
    fun withoutFnTheNameIsNsPartsAndAnOtherCharsetIsDecoded() {
        val n = "BEGIN:VCARD\nVERSION:2.1\nN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:M=C3=BCller;J=C3=BCrgen;;;\nEND:VCARD\n"
        assertEquals("Jürgen Müller", GeteilteKarte.lies(n)!!.name)
        val latin = "BEGIN:VCARD\nVERSION:2.1\nFN;QUOTED-PRINTABLE;CHARSET=ISO-8859-1:J=FCrgen\nEND:VCARD\n"
        assertEquals("2.1's bare parameter", "Jürgen", GeteilteKarte.lies(latin)!!.name)
    }

    @Test
    fun textThatIsNoVcardIsNoCardAndAnEmptyCardIsEmpty() {
        assertNull(GeteilteKarte.lies("Anna Schäfer\nMobil: 0151 2345678"))
        assertNull(GeteilteKarte.lies(""))
        assertTrue(GeteilteKarte.lies("BEGIN:VCARD\nVERSION:3.0\nNOTE:nur das\nEND:VCARD")!!.leer)
        assertEquals("a broken escape stays as it stands", "a=Zb", String(GeteilteKarte.qpBytes("a=Zb")))
    }

    // --- Homes' answer ------------------------------------------------------------

    @Test
    fun everyAnswerIsAnOutcome() {
        val g = FragenClient.ausgangAus(200, GEFUNDEN) as Ausgang.Gefunden
        assertEquals(Ausgang.Gefunden("Anna Schäfer", "Anna", TEXT, listOf("Adresse", "Geburtstag"), "+491512345678"), g)
        assertTrue((FragenClient.ausgangAus(200, """{"ok":true,"kontakt":"A","link":"https://x/k"}""") as Ausgang.Gefunden).text == "https://x/k")
        assertEquals(Ausgang.NichtDa(NICHT_DA), FragenClient.ausgangAus(404, """{"ok":false,"error":"$NICHT_DA","anlegen":true}"""))
        assertEquals("a 404 without anlegen is a refusal", Ausgang.Abgelehnt(404, null), FragenClient.ausgangAus(404, "404 page not found"))
        assertEquals(
            Ausgang.Mehrere(listOf(FragenClient.Kandidat("Anna Schäfer (Schwester)", "u1"), FragenClient.Kandidat("Anna Schäfer", "u2")), null),
            FragenClient.ausgangAus(409, MEHRERE),
        )
        assertEquals(Ausgang.Abgelehnt(403, "Das darfst du nicht."), FragenClient.ausgangAus(403, """{"ok":false,"error":"Das darfst du nicht."}"""))
        assertEquals(Ausgang.NichtGekoppelt, FragenClient.ausgangAus(401, """{"error":"unauthorized"}"""))
        assertEquals("ok without a text", Ausgang.Abgelehnt(200, null), FragenClient.ausgangAus(200, """{"ok":true}"""))
    }

    @Test
    fun theRequestCarriesTheCardAndTheClientEndsInTimeOrOffline() {
        val k = GeteilteKarte.lies(GOOGLE)!!
        val c = FragenClient(base, "homes_device_T", OkHttpClient())
        assertTrue(c.fragen(k) is Ausgang.Gefunden)
        val body = JSONObject(homes.anfragen.last().second)
        assertEquals("POST /napi/kontakte/fragen", homes.anfragen.last().first)
        assertEquals("Anna Schäfer", body.getString("name"))
        assertEquals(2, body.getJSONArray("telefon").length())
        assertEquals("anna@example.org", body.getJSONArray("mail").getString(0))
        assertFalse(body.has("anlegen") || body.has("uid"))
        c.fragen(k, anlegen = true, uid = "u2")
        val wieder = JSONObject(homes.anfragen.last().second)
        assertTrue(wieder.getBoolean("anlegen"))
        assertEquals("u2", wieder.getString("uid"))

        homes.antwort = { MockResponse().setBody(GEFUNDEN).setBodyDelay(2, TimeUnit.SECONDS) }
        assertEquals(Ausgang.Zeit, FragenClient(base, "homes_device_T", kurz()).fragen(k))
        server.shutdown()
        assertEquals(Ausgang.Offline, c.fragen(k))
    }

    // --- the dialog ---------------------------------------------------------------

    @Test
    fun runningSaysWhomItLooksForThenWhatIsMissingAndTheWays() {
        homes.antwort = { MockResponse().setBody(GEFUNDEN).setBodyDelay(600, TimeUnit.MILLISECONDS) }
        val a = geteilt(text(GOOGLE))
        warte { meldung() == "Sucht Anna Schäfer im Haushalts-Adressbuch …" }
        assertEquals("running", "Sucht Anna Schäfer im Haushalts-Adressbuch …", meldung())
        assertTrue("no button while it runs", wahl().isEmpty())
        warte { meldung().startsWith("Bei") }
        assertEquals("Bei Anna fehlen: Adresse, Geburtstag", meldung())
        assertEquals("no WhatsApp on this phone", listOf("Per SMS", "Anders teilen …"), wahl())
        tippe("Per SMS")
        val sms = shadowOf(a).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, sms.action)
        assertEquals("smsto:%2B491512345678", sms.data.toString())
        assertEquals(TEXT, sms.getStringExtra("sms_body"))
        assertTrue("ends the share", a.isFinishing)
    }

    @Test
    fun withWhatsAppItIsTheFirstWayAndAnyAppIsTheLast() {
        shadowOf(ctx().packageManager).installPackage(
            PackageInfo().apply {
                packageName = Weitergeben.WHATSAPP
                applicationInfo = ApplicationInfo().apply { packageName = Weitergeben.WHATSAPP }
            },
        )
        val a = geteilt(text(GOOGLE))
        warte { meldung().startsWith("Bei") }
        assertEquals(listOf("Per WhatsApp an Anna", "Per SMS", "Anders teilen …"), wahl())
        tippe("Per WhatsApp an Anna")
        val wa = shadowOf(a).nextStartedActivity
        assertEquals(Weitergeben.WHATSAPP, wa.`package`)
        assertEquals("wa.me", wa.data!!.host)
        assertEquals("/491512345678", wa.data!!.path)
        assertEquals(TEXT, wa.data!!.getQueryParameter("text"))
        assertTrue(a.isFinishing)

        val b = geteilt(text(GOOGLE))
        warte { meldung().startsWith("Bei") }
        tippe("Anders teilen …")
        val chooser = shadowOf(b).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(TEXT, inner.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun allThereStillAsks() {
        homes.antwort = { MockResponse().setBody("""{"ok":true,"kontakt":"Anna Schäfer","vorname":"Anna","link":"https://x/k","text":"$TEXT","fehlt":[]}""") }
        geteilt(text(GOOGLE))
        warte { meldung().startsWith("Alles") }
        assertEquals("Alles da – trotzdem fragen?", meldung())
        assertEquals("no number from Homes: SMS to the card's", listOf("Per SMS", "Anders teilen …"), wahl())
    }

    @Test
    fun notFoundOffersToAddThenAsks() {
        homes.antwort = { MockResponse().setResponseCode(404).setBody("""{"ok":false,"error":"$NICHT_DA","anlegen":true}""") }
        geteilt(text(SAMSUNG))
        warte { meldung() == NICHT_DA }
        assertEquals(listOf("Ins Haushalts-Adressbuch aufnehmen und fragen"), wahl())
        homes.antwort = { MockResponse().setBody(GEFUNDEN.replace("\"ok\":true", "\"ok\":true,\"neu\":true")) }
        tippe("Ins Haushalts-Adressbuch aufnehmen und fragen")
        warte { meldung().contains("fehlen") }
        assertEquals("Anna Schäfer ist jetzt im Haushalts-Adressbuch. Bei Anna fehlen: Adresse, Geburtstag", meldung())
        assertTrue(JSONObject(homes.anfragen.last().second).getBoolean("anlegen"))
    }

    @Test
    fun moreThanOneIsPickedAndAskedAgainByUid() {
        homes.antwort = { MockResponse().setResponseCode(409).setBody(MEHRERE) }
        geteilt(text(GOOGLE))
        warte { meldung() == "Mehrere Kontakte passen. Wer ist gemeint?" }
        assertEquals(listOf("Anna Schäfer (Schwester)", "Anna Schäfer"), wahl())
        homes.antwort = { MockResponse().setBody(GEFUNDEN) }
        tippe("Anna Schäfer")
        warte { meldung().startsWith("Bei") }
        assertEquals("u2", JSONObject(homes.anfragen.last().second).getString("uid"))
    }

    @Test
    fun aRefusalATimeoutAndNoCardAreSaid() {
        homes.antwort = { MockResponse().setResponseCode(403).setBody("""{"ok":false,"error":"Das darfst du nicht."}""") }
        val a = geteilt(text(GOOGLE))
        warte { meldung() == "Das darfst du nicht." }
        assertEquals("Fertig", knopf())
        dialog().findViewById<android.widget.Button>(R.id.ad_cancel).performClick()
        assertTrue(a.isFinishing)

        homes.antwort = { MockResponse().setResponseCode(502).setBody("bad gateway") }
        geteilt(text(GOOGLE))
        warte { meldung().contains("502") }
        assertEquals("Homes hat nicht lesbar geantwortet (502). Bitte später noch einmal teilen.", meldung())

        KontaktFragenActivity.http = kurz()
        homes.antwort = { MockResponse().setBody(GEFUNDEN).setBodyDelay(2, TimeUnit.SECONDS) }
        geteilt(text(GOOGLE))
        warte { meldung().contains("rechtzeitig") }
        assertEquals("timed out", "Homes hat nicht rechtzeitig geantwortet. Bitte gleich noch einmal teilen.", meldung())

        val vorher = homes.anfragen.size
        geteilt(text("Anna Schäfer\nMobil: 0151 2345678"))
        warte { meldung().startsWith("Das ist keine") }
        assertTrue("no vCard: no request", homes.anfragen.size == vorher)
    }

    @Test
    fun aVcfFileIsReadAndUnpairedIsSaid() {
        val uri = Uri.parse("content://contacts/as_vcard/42")
        shadowOf(ctx().contentResolver).registerInputStream(uri, ByteArrayInputStream(SAMSUNG.toByteArray()))
        geteilt(Intent(Intent.ACTION_SEND).setType("text/x-vcard").putExtra(Intent.EXTRA_STREAM, uri))
        warte { meldung().startsWith("Bei") }
        assertEquals("Dr. Jürgen Müller-Lüdenscheid von und zu Großenhain", JSONObject(homes.anfragen.last().second).getString("name"))

        TokenStore.clear(ctx())
        geteilt(text(GOOGLE))
        warte { meldung().contains("gekoppelt") }
        assertTrue(meldung().contains("nicht mit Homes gekoppelt"))
    }

    // --- the share sheet ------------------------------------------------------------

    @Test
    fun theTargetTakesVcardsAndFollowsTheBox() {
        val manifest = File(RepoFiles.main, "AndroidManifest.xml").readText()
        val act = manifest.substringAfter("""android:name=".fragen.KontaktFragenActivity"""").substringBefore("</activity>")
        listOf("text/x-vcard", "text/vcard", "text/plain").forEach { assertTrue(it, act.contains("""android:mimeType="$it"""")) }
        assertTrue(act, act.contains("""android:taskAffinity=""""") && act.contains("android.intent.action.SEND"))
        assertTrue("WhatsApp is visible to the app", manifest.contains("""<package android:name="com.whatsapp" />"""))

        val c = ComponentName(ctx(), KontaktFragenActivity::class.java)
        val pm = ctx().packageManager
        Teilen.schalten(ctx(), Kann.lesen(kann("[]")))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(c))
        Teilen.schalten(ctx(), Kann.lesen(kann("""["kontakt-fragen"]""")))
        assertTrue(Teilen.an(ctx(), "kontakt-fragen"))
    }

    // --- helpers ------------------------------------------------------------------

    private fun kann(teilen: String) =
        """{"version":1,"module":[],"app":{"widgets":[],"teilen":$teilen,"konto":[],"sicherung":[],"mehr":[]}}"""

    private fun kurz() = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()

    private fun text(t: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t)

    private fun geteilt(i: Intent): KontaktFragenActivity =
        Robolectric.buildActivity(KontaktFragenActivity::class.java, i).create().get()

    private fun dialog(): AlertDialog = ShadowDialog.getLatestDialog() as AlertDialog

    private fun meldung(): String =
        (ShadowDialog.getLatestDialog() as AlertDialog?)?.findViewById<TextView>(R.id.ad_message)?.text?.toString().orEmpty()

    private fun knopf(): String = dialog().findViewById<android.widget.Button>(R.id.ad_cancel).text.toString()

    private fun reihen() = dialog().findViewById<LinearLayout>(R.id.ad_items).let { s -> (0 until s.childCount).map { s.getChildAt(it) } }

    private fun wahl(): List<String> = reihen().mapNotNull { it.findViewById<TextView>(R.id.ar_label)?.text?.toString() }

    private fun tippe(was: String) {
        reihen().first { it.findViewById<TextView>(R.id.ar_label)?.text?.toString() == was }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Until [bis] holds, the main looper run between looks; background threads answer meanwhile. */
    private fun warte(bis: () -> Boolean) {
        val ende = System.currentTimeMillis() + 10_000
        while (!bis() && System.currentTimeMillis() < ende) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        const val TEXT = "Hallo Anna, magst du kurz deine Adresse und deinen Geburtstag eintragen? https://www.box.test/k/abc123"
        const val NICHT_DA = "Jürgen Müller steht nicht im Haushalts-Adressbuch."
        const val GEFUNDEN = """{"ok":true,"kontakt":"Anna Schäfer","vorname":"Anna","link":"https://www.box.test/k/abc123",""" +
            """"text":"$TEXT","fehlt":["Adresse","Geburtstag"],"telefon":"+491512345678"}"""
        const val MEHRERE = """{"ok":false,"kandidaten":[{"kontakt":"Anna Schäfer (Schwester)","uid":"u1"},{"kontakt":"Anna Schäfer","uid":"u2"},{"kontakt":"ohne"}]}"""

        /** Samsung Contacts → „Senden“ → „vCard-Datei (VCF)“: vCard 2.1, CRLF, QUOTED-PRINTABLE with a soft break, a photo. */
        val SAMSUNG = listOf(
            "BEGIN:VCARD",
            "VERSION:2.1",
            "N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:M=C3=BCller-L=C3=BCdenscheid;J=C3=BCrgen;;Dr.;",
            "FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Dr. J=C3=BCrgen M=C3=BCller-L=C3=BCdenscheid von und zu Gro=C3=9F=",
            "enhain",
            "TEL;CELL;PREF:+49 170 1234567",
            "TEL;HOME:030 1234567",
            "TEL;CELL:+49 170 1234567",
            "EMAIL;HOME:juergen@example.de",
            "ADR;HOME;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:;;Hauptstra=C3=9Fe 1;Berlin;;10115;",
            "PHOTO;ENCODING=BASE64;JPEG:/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRof",
            " Hh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIy",
            "",
            "END:VCARD",
            "",
        ).joinToString("\r\n")

        /** Google Contacts' export: vCard 3.0, LF. */
        val GOOGLE = listOf(
            "BEGIN:VCARD",
            "VERSION:3.0",
            "FN:Anna Schäfer",
            "N:Schäfer;Anna;;;",
            "EMAIL;TYPE=INTERNET;TYPE=HOME:anna@example.org",
            "EMAIL;TYPE=INTERNET;TYPE=WORK:anna.schaefer@firma.example",
            "TEL;TYPE=CELL:+49 151 2345678",
            "TEL;TYPE=WORK:+49 89 123456-0",
            "ADR;TYPE=HOME:;;Lindenweg 3;München;;80331;Deutschland",
            "item1.URL:https\\://anna.example",
            "item1.X-ABLabel:_\$!<HomePage>!\$_",
            "CATEGORIES:myContacts",
            "END:VCARD",
            "",
        ).joinToString("\n")
    }
}
