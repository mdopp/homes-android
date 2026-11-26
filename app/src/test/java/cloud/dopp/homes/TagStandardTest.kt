package cloud.dopp.homes

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.ausloeser.Ausloeser
import cloud.dopp.homes.ausloeser.AusloeserActivity
import cloud.dopp.homes.ausloeser.AusloeserClient
import cloud.dopp.homes.ausloeser.AusloeserClient.Ausgang
import cloud.dopp.homes.ausloeser.NfcSchreiber
import cloud.dopp.homes.ausloeser.TagActivity
import cloud.dopp.homes.ausloeser.TagAutomationen
import cloud.dopp.homes.ausloeser.TagSchreiber
import cloud.dopp.homes.data.TokenStore
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
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The tag's own address and what a tag can do (homes ADR 0004 §5, §8, owner
 * 2026-11-26): the box's https address touched (at once) or opened as a link
 * (only after „Auslösen“), the scanner's question opening Homes' Scannen, and
 * a tag's automations — linked, loosed, a new one — each asked first, shown
 * as Homes' sentence in the one dialog and only then written; a non-admin's
 * waits for an admin, asked after while the screen is open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TagStandardTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private companion object {
        const val TAG = "homes-aaaaaaaaaaaa"
        const val TAGS = """{"ok":true,"tags":[{"tag":"$TAG","name":"Bürotür","aktion":"",""" +
            """"automationen":[{"id":"gute_nacht","name":"Gute Nacht"}]}],"vorschlaege":[],""" +
            """"aktionen":[{"id":"","titel":"Home Assistant melden"},{"id":"scanner-fragen","titel":"Scanner – fragen, für wen"}]}"""
        const val AUTOS = """{"ok":true,"automationen":[{"id":"gute_nacht","name":"Gute Nacht","an":true},{"id":"licht_aus","name":"Licht aus","an":false}]}"""
        const val SATZ = "Wenn jemand ‚Bürotür‘ berührt, startet ‚Licht aus‘."
    }

    /** Homes' side of §8: the first call says the sentence, the confirmed one what came of it. */
    private inner class Homes : Dispatcher() {
        var melden: () -> MockResponse = { MockResponse().setBody("""{"ok":true,"name":"Bürotür"}""") }
        var frage: () -> MockResponse = { MockResponse().setBody("""{"ok":true,"satz":"$SATZ","bestaetigen":true}""") }
        var ja: () -> MockResponse = { MockResponse().setBody("""{"ok":true,"satz":"Verbunden: ‚Bürotür‘ startet jetzt ‚Licht aus‘."}""") }
        var freigabe: () -> MockResponse = { MockResponse().setBody("""{"ok":true,"stand":"wartet","satz":"Wartet auf Freigabe.","wunsch":"x"}""") }
        val anfragen = CopyOnWriteArrayList<Pair<String, String>>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = request.body.readUtf8()
            val pfad = request.path.orEmpty()
            anfragen += "${request.method} $pfad" to body
            return when {
                pfad == "/napi/ausloeser" -> melden()
                pfad == "/napi/ausloeser/tags" -> MockResponse().setBody(TAGS)
                pfad.startsWith("/napi/automationen") -> MockResponse().setBody(AUTOS)
                pfad.startsWith("/napi/ausloeser/freigabe/") -> freigabe()
                pfad.startsWith("/napi/ausloeser/tag/$TAG/") -> if (JSONObject(body).optBoolean("bestaetigt")) ja() else frage()
                else -> MockResponse().setResponseCode(404)
            }
        }

        fun letzte(teil: String): JSONObject = JSONObject(anfragen.last { it.first.contains(teil) }.second)
    }

    private val homes = Homes()
    private val server = MockWebServer().also { it.dispatcher = homes }
    private val base get() = server.url("/").toString().trimEnd('/')
    private val host get() = server.url("/").host

    private class Schreiber : TagSchreiber {
        override fun bereit(ctx: Context) = TagSchreiber.Bereit.JA
        override fun an(activity: Activity, uri: () -> String?, ergebnis: (TagSchreiber.Ergebnis) -> Unit) = Unit
        override fun aus(activity: Activity) = Unit
    }

    @Before
    fun gekoppelt() {
        ctx().getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit().putString("base_url", base).commit()
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_TESTTOKEN123")
        AusloeserActivity.schreiber = Schreiber()
    }

    @After
    fun zu() {
        AusloeserActivity.schreiber = NfcSchreiber
        ctx().getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit().clear().commit()
        ctx().getSharedPreferences("ausloeser", Context.MODE_PRIVATE).edit().clear().commit()
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

    // --- §5: the box's own address ---------------------------------------------------------

    @Test
    fun theBoxsAddressIsATagOfThePairedBoxOnly() {
        assertEquals(TAG, Ausloeser.tagAus("https://www.dopp.cloud/tag/$TAG", "www.dopp.cloud"))
        assertEquals(TAG, Ausloeser.tagAus("https://WWW.DOPP.CLOUD/tag/$TAG/", "www.dopp.cloud"))
        assertNull("another box's", Ausloeser.tagAus("https://www.example.org/tag/$TAG", "www.dopp.cloud"))
        assertNull("unpaired: no box's", Ausloeser.tagAus("https://www.dopp.cloud/tag/$TAG"))
        assertNull("a wish's page is no tag", Ausloeser.tagAus("https://www.dopp.cloud/tag/freigabe/f1", "www.dopp.cloud"))
        assertNull("http", Ausloeser.tagAus("http://www.dopp.cloud/tag/$TAG", "www.dopp.cloud"))
        // the older forms keep working
        assertEquals("abc", Ausloeser.tagAus("cloud.dopp.homes://tag/abc", "www.dopp.cloud"))
        assertEquals("abc", Ausloeser.tagAus("https://www.home-assistant.io/tag/abc", "www.dopp.cloud"))
    }

    private fun oeffne(action: String, uri: String): TagActivity =
        Robolectric.buildActivity(TagActivity::class.java, Intent(action, Uri.parse(uri))).setup().get()

    private fun TagActivity.zeile() = findViewById<TextView>(R.id.tag_zeile).text.toString()

    @Test
    fun theBoxsAddressTouchedIsReportedAtOnce() {
        homes.melden = { MockResponse().setBody("""{"ok":true,"name":"Bürotür"}""").setBodyDelay(300, TimeUnit.MILLISECONDS) }
        val t = oeffne(NfcAdapter.ACTION_NDEF_DISCOVERED, "https://$host/tag/$TAG")
        assertEquals("running at once", "Meldet …", t.zeile())
        warte { t.zeile() != "Meldet …" }
        assertEquals("Bürotür – gemeldet", t.zeile())
        assertEquals(TAG, homes.letzte("POST /napi/ausloeser").getString("tag"))
    }

    @Test
    fun theBoxsAddressAsALinkActsOnlyOnAusloesen() {
        val t = oeffne(Intent.ACTION_VIEW, "https://$host/tag/$TAG")
        assertEquals("Ein Link zu einem Homes-Tag.", t.zeile())
        val knopf = t.findViewById<Button>(R.id.tag_ausloesen)
        assertEquals(View.VISIBLE, knopf.visibility)
        assertEquals("Auslösen", knopf.text.toString())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("a link reports nothing by itself", homes.anfragen.none { it.first == "POST /napi/ausloeser" })
        knopf.performClick()
        assertEquals(View.GONE, knopf.visibility)
        warte { t.zeile() == "Bürotür – gemeldet" }
        assertEquals("Bürotür – gemeldet", t.zeile())
        assertEquals(1, homes.anfragen.count { it.first == "POST /napi/ausloeser" })
    }

    @Test
    fun aLinkNotTappedCloses() {
        val t = oeffne(Intent.ACTION_VIEW, "https://$host/tag/$TAG")
        shadowOf(Looper.getMainLooper()).idleFor(TagActivity.BENENNEN_MS, TimeUnit.MILLISECONDS)
        assertTrue(t.isFinishing)
        assertTrue(homes.anfragen.none { it.first == "POST /napi/ausloeser" })
    }

    // --- §8: the scanner asks for whom ----------------------------------------------------------

    @Test
    fun theScannersQuestionOpensHomesScannen() {
        homes.melden = {
            MockResponse().setBody(
                """{"ok":true,"name":"Scanner","aktion":"scanner-fragen","text":"Wähle auf Homes, für wen gescannt wird.",""" +
                    """"oeffnen":"https://$host/dokumente#scannen"}""",
            )
        }
        val t = oeffne(NfcAdapter.ACTION_NDEF_DISCOVERED, "https://$host/tag/$TAG")
        warte { t.isFinishing }
        assertTrue(t.isFinishing)
        assertEquals("Wähle auf Homes, für wen gescannt wird.", ShadowToast.getTextOfLatestToast())
        val geoeffnet = shadowOf(t).nextStartedActivity
        assertEquals("https://$host/dokumente#scannen", geoeffnet.dataString)
    }

    @Test
    fun anAddressOffTheBoxIsNotOpened() {
        homes.melden = {
            MockResponse().setBody("""{"ok":true,"name":"Scanner","aktion":"scanner-fragen","text":"Wähle auf Homes.","oeffnen":"https://evil.example/x"}""")
        }
        val t = oeffne(NfcAdapter.ACTION_NDEF_DISCOVERED, "https://$host/tag/$TAG")
        warte { t.zeile() != "Meldet …" }
        assertEquals("Wähle auf Homes.", t.zeile())
        assertNull(shadowOf(t).nextStartedActivity)
        assertNull(Ausloeser.aufHomes("https://evil.example/x", base))
        assertNull("only https", Ausloeser.aufHomes("http://$host/x", base))
    }

    // --- §8: the client ---------------------------------------------------------------------

    private fun client(http: OkHttpClient = OkHttpClient()) = AusloeserClient(base, "homes_device_T", http)

    @Test
    fun theClientsCallsAsTheContractSays() {
        val tags = client().tags() as Ausgang.Tags
        assertEquals(listOf(AusloeserClient.Automation("gute_nacht", "Gute Nacht")), tags.tags.single().automationen)
        val autos = client().automationen("Licht") as Ausgang.Automationen
        assertEquals(listOf(true, false), autos.automationen.map { it.an })
        assertEquals("GET /napi/automationen?q=Licht", homes.anfragen.last().first)
        client().automationen(" ")
        assertEquals("an empty search asks for all", "GET /napi/automationen", homes.anfragen.last().first)

        val frage = client().aendern(AusloeserClient.Art.VERBINDEN, TAG, "licht_aus", null, bestaetigt = false)
        assertEquals(Ausgang.Aenderung(SATZ, bestaetigen = true), frage)
        val o = homes.letzte("/verbinden")
        assertEquals("licht_aus", o.getString("automation"))
        assertFalse("the first call writes nothing", o.has("bestaetigt"))
        client().aendern(AusloeserClient.Art.NEU, TAG, null, "Licht an", bestaetigt = true)
        val neu = homes.letzte("/neu")
        assertEquals("Licht an", neu.getString("satz"))
        assertTrue(neu.getBoolean("bestaetigt"))

        homes.freigabe = { MockResponse().setBody("""{"ok":true,"stand":"abgelehnt","satz":"Michael hat es nicht freigegeben.","wunsch":"w"}""") }
        assertEquals(Ausgang.Freigabe(false, "Michael hat es nicht freigegeben.", "w"), client().freigabe("f1"))
        assertEquals("GET /napi/ausloeser/freigabe/f1", homes.anfragen.last().first)
    }

    @Test
    fun aChangeTooLateOrRefusedIsSaid() {
        homes.frage = { MockResponse().setBody("""{"ok":true,"satz":"x"}""").setBodyDelay(2, TimeUnit.SECONDS) }
        val kurz = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        val zeit = client(kurz).aendern(AusloeserClient.Art.LOESEN, TAG, "gute_nacht", null, false)
        assertEquals(Ausgang.Zeit, zeit)
        assertTrue(Ausloeser.fehler(zeit)!!.contains("nicht rechtzeitig"))
        homes.frage = { MockResponse().setResponseCode(409).setBody("""{"ok":false,"error":"‚Bürotür‘ ist schon mit ‚Licht aus‘ verbunden."}""") }
        assertEquals("‚Bürotür‘ ist schon mit ‚Licht aus‘ verbunden.", Ausloeser.fehler(client().aendern(AusloeserClient.Art.VERBINDEN, TAG, "licht_aus", null, false)))
    }

    // --- §8: the Auslöser screen ------------------------------------------------------------

    private fun bildschirm(): AusloeserActivity {
        val a = Robolectric.buildActivity(AusloeserActivity::class.java).setup().get()
        val liste = a.findViewById<LinearLayout>(R.id.ausloeser_liste)
        warte { (liste.getChildAt(0) as? TextView)?.text?.startsWith("Bürotür") == true }
        return a
    }

    private fun AusloeserActivity.zeile(): TextView = findViewById<LinearLayout>(R.id.ausloeser_liste).getChildAt(0) as TextView
    private fun AusloeserActivity.autoStand() = findViewById<TextView>(R.id.ausloeser_auto_stand).text.toString()
    private fun AusloeserActivity.knopfMit(id: Int, text: String): Button {
        val g = findViewById<LinearLayout>(id)
        return (0 until g.childCount).map { g.getChildAt(it) }.filterIsInstance<Button>().first { it.text.toString() == text }
    }

    private fun dialog(): AlertDialog = ShadowDialog.getLatestDialog() as AlertDialog

    private fun AlertDialog.waehle(text: String) {
        val stapel = findViewById<LinearLayout>(R.id.ad_items)!!
        (0 until stapel.childCount).map { stapel.getChildAt(it) }
            .first { it.findViewById<TextView>(R.id.ar_label)?.text?.toString() == text }.performClick()
    }

    @Test
    fun theListSaysWhatATagStarts() {
        val a = bildschirm()
        assertEquals("Bürotür\nstartet: Gute Nacht", a.zeile().text.toString())
        assertEquals("no tag changed: no automations card", View.GONE, a.findViewById<View>(R.id.ausloeser_automationen).visibility)
    }

    @Test
    fun loosingAnAutomationIsAskedThenConfirmed() {
        homes.frage = { MockResponse().setBody("""{"ok":true,"satz":"‚Bürotür‘ startet dann ‚Gute Nacht‘ nicht mehr.","bestaetigen":true}""") }
        homes.ja = { MockResponse().setBody("""{"ok":true,"satz":"Gelöst: ‚Bürotür‘ startet ‚Gute Nacht‘ nicht mehr."}""").setBodyDelay(300, TimeUnit.MILLISECONDS) }
        val a = bildschirm()
        a.zeile().performClick()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.ausloeser_automationen).visibility)
        val loesen = a.knopfMit(R.id.ausloeser_auto_liste, "Lösen")
        loesen.performClick()
        assertEquals("running at once", "Fragt Homes …", a.autoStand())
        assertFalse(a.findViewById<Button>(R.id.ausloeser_auto_neu).isEnabled)
        warte { ShadowDialog.getLatestDialog()?.isShowing == true }
        val d = dialog()
        assertEquals("‚Bürotür‘ startet dann ‚Gute Nacht‘ nicht mehr.", d.findViewById<TextView>(R.id.ad_message)!!.text.toString())
        assertTrue("nothing written before the yes", homes.anfragen.none { it.first.endsWith("/loesen") && it.second.contains("bestaetigt") })
        d.waehle("Ja, so machen")
        assertEquals("Speichert …", a.autoStand())
        warte { a.autoStand().startsWith("Gelöst") }
        assertEquals("Gelöst: ‚Bürotür‘ startet ‚Gute Nacht‘ nicht mehr.", a.autoStand())
        assertEquals("gute_nacht", homes.letzte("/loesen").getString("automation"))
        assertTrue(homes.letzte("/loesen").getBoolean("bestaetigt"))
        assertTrue(a.findViewById<Button>(R.id.ausloeser_auto_neu).isEnabled)
    }

    @Test
    fun theSearchListsTheOnesNotLinkedYet() {
        val a = bildschirm()
        a.zeile().performClick()
        a.findViewById<Button>(R.id.ausloeser_auto_verbinden).performClick()
        assertEquals(View.VISIBLE, a.findViewById<EditText>(R.id.ausloeser_auto_suche).visibility)
        val treffer = a.findViewById<LinearLayout>(R.id.ausloeser_auto_treffer)
        warte { treffer.childCount == 1 && treffer.getChildAt(0) is Button }
        assertEquals("the linked one is left out, an off one says so", "Licht aus (aus)", (treffer.getChildAt(0) as Button).text.toString())
    }

    @Test
    fun aNonAdminsLinkWaitsForAnAdminAndIsAskedAfter() {
        homes.frage = { MockResponse().setBody("""{"ok":true,"satz":"$SATZ","bestaetigen":true,"freigabe_noetig":true}""") }
        homes.ja = { MockResponse().setBody("""{"ok":true,"satz":"Wartet auf Freigabe: $SATZ","wartet":true,"freigabe":"f1"}""") }
        val a = bildschirm()
        a.zeile().performClick()
        a.findViewById<Button>(R.id.ausloeser_auto_verbinden).performClick()
        val treffer = a.findViewById<LinearLayout>(R.id.ausloeser_auto_treffer)
        warte { treffer.childCount == 1 && treffer.getChildAt(0) is Button }
        treffer.getChildAt(0).performClick()
        warte { ShadowDialog.getLatestDialog()?.isShowing == true }
        val d = dialog()
        assertTrue("the dialog says the admins decide", d.findViewById<TextView>(R.id.ad_message)!!.text.contains("entscheidet ein Admin"))
        d.waehle("Um Freigabe bitten")
        warte { a.autoStand().startsWith("Wartet auf Freigabe") }
        assertEquals(mapOf("f1" to TAG), Ausloeser.wartende(ctx()))
        warte { a.zeile().text.contains("Wartet auf Freigabe") }
        assertEquals("Bürotür\nstartet: Gute Nacht\nWartet auf Freigabe", a.zeile().text.toString())

        // still waiting: asked again while the screen is open, nothing changes
        shadowOf(Looper.getMainLooper()).idleFor(TagAutomationen.NACHSEHEN_MS, TimeUnit.MILLISECONDS)
        warte { homes.anfragen.any { it.first == "GET /napi/ausloeser/freigabe/f1" } }
        assertEquals(mapOf("f1" to TAG), Ausloeser.wartende(ctx()))

        homes.freigabe = { MockResponse().setBody("""{"ok":true,"stand":"freigegeben","satz":"Verbunden: ‚Bürotür‘ startet jetzt ‚Licht aus‘.","wunsch":"x"}""") }
        shadowOf(Looper.getMainLooper()).idleFor(TagAutomationen.NACHSEHEN_MS, TimeUnit.MILLISECONDS)
        warte { a.autoStand().startsWith("Verbunden") }
        assertEquals("Verbunden: ‚Bürotür‘ startet jetzt ‚Licht aus‘.", a.autoStand())
        assertTrue("it waits no more", Ausloeser.wartende(ctx()).isEmpty())
    }

    @Test
    fun noWishIsAskedAfterWhileTheScreenIsAway() {
        Ausloeser.wartet(ctx(), "f9", TAG)
        val c = Robolectric.buildActivity(AusloeserActivity::class.java).setup()
        warte { homes.anfragen.any { it.first == "GET /napi/ausloeser/freigabe/f9" } }
        c.pause()
        val vorher = homes.anfragen.count { it.first.startsWith("GET /napi/ausloeser/freigabe/") }
        shadowOf(Looper.getMainLooper()).idleFor(TagAutomationen.NACHSEHEN_MS * 3, TimeUnit.MILLISECONDS)
        Thread.sleep(200)
        assertEquals("no background poll", vorher, homes.anfragen.count { it.first.startsWith("GET /napi/ausloeser/freigabe/") })
    }

    @Test
    fun anAdminsNewAutomationShowsTheBotesReply() {
        homes.frage = { MockResponse().setBody("""{"ok":true,"satz":"Neue Automation für ‚Bürotür‘: Licht an.","bestaetigen":true}""") }
        homes.ja = { MockResponse().setBody("""{"ok":true,"satz":"Der Bote entwirft sie.","bote":"Entwurf: Licht an. Bestätige mit dem Code 4711."}""") }
        val a = bildschirm()
        a.zeile().performClick()
        a.findViewById<Button>(R.id.ausloeser_auto_neu).performClick()
        a.findViewById<Button>(R.id.ausloeser_auto_weiter).performClick()
        assertEquals("idle without a sentence", "Sag zuerst in einem Satz, was passieren soll.", a.autoStand())
        a.findViewById<EditText>(R.id.ausloeser_auto_satz).setText("Licht an")
        a.findViewById<Button>(R.id.ausloeser_auto_weiter).performClick()
        warte { ShadowDialog.getLatestDialog()?.isShowing == true }
        dialog().waehle("Ja, so machen")
        warte { a.autoStand().contains("4711") }
        assertEquals("Der Bote entwirft sie.\n\nEntwurf: Licht an. Bestätige mit dem Code 4711.", a.autoStand())
        assertEquals("Licht an", homes.letzte("/neu").getString("satz"))
    }

    @Test
    fun aRefusedChangeIsSaidAndNoDialogComes() {
        homes.frage = { MockResponse().setResponseCode(409).setBody("""{"ok":false,"error":"Diese Automation kommt aus YAML; der Editor kann sie nicht ändern."}""") }
        val a = bildschirm()
        a.zeile().performClick()
        val vorher = ShadowDialog.getLatestDialog()
        a.knopfMit(R.id.ausloeser_auto_liste, "Lösen").performClick()
        warte { a.autoStand() != "Fragt Homes …" }
        assertEquals("Diese Automation kommt aus YAML; der Editor kann sie nicht ändern.", a.autoStand())
        assertTrue(ShadowDialog.getLatestDialog() === vorher)
        assertTrue("the buttons are back", a.knopfMit(R.id.ausloeser_auto_liste, "Lösen").isEnabled)
    }
}
