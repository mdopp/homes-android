package cloud.dopp.homes

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.sprachmodell.Karte
import cloud.dopp.homes.sprachmodell.SprachmodellClient
import cloud.dopp.homes.ui.HomeActivity
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The language model's card (owner, 2026-11-17): the app reads Homes'
 * `/napi/sprachmodell` card as the web page's words, draws it below the
 * favourites — the cost as a warning line, a button per duration, „Zurück zum
 * Haushalt“ —, asks Homes' question before a switch and posts only on its yes,
 * shows the new card or Homes' sentence under the row that was tapped, and
 * draws no card at all when the box has no switchable model (404). The
 * answers are shaped as Homes' handler writes them
 * (mdopp/homes feat/sprachmodell-schalter, internal/napi/sprachmodell.go).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SprachmodellTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private val server = MockWebServer()

    @After
    fun stop() = server.shutdown()

    private fun client(read: Long = 5) = SprachmodellClient(
        server.url("/").toString().trimEnd('/'), TOKEN,
        OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(read, TimeUnit.SECONDS).build(),
    )

    // --- the card's words ---------------------------------------------------------

    @Test
    fun theCardReadsAsHomesWritesIt() {
        val k = Karte.lesen(HAUSHALT)!!
        assertEquals("haushalt", k.zustand)
        assertEquals(TITEL, k.titel)
        assertEquals("Haushalt · Qwen3.6 35B A3B", k.zeile)
        assertEquals("Sprachsteuerung läuft", k.detail)
        assertEquals("", k.kosten)
        assertEquals("no button back in the household", "", k.zurueck)
        assertEquals(listOf("gemma-4-12b", "qwen3-coder-30b"), k.wahl.map { it.preset })
        val d = k.wahl.first().dauern
        assertEquals(listOf(1, 4), d.map { it.stunden })
        assertEquals(listOf("1 Std.", "4 Std."), d.map { it.label })
        assertEquals(FRAGE_1, d.first().frage)
        assertEquals("Umschalten", d.first().ja)
        assertEquals("Abbrechen", d.first().nein)
    }

    @Test
    fun anotherModelCarriesItsCostAndTheWayBack() {
        val k = Karte.lesen(ANDERES)!!
        assertEquals("Gemma 4 12B · für anna", k.zeile)
        assertEquals(KOSTEN, k.kosten)
        assertEquals(ZURUECK, k.zurueck)
        assertEquals("the model held is not offered again", listOf("qwen3-coder-30b"), k.wahl.map { it.preset })
    }

    @Test
    fun whatIsNoCardIsNone() {
        assertNull(Karte.lesen(null))
        assertNull(Karte.lesen("<html>"))
        assertNull("a refusal is no card", Karte.lesen("""{"ok":false,"error":"Nein."}"""))
        assertNull("no line, no card", Karte.lesen("""{"ok":true,"karte":{"titel":"Sprachmodell"}}"""))
        val ohne = Karte.lesen("""{"ok":true,"karte":{"zeile":"X","wahl":[{"preset":"p","dauern":[{"stunden":0,"label":"0"}]},{"name":"ohne"}]}}""")!!
        assertTrue("a preset without a usable duration offers nothing", ohne.wahl.isEmpty())
    }

    // --- the calls --------------------------------------------------------------

    @Test
    fun aReadAsksWithTheTokenAndA404IsNoCard() {
        server.enqueue(MockResponse().setBody(HAUSHALT))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"ok":false,"reason":"not_found"}"""))
        assertTrue(client().lesen() is SprachmodellClient.Lesen.Da)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/napi/sprachmodell", req.path)
        assertEquals("Bearer $TOKEN", req.getHeader("Authorization"))
        assertEquals(SprachmodellClient.Lesen.Keins, client().lesen())
    }

    @Test
    fun aSwitchPostsThePresetAndTheHours() {
        server.enqueue(MockResponse().setBody(ANDERES))
        val r = client().schalten("gemma-4-12b", 4)
        assertEquals(KOSTEN, (r as SprachmodellClient.Ergebnis.Neu).karte.kosten)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("gemma-4-12b", body.getString("preset"))
        assertEquals(4, body.getInt("stunden"))
        assertFalse("a switch sends no zurueck", body.has("zurueck"))
    }

    @Test
    fun givingBackPostsZurueckAlone() {
        server.enqueue(MockResponse().setBody(HAUSHALT))
        assertTrue(client().zurueck() is SprachmodellClient.Ergebnis.Neu)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(setOf("zurueck"), body.keys().asSequence().toSet())
        assertTrue(body.getBoolean("zurueck"))
    }

    @Test
    fun aRefusalCarriesHomesSentence() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"ok":false,"error":"$BELEGT"}"""))
        assertEquals(SprachmodellClient.Ergebnis.Abgelehnt(BELEGT), client().schalten("gemma-4-12b", 1))
    }

    @Test
    fun noAnswerInTimeAndNoLineAreSaidApart() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(SprachmodellClient.Ergebnis.Zeit, client(read = 1).schalten("gemma-4-12b", 1))
        server.shutdown()
        assertEquals(SprachmodellClient.Ergebnis.Offline, client().zurueck())
    }

    // --- the start screen -------------------------------------------------------

    /** Homes as the screen meets it: the card at GET, POST answering [post]; every other path 404. */
    private class Homes(val get: MockResponse, val post: () -> MockResponse) : Dispatcher() {
        val posts = CopyOnWriteArrayList<String>()
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path != "/napi/sprachmodell" -> MockResponse().setResponseCode(404)
            request.method == "POST" -> {
                posts += request.body.readUtf8()
                post()
            }
            else -> get
        }
    }

    private fun screen(homes: Homes): HomeActivity {
        server.dispatcher = homes
        server.start()
        // the plain test server as the paired Homes (ServerStore keeps https only)
        ctx().getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit()
            .putString("base_url", server.url("/").toString().trimEnd('/')).commit()
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), TOKEN)
        return Robolectric.buildActivity(HomeActivity::class.java).setup().visible().get()
    }

    private fun until(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < end) {
            ShadowLooper.idleMainLooper()
            if (ok()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("not within ${WAIT_MS / 1000} s: $what")
    }

    private fun card(a: HomeActivity): ViewGroup = a.findViewById(R.id.sprachmodell)

    private fun texts(v: View): List<String> = when (v) {
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        is TextView -> if (v.visibility == View.VISIBLE) listOf(v.text.toString()) else emptyList()
        else -> emptyList()
    }

    private fun button(a: HomeActivity, label: String, row: String? = null): Button {
        val all = ArrayList<Button>()
        fun walk(v: View) {
            if (v is Button) all += v
            if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) }
        }
        walk(card(a))
        return all.first { it.text == label && (row == null || (it.parent as ViewGroup).let { p -> texts(p).contains(row) }) }
    }

    @Test
    fun noSwitchableModelNoCard() {
        val a = screen(Homes(MockResponse().setResponseCode(404).setBody("""{"ok":false,"reason":"not_found"}""")) { MockResponse() })
        Thread.sleep(SETTLE_MS)
        ShadowLooper.idleMainLooper()
        assertEquals(View.GONE, card(a).visibility)
    }

    @Test
    fun theCardIsDrawnBelowTheFavouritesInHomesWords() {
        val a = screen(Homes(MockResponse().setBody(ANDERES)) { MockResponse() })
        until("the card") { card(a).visibility == View.VISIBLE }
        val shown = texts(card(a))
        listOf(TITEL, "Gemma 4 12B · für anna", KOSTEN, ZURUECK, "Qwen3 Coder 30B", "1 Std.", "4 Std.").forEach {
            assertTrue("$it on the card: $shown", it in shown)
        }
        val kosten = card(a).findViewById<TextView>(R.id.sm_unter)
        assertEquals("the cost is the warning line", KOSTEN, kosten.text.toString())
        assertEquals(ctx().getColor(R.color.homes_warn), kosten.currentTextColor)
        val group = card(a).parent as ViewGroup
        assertTrue("below the favourites", group.indexOfChild(card(a)) > group.indexOfChild(a.findViewById(R.id.devices_group)))
        preview(a, "sprachmodell")
    }

    @Test
    fun aDurationAsksFirstAndOnlyTheYesSwitches() {
        val homes = Homes(MockResponse().setBody(HAUSHALT)) { MockResponse().setBody(ANDERES).setBodyDelay(PRESS_MS, TimeUnit.MILLISECONDS) }
        val a = screen(homes)
        until("the card") { card(a).visibility == View.VISIBLE }

        button(a, "1 Std.", "Gemma 4 12B").performClick()
        ShadowLooper.idleMainLooper()
        var dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(FRAGE_1, dialog.findViewById<TextView>(R.id.ad_message).text.toString())
        assertEquals("Abbrechen", dialog.findViewById<Button>(R.id.ad_cancel).text.toString())
        dialog.findViewById<Button>(R.id.ad_cancel).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue("no yes, no switch", homes.posts.isEmpty())
        assertTrue("nothing pressed", button(a, "1 Std.", "Gemma 4 12B").isEnabled)

        button(a, "1 Std.", "Gemma 4 12B").performClick()
        ShadowLooper.idleMainLooper()
        dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val yes = dialog.findViewById<ViewGroup>(R.id.ad_items).getChildAt(0)
        assertTrue("Homes' yes", texts(yes).contains("Umschalten"))
        yes.performClick()
        ShadowLooper.idleMainLooper()
        // the pressed button answers at once, the others wait
        assertTrue("pressed: ${texts(card(a))}", ctx().getString(R.string.home_device_switching) in texts(card(a)))
        assertFalse(button(a, "4 Std.", "Gemma 4 12B").isEnabled)

        until("the new card") { KOSTEN in texts(card(a)) }
        assertEquals(1, homes.posts.size)
        assertEquals("gemma-4-12b", JSONObject(homes.posts.single()).getString("preset"))
        assertEquals(1, JSONObject(homes.posts.single()).getInt("stunden"))
        assertTrue(button(a, ZURUECK).isEnabled)
    }

    @Test
    fun aRefusalIsSaidWhereItWasTapped() {
        val homes = Homes(MockResponse().setBody(ANDERES)) {
            MockResponse().setResponseCode(502).setBody("""{"ok":false,"error":"$FEHLER"}""")
        }
        val a = screen(homes)
        until("the card") { card(a).visibility == View.VISIBLE }
        button(a, ZURUECK).performClick()
        until("Homes' sentence") { FEHLER in texts(card(a)) }
        val shown = texts(card(a))
        assertEquals("right under the button", shown.indexOf(ZURUECK) + 1, shown.indexOf(FEHLER))
        assertEquals(1, homes.posts.size)
        assertTrue("the button is there again", button(a, ZURUECK).isEnabled)
    }

    private fun preview(a: HomeActivity, name: String) {
        val v = card(a)
        val d = a.resources.displayMetrics.density
        val w = ((a.resources.configuration.screenWidthDp - 2 * GUTTER_DP) * d).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val pad = (GUTTER_DP * d).toInt()
        val bmp = Bitmap.createBitmap(w + 2 * pad, v.measuredHeight + 2 * pad, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(ctx().getColor(R.color.homes_bg))
        val canvas = Canvas(bmp)
        canvas.translate(pad.toFloat(), pad.toFloat())
        v.draw(canvas)
        listOfNotNull(File(RepoFiles.root, "app/build/widget-preview"), System.getenv("WIDGET_PREVIEW_DIR")?.let(::File)).forEach { dir ->
            dir.mkdirs()
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertNotNull(bmp)
    }

    companion object {
        private const val TOKEN = "homes_device_T"
        private const val TITEL = "Sprachmodell"
        private const val ZURUECK = "Zurück zum Haushalt"
        private const val KOSTEN = "Sprachsteuerung pausiert bis 21:30"
        private const val FRAGE_1 = "Auf Gemma 4 12B umschalten? Die Sprachsteuerung pausiert bis 15:30."
        private const val BELEGT = "Umschalten geht wieder, wenn llm-bench die Grafikkarte zurückgibt, spätestens 18:00."
        private const val FEHLER = "Das hat nicht geklappt: das Sprachmodell antwortet gerade nicht. Versuch es gleich noch einmal."
        private const val HINWEIS = "Ein anderes Modell braucht die Grafikkarte für sich: solange pausiert die Sprachsteuerung. " +
            "Am Ende schaltet Homes von selbst zurück zum Haushalt."
        private const val WAIT_MS = 10_000L
        private const val POLL_MS = 20L
        private const val SETTLE_MS = 1500L
        private const val PRESS_MS = 400L
        private const val GUTTER_DP = 24

        private fun dauern(name: String) = """[
            {"stunden":1,"label":"1 Std.","bis":"15:30","frage":"Auf $name umschalten? Die Sprachsteuerung pausiert bis 15:30.","ja":"Umschalten","nein":"Abbrechen"},
            {"stunden":4,"label":"4 Std.","bis":"18:30","frage":"Auf $name umschalten? Die Sprachsteuerung pausiert bis 18:30.","ja":"Umschalten","nein":"Abbrechen"}]"""

        /** `sprachmodell.Ansicht` in the household, as `Sehen` writes it. */
        private val HAUSHALT = """{"ok":true,"karte":{"zustand":"haushalt","titel":"$TITEL","hinweis":"$HINWEIS",
            "zeile":"Haushalt · Qwen3.6 35B A3B","detail":"Sprachsteuerung läuft","kosten":"","zurueck":"",
            "wahl":[{"preset":"gemma-4-12b","name":"Gemma 4 12B","dauern":${dauern("Gemma 4 12B")}},
                    {"preset":"qwen3-coder-30b","name":"Qwen3 Coder 30B","dauern":${dauern("Qwen3 Coder 30B")}}]}}"""

        /** Another model for a person of Homes: its cost, the way back, the other preset. */
        private val ANDERES = """{"ok":true,"karte":{"zustand":"anderes","titel":"$TITEL","hinweis":"$HINWEIS",
            "zeile":"Gemma 4 12B · für anna","detail":"","kosten":"$KOSTEN","zurueck":"$ZURUECK",
            "wahl":[{"preset":"qwen3-coder-30b","name":"Qwen3 Coder 30B","dauern":${dauern("Qwen3 Coder 30B")}}]}}"""
    }
}
