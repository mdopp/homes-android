package cloud.dopp.homes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.Assistent
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.kann.Anfordern
import cloud.dopp.homes.kann.Anfrage
import cloud.dopp.homes.kann.KannStore
import cloud.dopp.homes.ui.HomeActivity
import cloud.dopp.homes.ui.StartDevices
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The start screen on a box that lacks things (homes ADR 0001 §10;
 * homes-android#231): Mehr's quiet section with a trace in each state, the
 * widget list's muted entries, and „Hier anfordern“ pressed — running, then
 * done or refused. Drawn into `build/widget-preview/app-<name>.png` (and
 * `$WIDGET_PREVIEW_DIR`), to be looked at; demo data only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class KannPreviewTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private val server = MockWebServer()

    @After
    fun stop() {
        server.shutdown()
        KannStore.clear(ctx())
        Anfordern.clear()
    }

    /** Homes as the demo box answers: `/napi/kann` the demo, a wish for podcasts taken, one for standort refused late. */
    private fun homes() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/napi/kann" -> MockResponse().setBody(DEMO).setHeader("ETag", "\"demo\"")
                    path == "/napi/kann/anfordern" && request.body.readUtf8().contains("podcasts") ->
                        MockResponse().setBody(modul("podcasts").put("spur_id", "angefordert").put("spur", "angefordert").put("anfordern", false).toString())
                    path == "/napi/kann/anfordern" -> MockResponse().setResponseCode(502)
                        .setBody("""{"ok":false,"error":"Die Anfrage ließ sich gerade nicht aufschreiben. Versuch es später noch einmal."}""")
                        .setHeadersDelay(400, TimeUnit.MILLISECONDS)
                    else -> MockResponse().setResponseCode(404).setBody("""{"ok":false,"reason":"not_found"}""")
                }
            }
        }
        server.start()
    }

    private fun paired(): HomeActivity {
        homes()
        ctx().getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit()
            .putString("base_url", server.url("/").toString().trimEnd('/')).commit()
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_TESTTOKEN123")
        Assistent.save(ctx(), "Homes")
        StartDevices.save(ctx(), StartPreviewTest.FAVORITES)
        KannStore.neu(ctx(), DEMO, "\"demo\"")
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return a
    }

    private fun texts(v: View): List<String> = when (v) {
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        is TextView -> if (v.visibility == View.VISIBLE && v.isShown) listOf(v.text.toString()) else emptyList()
        else -> emptyList()
    }

    @Test
    fun drawTheTracesInEveryState() {
        val a = paired()
        val mehr = texts(a.findViewById(R.id.mehr_group))
        assertFalse("Standortverlauf's row gave way to its trace: $mehr", "Standortverlauf" in mehr)
        assertTrue("Homes auf dem Handy stays", mehr.isNotEmpty())
        val section = a.findViewById<View>(R.id.spuren_section)
        assertEquals(View.VISIBLE, section.visibility)
        val spuren = texts(a.findViewById(R.id.spuren_group))
        listOf(
            "auf dieser Box nicht eingerichtet", "ausgeschaltet", "angefordert", "wartet auf Freigabe", "abgelehnt",
            "wird eingerichtet", "Hier anfordern",
        ).forEach { assertTrue("$it missing in $spuren", it in spuren) }
        assertFalse("the admins' trace is on Homes' Box page", "Werkstatt" in spuren)
        val widgets = texts(a.findViewById(R.id.widgets_group))
        assertTrue("energy is a muted entry: $widgets", "Energie" in widgets || widgets.any { it.startsWith("Energie") })
        // the Dokumente and Notizen widgets went (owner 2026-11-27): no entry, not even a muted one
        assertFalse("dokumente's tool is no entry any more: $widgets", "Dokumente" in widgets || "Notizen" in widgets)
        draw(a, "app-start-spuren", content(a))
        draw(a, "app-mehr-spuren", section)
        draw(a, "app-picker-spur", a.findViewById(R.id.widgets_card))
    }

    @Test
    fun drawARequestRunningThenRefused() {
        val a = paired()
        press(a, "Standortverlauf")
        assertEquals(Anfrage.Laeuft, Anfordern.stand("standort"))
        assertTrue("Wird angefordert …" in texts(a.findViewById(R.id.spuren_group)))
        draw(a, "app-anfordern-laeuft", a.findViewById(R.id.spuren_section))
        settle("standort")
        assertTrue(Anfordern.stand("standort") is Anfrage.Abgelehnt)
        assertTrue(texts(a.findViewById(R.id.spuren_group)).any { it.startsWith("Die Anfrage ließ sich gerade nicht") })
        draw(a, "app-anfordern-fehler", a.findViewById(R.id.spuren_section))
    }

    @Test
    fun drawARequestDone() {
        val a = paired()
        press(a, "Podcasts")
        settle("podcasts")
        assertEquals(Anfrage.Fertig, Anfordern.stand("podcasts"))
        assertEquals("angefordert", KannStore.kann(ctx())!!.module.single { it.name == "podcasts" }.spurId)
        draw(a, "app-anfordern-fertig", a.findViewById(R.id.spuren_section))
    }

    /** Press „Hier anfordern“ on the trace titled [titel]. */
    private fun press(a: HomeActivity, titel: String) {
        val group = a.findViewById<ViewGroup>(R.id.spuren_group)
        val row = (0 until group.childCount).map { group.getChildAt(it) }
            .single { it.findViewById<TextView>(R.id.spur_titel).text == titel }
        row.findViewById<Button>(R.id.spur_anfordern).performClick()
    }

    private fun settle(modul: String) {
        val until = System.currentTimeMillis() + 15_000
        while (Anfordern.stand(modul) == Anfrage.Laeuft && System.currentTimeMillis() < until) Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun content(a: HomeActivity): View = a.findViewById<ViewGroup>(R.id.home_root).getChildAt(0)

    private fun draw(a: HomeActivity, name: String, view: View) {
        val root = content(a)
        val w = (411 * a.resources.displayMetrics.density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        assertTrue(view.width > 0 && view.height > 0)
        val pad = if (view === root) 0 else (12 * a.resources.displayMetrics.density).toInt()
        val bmp = Bitmap.createBitmap(view.width + 2 * pad, view.height + 2 * pad, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(ctx().getColor(R.color.terminal_ground))
        val c = Canvas(bmp)
        c.translate(pad.toFloat(), pad.toFloat())
        view.draw(c)
        listOfNotNull(File(RepoFiles.root, "app/build/widget-preview"), System.getenv("WIDGET_PREVIEW_DIR")?.let(::File)).forEach { dir ->
            dir.mkdirs()
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    companion object {
        private fun off(name: String, titel: String, spur: String, words: String, anfordern: Boolean, grund: String, widgets: String = "", mehr: String = "") =
            """{"name":"$name","titel":"$titel","an":false,"warum":"fehlt","grund":"$grund",
               "bietet":{"widgets":[$widgets],"teilen":[],"konto":[],"sicherung":[],"mehr":[$mehr]},
               "spur_id":"$spur","spur":"$words","anfordern":$anfordern}"""

        /** A demo box: devices and notes on; energy, documents and the rest off, a trace in each state. */
        val DEMO = """{"version":1,"module":[
            {"name":"geraete","titel":"Geräte","an":true,"backend":"home-assistant","bietet":{"widgets":["geraet"],"teilen":[],"konto":[],"sicherung":[],"mehr":[]}},
            ${off("energie", "Energie", "fehlt", "auf dieser Box nicht eingerichtet", true, "Auf dieser Box gibt es keine Energiezähler.", widgets = "\"energie\"")},
            {"name":"notizen","titel":"Notizen","an":true,"bietet":{"widgets":["tool:note"],"teilen":["merken"],"konto":[],"sicherung":[],"mehr":[]}},
            ${off("dokumente", "Dokumente", "aus", "ausgeschaltet", true, "Auf dieser Box ausgeschaltet.", widgets = "\"tool:doc\"")},
            ${off("standort", "Standortverlauf", "fehlt", "auf dieser Box nicht eingerichtet", true, "Auf dieser Box läuft kein Reitti.", mehr = "\"standortverlauf\"")},
            ${off("sicherung", "Daten sichern", "wartet", "wartet auf Freigabe", false, "Auf dieser Box gibt es kein Archiv für Nachrichten.", mehr = "\"daten-sichern\"")},
            ${off("podcasts", "Podcasts", "fehlt", "auf dieser Box nicht eingerichtet", true, "Auf dieser Box läuft kein Audiobookshelf.")},
            ${off("gesehen", "Gesehen & Gehört", "abgelehnt", "abgelehnt", true, "Auf dieser Box laufen weder Jellyfin noch Audiobookshelf.")},
            ${off("einkauf", "Einkauf", "angefordert", "angefordert", false, "Auf dieser Box läuft kein Radicale.")},
            ${off("musik", "Musik", "eingerichtet", "wird eingerichtet", false, "Auf dieser Box gibt es keinen Musik-Eingang.")},
            {"name":"werkstatt","titel":"Werkstatt","an":false,"warum":"fehlt","grund":"Keine Werkstatt.","admins":true,"spur_id":"fehlt","anfordern":true,
             "bietet":{"widgets":[],"teilen":[],"konto":[],"sicherung":[],"mehr":[]}}],
            "app":{"widgets":["geraet","tool:note"],"teilen":["merken"],"konto":["kontakte","kalender"],"sicherung":["kontakte"],"mehr":[]}}"""

        fun modul(name: String): JSONObject = JSONObject(DEMO).getJSONArray("module").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.single { it.getString("name") == name }
        }
    }
}
