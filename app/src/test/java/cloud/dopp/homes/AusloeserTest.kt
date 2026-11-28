package cloud.dopp.homes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.ausloeser.Ausloeser
import cloud.dopp.homes.ausloeser.AusloeserActivity
import cloud.dopp.homes.ausloeser.AusloeserClient
import cloud.dopp.homes.ausloeser.AusloeserClient.Ausgang
import cloud.dopp.homes.ausloeser.TagActivity
import cloud.dopp.homes.ausloeser.TagSchreiber
import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.kann.Kann
import cloud.dopp.homes.kann.Liste
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The phone as a trigger (homes ADR 0004 §1): what a tag says in both forms,
 * how the phone charges, each call's outcome in German, the touched tag's
 * one line, and the Auslöser screen's states — idle, running, done, failed,
 * timed out — against a Homes that answers as the contract says.
 */
@RunWith(RobolectricTestRunner::class) // org.json and the activities are Android's
@Config(sdk = [34])
class AusloeserTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    /** Homes' side of ADR 0004 §1, by path; each test sets what it answers. */
    private inner class Homes : Dispatcher() {
        // made fresh for every request: a response's body is read once
        var melden: () -> MockResponse = { MockResponse().setBody("""{"ok":true,"name":"Bürotür"}""") }
        var neu: () -> MockResponse = { MockResponse().setBody(NEU) }
        var tags: () -> MockResponse = {
            MockResponse().setBody("""{"ok":true,"tags":[{"tag":"homes-0123456789ab","name":"Bürotür"},{"tag":"abc","name":""}],"vorschlaege":["Büro","Bad","Tür"]}""")
        }
        var weg: () -> MockResponse = { MockResponse().setBody("""{"ok":true}""") }
        val anfragen = CopyOnWriteArrayList<Pair<String, String>>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            anfragen += "${request.method} ${request.path}" to request.body.readUtf8()
            return when (request.path) {
                "/napi/ausloeser" -> melden()
                "/napi/ausloeser/tag" -> neu()
                "/napi/ausloeser/tags" -> tags()
                else -> if (request.method == "DELETE" && request.path!!.startsWith("/napi/ausloeser/tag/")) weg() else MockResponse().setResponseCode(404)
            }
        }
    }

    private companion object {
        const val NEU = """{"ok":true,"tag":"homes-0123456789ab","uri":"cloud.dopp.homes://tag/homes-0123456789ab"}"""
    }

    private val homes = Homes()
    private val server = MockWebServer().also { it.dispatcher = homes }
    private val base get() = server.url("/").toString().trimEnd('/')

    /** The phone's NFC: [ergebnis] is what a tag held to it gives, [uri] what would be written onto it now. */
    private class Schreiber : TagSchreiber {
        var bereit = TagSchreiber.Bereit.JA
        var uriVon: (() -> String?)? = null
        var ergebnis: ((TagSchreiber.Ergebnis) -> Unit)? = null
        val uri: String? get() = uriVon?.invoke()
        override fun bereit(ctx: Context) = bereit
        override fun an(activity: Activity, uri: () -> String?, ergebnis: (TagSchreiber.Ergebnis) -> Unit) {
            uriVon = uri
            this.ergebnis = ergebnis
        }
        override fun aus(activity: Activity) {
            uriVon = null
            ergebnis = null
        }
    }

    private val schreiber = Schreiber()

    @Before
    fun gekoppelt() {
        // the plain test server as the paired Homes (ServerStore keeps https only)
        ctx().getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit().putString("base_url", base).commit()
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_TESTTOKEN123")
        AusloeserActivity.schreiber = schreiber
    }

    @After
    fun zu() {
        AusloeserActivity.schreiber = cloud.dopp.homes.ausloeser.NfcSchreiber
        ctx().getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit().clear().commit()
        TokenStore.clear(ctx())
        Ausloeser.setzeLadenMelden(ctx(), false)
        runCatching { server.shutdown() } // a test may have shut it down already
    }

    private fun client(http: OkHttpClient = OkHttpClient()) = AusloeserClient(base, "homes_device_T", http)

    /** Until [bis] holds, the main looper run between looks; background threads answer meanwhile. */
    private fun warte(bis: () -> Boolean) {
        val ende = System.currentTimeMillis() + 10_000
        while (!bis() && System.currentTimeMillis() < ende) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    // --- what a tag says ------------------------------------------------------------

    @Test
    fun theIdOfBothForms() {
        assertEquals("homes-0123456789ab", Ausloeser.tagAus("cloud.dopp.homes://tag/homes-0123456789ab"))
        assertEquals("homes-0123456789ab", Ausloeser.tagAus("CLOUD.DOPP.HOMES://TAG/homes-0123456789ab/"))
        // a tag HA's own app wrote keeps working: the id is what follows /tag/
        assertEquals("8a1c7e3e-2f4b-4b8e-9a51-0c6d1e2f3a4b", Ausloeser.tagAus("https://www.home-assistant.io/tag/8a1c7e3e-2f4b-4b8e-9a51-0c6d1e2f3a4b"))
        assertEquals("abc", Ausloeser.tagAus(" https://www.home-assistant.io/tag/abc?x=1 "))
    }

    @Test
    fun anythingElseIsNoTag() {
        listOf(
            null, "", "cloud.dopp.homes://tag/", "cloud.dopp.homes://pair/abc", "cloud.dopp.homes://tag/a/b",
            "http://www.home-assistant.io/tag/abc", "https://home-assistant.io/tag/abc", "https://www.home-assistant.io/x/abc",
            "https://evil.example/tag/abc", "cloud.dopp.homes://tag/a%20b", "cloud.dopp.homes://tag/" + "x".repeat(129), "kein uri",
        ).forEach { assertNull("taken: $it", Ausloeser.tagAus(it)) }
    }

    @Test
    fun howThePhoneCharges() {
        assertEquals(Napi.Ladeart.KABELLOS, Ausloeser.ladeart(BatteryManager.BATTERY_PLUGGED_WIRELESS))
        assertEquals(Napi.Ladeart.KABEL, Ausloeser.ladeart(BatteryManager.BATTERY_PLUGGED_AC))
        assertEquals(Napi.Ladeart.USB, Ausloeser.ladeart(BatteryManager.BATTERY_PLUGGED_USB))
        assertEquals(Napi.Ladeart.UNBEKANNT, Ausloeser.ladeart(BatteryManager.BATTERY_PLUGGED_DOCK))
        assertEquals(Napi.Ladeart.UNBEKANNT, Ausloeser.ladeart(0))
        assertEquals("", Napi.Ladeart.UNBEKANNT.wire)
    }

    /** Mehr's row while `mehr` has `ausloeser`; the geraete module off leaves its trace (homes ADR 0001 §10). */
    @Test
    fun theRowIsTheBoxs() {
        val an = Kann.lesen("""{"version":1,"module":[],"app":{"mehr":["ausloeser"]}}""")!!
        assertTrue(an.hat(Liste.MEHR, Napi.KannKey.AUSLOESER))
        val aus = Kann.lesen(
            """{"version":1,"module":[{"name":"geraete","titel":"Geräte","an":false,"bietet":{"mehr":["ausloeser"]}}],"app":{"mehr":[]}}""",
        )!!
        assertFalse(aus.hat(Liste.MEHR, Napi.KannKey.AUSLOESER))
        assertEquals("geraete", aus.spurVon(Liste.MEHR, Napi.KannKey.AUSLOESER)?.name)
    }

    // --- the calls ------------------------------------------------------------------

    @Test
    fun aTagGoesAsTheContractSays() {
        val a = client().nfc("homes-0123456789ab")
        assertEquals(Ausgang.Gemeldet("Bürotür"), a)
        assertEquals("Bürotür – gemeldet", Ausloeser.tagZeile(a))
        val (was, body) = homes.anfragen.single()
        assertEquals("POST /napi/ausloeser", was)
        val o = JSONObject(body)
        assertEquals("nfc", o.getString("art"))
        assertEquals("homes-0123456789ab", o.getString("tag"))
        assertFalse(o.has("zustand") || o.has("ladeart"))
    }

    @Test
    fun aTagHaDoesNotKnowIsSaidSo() {
        homes.melden = { MockResponse().setBody("""{"ok":true,"name":""}""") }
        assertEquals("Unbekannter Tag – auf Homes oder hier benennen", Ausloeser.tagZeile(client().nfc("abc")))
    }

    @Test
    fun theChargerGoesWithHowItCharges() {
        homes.melden = { MockResponse().setBody("""{"ok":true}""") }
        val a = client().laden(false, Napi.Ladeart.UNBEKANNT)
        assertEquals("Lädt nicht mehr – gemeldet", Ausloeser.ladenZeile(false, Napi.Ladeart.UNBEKANNT, a))
        val o = JSONObject(homes.anfragen.single().second)
        assertEquals("laden", o.getString("art"))
        assertEquals("aus", o.getString("zustand"))
        assertEquals("", o.getString("ladeart"))
        assertEquals("Lädt kabellos – gemeldet", Ausloeser.ladenZeile(true, Napi.Ladeart.KABELLOS, Ausgang.Gemeldet("")))
    }

    @Test
    fun everyFailureIsAGermanSentence() {
        homes.melden = { MockResponse().setResponseCode(404).setBody("""{"ok":false,"reason":"not_found"}""") }
        assertEquals(Ausgang.OhneHa, client().nfc("abc"))
        assertTrue(Ausloeser.tagZeile(Ausgang.OhneHa).contains("kein Home Assistant"))

        homes.melden = { MockResponse().setResponseCode(502).setBody("""{"ok":false,"error":"Home Assistant antwortet nicht."}""") }
        assertEquals("Home Assistant antwortet nicht.", Ausloeser.tagZeile(client().nfc("abc")))

        homes.melden = { MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}""") }
        assertEquals(Ausloeser.NICHT_GEKOPPELT, Ausloeser.tagZeile(client().nfc("abc")))

        homes.melden = { MockResponse().setResponseCode(500).setBody("kaputt") }
        assertEquals("Homes hat nicht lesbar geantwortet. (500)", Ausloeser.tagZeile(client().nfc("abc")))

        homes.melden = { MockResponse().setBody("""{"ok":true,"name":"x"}""").setBodyDelay(2, TimeUnit.SECONDS) }
        val kurz = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        assertEquals(Ausgang.Zeit, client(kurz).nfc("abc"))
        assertTrue(Ausloeser.tagZeile(Ausgang.Zeit).contains("nicht rechtzeitig"))

        server.shutdown()
        assertEquals(Ausgang.Offline, client().nfc("abc"))
        assertTrue(Ausloeser.tagZeile(Ausgang.Offline).contains("nicht erreichbar"))
    }

    @Test
    fun aNewTagAndTheList() {
        assertEquals(
            Ausgang.Angelegt("homes-0123456789ab", "cloud.dopp.homes://tag/homes-0123456789ab"),
            client().benennen("Bürotür"),
        )
        val neu = JSONObject(homes.anfragen.single().second)
        assertEquals("Bürotür", neu.getString("name"))
        assertFalse("a new tag names no id", neu.has("tag"))
        assertEquals(
            Ausgang.Tags(
                listOf(AusloeserClient.Tag("homes-0123456789ab", "Bürotür"), AusloeserClient.Tag("abc", "")),
                listOf("Büro", "Bad", "Tür"),
            ),
            client().tags(),
        )
        homes.neu = { MockResponse().setBody("""{"ok":true}""") }
        assertTrue("no uri: not a tag to write", client().benennen("x") is Ausgang.Abgelehnt)
        // the uri Homes gives is read back as the tag's id
        assertEquals("homes-0123456789ab", Ausloeser.tagAus("cloud.dopp.homes://tag/homes-0123456789ab"))
    }

    // --- a touched tag --------------------------------------------------------------

    private fun beruehrt(uri: String): TagActivity {
        val intent = Intent(NfcAdapter.ACTION_NDEF_DISCOVERED, Uri.parse(uri))
        return Robolectric.buildActivity(TagActivity::class.java, intent).setup().get()
    }

    private fun TagActivity.zeile() = findViewById<TextView>(R.id.tag_zeile).text.toString()

    @Test
    fun aTouchedTagSaysMeldetAtOnceThenItsNameThenCloses() {
        homes.melden = { MockResponse().setBody("""{"ok":true,"name":"Bürotür"}""").setBodyDelay(300, TimeUnit.MILLISECONDS) }
        val a = beruehrt("cloud.dopp.homes://tag/homes-0123456789ab")
        assertEquals("running at once", "Meldet …", a.zeile())
        warte { a.zeile() != "Meldet …" }
        assertEquals("Bürotür – gemeldet", a.zeile())
        assertEquals("one report", 1, homes.anfragen.size)
        assertFalse(a.isFinishing)
        shadowOf(Looper.getMainLooper()).idleFor(TagActivity.ZEIGEN_MS, TimeUnit.MILLISECONDS)
        assertTrue("closes after the line was read", a.isFinishing)
    }

    @Test
    fun aTagOfHasAppIsReportedByItsId() {
        val a = beruehrt("https://www.home-assistant.io/tag/8a1c7e3e")
        warte { a.zeile() != "Meldet …" }
        assertEquals("8a1c7e3e", JSONObject(homes.anfragen.single().second).getString("tag"))
    }

    @Test
    fun unpairedTheTagSaysSoAndSendsNothing() {
        TokenStore.clear(ctx())
        val a = beruehrt("cloud.dopp.homes://tag/abc")
        assertEquals(Ausloeser.NICHT_GEKOPPELT, a.zeile())
        assertTrue(homes.anfragen.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(TagActivity.ZEIGEN_MS, TimeUnit.MILLISECONDS)
        assertTrue(a.isFinishing)
    }

    @Test
    fun aFailedReportShowsHomesSentence() {
        homes.melden = { MockResponse().setResponseCode(404).setBody("""{"ok":false,"reason":"not_found"}""") }
        val a = beruehrt("cloud.dopp.homes://tag/abc")
        warte { a.zeile() != "Meldet …" }
        assertTrue(a.zeile(), a.zeile().contains("kein Home Assistant"))
    }

    // --- the Auslöser screen ----------------------------------------------------------

    private fun bildschirm(): AusloeserActivity = Robolectric.buildActivity(AusloeserActivity::class.java).setup().get()

    private fun AusloeserActivity.stand() = findViewById<TextView>(R.id.ausloeser_stand).text.toString()
    private fun AusloeserActivity.knopf() = findViewById<Button>(R.id.ausloeser_schreiben)

    @Test
    fun theScreenListsTheTagsAndTheChargerIsOffByDefault() {
        val a = bildschirm()
        val liste = a.findViewById<LinearLayout>(R.id.ausloeser_liste)
        warte { liste.childCount == 2 && (liste.getChildAt(0) as TextView).text != "Lädt die Tags …" }
        assertEquals("the id is never shown", "Bürotür", (liste.getChildAt(0) as TextView).text.toString())
        assertEquals("ohne Namen", (liste.getChildAt(1) as TextView).text.toString())
        val laden = a.findViewById<SwitchCompat>(R.id.ausloeser_laden)
        assertFalse("off by default", laden.isChecked)
        laden.isChecked = true
        assertTrue(Ausloeser.ladenMelden(ctx()))
    }

    @Test
    fun aListThatFailsSaysWhy() {
        homes.tags = { MockResponse().setResponseCode(404).setBody("""{"ok":false,"reason":"not_found"}""") }
        val a = bildschirm()
        val liste = a.findViewById<LinearLayout>(R.id.ausloeser_liste)
        warte { (liste.getChildAt(0) as? TextView)?.text?.contains("Home Assistant") == true }
        assertTrue((liste.getChildAt(0) as TextView).text.contains("kein Home Assistant"))
    }

    @Test
    fun aNewTagRunsWaitsAndEndsDone() {
        homes.neu = { MockResponse().setBody(NEU).setBodyDelay(300, TimeUnit.MILLISECONDS) }
        val a = bildschirm()
        a.knopf().performClick()
        assertEquals("idle without a name", "Gib dem Tag zuerst einen Namen.", a.stand())
        a.findViewById<EditText>(R.id.ausloeser_name).setText("Bürotür")
        a.knopf().performClick()
        assertEquals("running on the button at once", "Legt an …", a.knopf().text.toString())
        assertFalse(a.knopf().isEnabled)
        warte { schreiber.uri != null }
        assertEquals("cloud.dopp.homes://tag/homes-0123456789ab", schreiber.uri)
        assertEquals("Halte den Tag ans Telefon … („Bürotür“)", a.stand())
        assertEquals("Abbrechen", a.knopf().text.toString())
        schreiber.ergebnis!!(TagSchreiber.Ergebnis.Fertig)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(a.stand(), a.stand().startsWith("„Bürotür“ ist fertig. Kleb ihn hin und sag in der Werkstatt, was er tun soll."))
        assertTrue("the name on the sticker", a.stand().contains("Aufkleber"))
        assertEquals("Tag beschreiben", a.knopf().text.toString())
        assertEquals("", a.findViewById<EditText>(R.id.ausloeser_name).text.toString())
    }

    @Test
    fun aFailedWriteSaysWhyAndTheRetryMakesNoSecondTag() {
        val a = bildschirm()
        a.findViewById<EditText>(R.id.ausloeser_name).setText("Bürotür")
        a.knopf().performClick()
        warte { schreiber.uri != null } // the screen waits for a tag (the callback itself is set on opening)
        schreiber.ergebnis!!(TagSchreiber.Ergebnis.Fehler("Der Tag war zu früh weg."))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Der Tag war zu früh weg.", a.stand())
        assertEquals("Noch einmal versuchen", a.knopf().text.toString())
        a.knopf().performClick()
        assertEquals("waits again at once", "cloud.dopp.homes://tag/homes-0123456789ab", schreiber.uri)
        assertEquals("one tag made in HA", 1, homes.anfragen.count { it.first == "POST /napi/ausloeser/tag" })
    }

    @Test
    fun noTagWithinAMinuteIsSaid() {
        val a = bildschirm()
        a.findViewById<EditText>(R.id.ausloeser_name).setText("Bürotür")
        a.knopf().performClick()
        warte { schreiber.uri != null } // the screen waits for a tag (the callback itself is set on opening)
        shadowOf(Looper.getMainLooper()).idleFor(61, TimeUnit.SECONDS)
        assertEquals("Kein Tag gekommen. Tippe noch einmal und halte ihn ans Telefon.", a.stand())
        assertNull("no longer waiting: nothing would be written", schreiber.uri)
    }

    @Test
    fun aRefusedNewTagAndAPhoneWithoutNfcAreSaid() {
        homes.neu = { MockResponse().setResponseCode(503).setBody("""{"ok":false,"error":"Home Assistant ist gerade nicht erreichbar."}""") }
        val a = bildschirm()
        a.findViewById<EditText>(R.id.ausloeser_name).setText("Bürotür")
        a.knopf().performClick()
        warte { a.knopf().isEnabled }
        assertEquals("Home Assistant ist gerade nicht erreichbar.", a.stand())
        assertEquals("Tag beschreiben", a.knopf().text.toString())

        schreiber.bereit = TagSchreiber.Bereit.KEIN_NFC
        a.knopf().performClick()
        assertEquals("Dieses Telefon hat kein NFC.", a.stand())
        schreiber.bereit = TagSchreiber.Bereit.AUS
        a.knopf().performClick()
        assertEquals("NFC ist aus. Tippe hier und schalte es ein.", a.stand())
    }

    // --- naming (homes ADR 0004 §2) -------------------------------------------------

    @Test
    fun theClientNamesRenamesAndRemoves() {
        assertTrue(client().benennen("Bad oben", "abc") is Ausgang.Angelegt)
        val o = JSONObject(homes.anfragen.last().second)
        assertEquals("Bad oben", o.getString("name"))
        assertEquals("that id is named, not a new one", "abc", o.getString("tag"))

        homes.neu = { MockResponse().setResponseCode(409).setBody("""{"ok":false,"error":"Es gibt schon einen Tag ‚Bad‘ – nimm einen anderen Namen."}""") }
        assertEquals("Es gibt schon einen Tag ‚Bad‘ – nimm einen anderen Namen.", Ausloeser.fehler(client().benennen("bad", "abc")))

        assertEquals(Ausgang.Entfernt, client().entfernen("homes-0123456789ab"))
        assertEquals("DELETE /napi/ausloeser/tag/homes-0123456789ab", homes.anfragen.last().first)
        homes.weg = { MockResponse().setResponseCode(404).setBody("""{"ok":false,"error":"Diesen Tag kennt Home Assistant nicht."}""") }
        assertEquals("a tag HA lacks is no box without HA", "Diesen Tag kennt Home Assistant nicht.", Ausloeser.fehler(client().entfernen("x")))
    }

    @Test
    fun theSuggestionsAreOneTapEach() {
        val a = bildschirm()
        val reihe = a.findViewById<LinearLayout>(R.id.ausloeser_vorschlaege)
        warte { reihe.childCount == 3 }
        assertEquals("the suggestions shown (list: ${a.findViewById<LinearLayout>(R.id.ausloeser_liste).childCount} rows)", 3, reihe.childCount)
        assertEquals("Büro", (reihe.getChildAt(0) as Button).text.toString())
        reihe.getChildAt(1).performClick()
        assertEquals("Bad", a.findViewById<EditText>(R.id.ausloeser_name).text.toString())
    }

    private fun AusloeserActivity.ersteZeile(): TextView {
        val liste = findViewById<LinearLayout>(R.id.ausloeser_liste)
        warte { (liste.getChildAt(0) as? TextView)?.text?.toString() == "Bürotür" }
        return liste.getChildAt(0) as TextView
    }

    @Test
    fun aTagInTheListIsRenamedNotWritten() {
        val a = bildschirm()
        a.ersteZeile().performClick()
        assertEquals("Tag umbenennen", a.findViewById<TextView>(R.id.ausloeser_name_titel).text.toString())
        assertEquals("Bürotür", a.findViewById<EditText>(R.id.ausloeser_name).text.toString())
        assertEquals("Sichern", a.knopf().text.toString())
        assertEquals(android.view.View.VISIBLE, a.findViewById<android.view.View>(R.id.ausloeser_entfernen).visibility)

        homes.neu = { MockResponse().setBody(NEU).setBodyDelay(300, TimeUnit.MILLISECONDS) }
        a.findViewById<EditText>(R.id.ausloeser_name).setText("Bürotür oben")
        a.knopf().performClick()
        assertEquals("running on the button at once", "Sichert …", a.knopf().text.toString())
        assertFalse(a.knopf().isEnabled)
        warte { a.knopf().isEnabled }
        assertEquals("Der Tag heißt jetzt „Bürotür oben“. Automationen, die ihn nutzen, laufen weiter.", a.stand())
        val o = JSONObject(homes.anfragen.last { it.first == "POST /napi/ausloeser/tag" }.second)
        assertEquals("homes-0123456789ab", o.getString("tag"))
        assertNull("nothing is written onto the tag", schreiber.uri)
        assertEquals("back to a new tag", "Tag beschreiben", a.knopf().text.toString())
    }

    @Test
    fun aTakenNameIsSaidAndTheRenameStays() {
        homes.neu = { MockResponse().setResponseCode(409).setBody("""{"ok":false,"error":"Es gibt schon einen Tag ‚Bad‘ – nimm einen anderen Namen."}""") }
        val a = bildschirm()
        a.ersteZeile().performClick()
        a.findViewById<EditText>(R.id.ausloeser_name).setText("Bad")
        a.knopf().performClick()
        warte { a.knopf().isEnabled }
        assertEquals("Es gibt schon einen Tag ‚Bad‘ – nimm einen anderen Namen.", a.stand())
        assertEquals("Sichern", a.knopf().text.toString())
    }

    @Test
    fun removingAsksOnceThenRemoves() {
        val a = bildschirm()
        a.ersteZeile().performClick()
        a.findViewById<Button>(R.id.ausloeser_entfernen).performClick()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.AlertDialog
        assertTrue(dialog.isShowing)
        val stapel = dialog.findViewById<LinearLayout>(R.id.ad_items)!!
        val texte = (0 until stapel.childCount).map { i ->
            stapel.getChildAt(i).findViewById<TextView>(R.id.ar_label)?.text?.toString() ?: (stapel.getChildAt(i) as? TextView)?.text?.toString()
        }
        assertTrue("one question before: $texte", homes.anfragen.none { it.first.startsWith("DELETE") })
        val zeile = (0 until stapel.childCount).map { stapel.getChildAt(it) }
            .first { it.findViewById<TextView>(R.id.ar_label)?.text?.toString() == "Entfernen" }
        zeile.performClick()
        warte { a.stand() == "„Bürotür“ ist entfernt." }
        assertEquals("„Bürotür“ ist entfernt.", a.stand())
        assertEquals("DELETE /napi/ausloeser/tag/homes-0123456789ab", homes.anfragen.last { it.first.startsWith("DELETE") }.first)
    }

    @Test
    fun anUnknownTagOffersBenennenWhichNamesThatId() {
        homes.melden = { MockResponse().setBody("""{"ok":true,"name":""}""") }
        val t = beruehrt("https://www.home-assistant.io/tag/abc")
        warte { t.zeile() != "Meldet …" }
        val benennen = t.findViewById<Button>(R.id.tag_benennen)
        assertEquals(android.view.View.VISIBLE, benennen.visibility)
        assertEquals("Benennen", benennen.text.toString())
        assertFalse("stays long enough to decide", run {
            shadowOf(Looper.getMainLooper()).idleFor(TagActivity.ZEIGEN_MS, TimeUnit.MILLISECONDS)
            t.isFinishing
        })
        benennen.performClick()
        val next = shadowOf(t).nextStartedActivity
        assertEquals(AusloeserActivity::class.java.name, next.component?.className)
        assertEquals("abc", next.getStringExtra(AusloeserActivity.EXTRA_TAG))
        assertTrue(t.isFinishing)

        val a = Robolectric.buildActivity(AusloeserActivity::class.java, next).setup().get()
        assertEquals("Tag benennen", a.findViewById<TextView>(R.id.ausloeser_name_titel).text.toString())
        assertEquals("no removing a tag HA may not have", android.view.View.GONE, a.findViewById<android.view.View>(R.id.ausloeser_entfernen).visibility)
        a.findViewById<EditText>(R.id.ausloeser_name).setText("Haustür")
        a.knopf().performClick()
        warte { a.knopf().isEnabled }
        val o = JSONObject(homes.anfragen.last { it.first == "POST /napi/ausloeser/tag" }.second)
        assertEquals("abc", o.getString("tag"))
        assertEquals("Haustür", o.getString("name"))
    }

    @Test
    fun aBlankTagIsOfferedForANewOne() {
        val a = bildschirm()
        a.ersteZeile().performClick() // renaming: the blank tag goes back to a new one
        schreiber.ergebnis!!(TagSchreiber.Ergebnis.Leer)
        shadowOf(Looper.getMainLooper()).idle()
        // the form opens by itself for it (owner 2026-11-27), asking its name
        assertEquals("Neuer Tag erkannt – wie soll er heißen?", a.findViewById<TextView>(R.id.ausloeser_name_titel).text.toString())
        assertEquals(android.view.View.VISIBLE, a.findViewById<android.view.View>(R.id.ausloeser_formular).visibility)
        assertTrue("the name asked for", a.findViewById<EditText>(R.id.ausloeser_name).hasFocus())
        assertEquals("Tag beschreiben", a.knopf().text.toString())
        schreiber.ergebnis!!(TagSchreiber.Ergebnis.Unlesbar)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Tag nicht lesbar – halte ihn ruhig noch einmal ans Telefon.", a.stand())
        assertTrue("nothing was sent", homes.anfragen.none { it.first.startsWith("POST") })
    }
}
