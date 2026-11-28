package cloud.dopp.homes

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.kann.KannStore
import cloud.dopp.homes.ui.HomeActivity
import cloud.dopp.homes.ui.StartAngebot
import cloud.dopp.homes.widget.EnergyWidgetProvider
import cloud.dopp.homes.widget.WidgetTypes
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

/**
 * The energy as a link (owner 2026-11-28: „Energie brauche ich auch nicht
 * als Widget sondern nur als Link zur Anzeige.“): no energy widget in the
 * picker, a placed one says it is gone and reads nothing, and the start
 * screen's „Energie“ tile opens Homes' Energie page with the widget's numbers
 * as its line — only while the box has the energy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnergieKachelTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val anfragen = CopyOnWriteArrayList<String>()
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                anfragen += request.path.orEmpty()
                return when (request.path) {
                    "/napi/portal/energy" -> MockResponse().setBody(ENERGY)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }
    private val base get() = server.url("/").toString().trimEnd('/')

    @Before
    fun gekoppelt() {
        ctx.getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit().putString("base_url", base).commit()
        TokenStore.backend = ctx.getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx, "homes_device_TESTTOKEN123")
        @Suppress("DEPRECATION")
        shadowOf(ctx.getSystemService(PowerManager::class.java)).setIsInteractive(true)
    }

    @After
    fun zu() {
        ctx.getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit().clear().commit()
        listOf("start_energie", "start_bereiche").forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        TokenStore.clear(ctx)
        KannStore.clear(ctx)
        runCatching { server.shutdown() }
    }

    private fun kann(vararg widgets: String) =
        KannStore.neu(ctx, """{"version":1,"module":[],"app":{"widgets":[${widgets.joinToString(",") { "\"$it\"" }}],"mehr":[]}}""", null)

    @Test
    fun theEnergyWidgetIsNoLongerOffered() {
        assertEquals("„Widget hinzufügen“: only „Ein Gerät“ (and the tools)", listOf("geraet"), WidgetTypes.ALL.map { it.key })
        assertFalse(StartAngebot.widget("energie"))
    }

    @Test
    fun aPlacedEnergyWidgetSaysToRemoveItAndReadsNothing() {
        val mgr = AppWidgetManager.getInstance(ctx)
        val id = shadowOf(mgr).createWidget(EnergyWidgetProvider::class.java, R.layout.widget_error)
        EnergyWidgetProvider().onUpdate(ctx, mgr, intArrayOf(id))
        assertEquals(
            "Dieses Widget gibt es nicht mehr – lange drücken und entfernen.",
            shadowOf(mgr).getViewFor(id).findViewById<TextView>(R.id.we_text).text.toString(),
        )
        Thread.sleep(300)
        assertTrue("no network: $anfragen", anfragen.isEmpty())
    }

    private fun start(): HomeActivity {
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return a
    }

    private fun kachel(a: HomeActivity, name: String): View? {
        val g = a.findViewById<ViewGroup>(R.id.ueberblick_group)
        return (0 until g.childCount).flatMap { r -> (g.getChildAt(r) as ViewGroup).let { z -> (0 until z.childCount).map { z.getChildAt(it) } } }
            .firstOrNull { it.findViewById<TextView>(R.id.bereich_name)?.text?.toString() == name }
    }

    private fun warte(bis: () -> Boolean) {
        val ende = System.currentTimeMillis() + 10_000
        while (!bis() && System.currentTimeMillis() < ende) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun theTileSaysTheWidgetsNumbersAndOpensTheEnergyPage() {
        kann("geraet", "energie")
        val a = start()
        warte { kachel(a, "Energie")?.findViewById<TextView>(R.id.bereich_stand)?.text?.startsWith("Haus") == true }
        val k = kachel(a, "Energie")!!
        assertEquals("Haus 271 W · heute 8,2 kWh erzeugt", k.findViewById<TextView>(R.id.bereich_stand).text.toString())
        assertEquals(1, anfragen.count { it == "/napi/portal/energy" })
        k.performClick()
        assertEquals("$base/energie", shadowOf(a).nextStartedActivity?.dataString)
        start()
        Thread.sleep(300)
        assertEquals("read at most every few minutes", 1, anfragen.count { it == "/napi/portal/energy" })
    }

    @Test
    fun withoutTheEnergyNoTileAndNoRead() {
        kann("geraet")
        assertNull(kachel(start(), "Energie"))
        KannStore.clear(ctx)
        assertNull("what the box can do unknown: no link to a page that may not be there", kachel(start(), "Energie"))
        Thread.sleep(300)
        assertTrue(anfragen.none { it == "/napi/portal/energy" })
    }

    private companion object {
        /** The owner's evening of 2026-11-03. */
        const val ENERGY = """{"ok":true,"energy":{
          "flow":[
            {"entity_id":"sensor.pv","label":"PV","sense":"supply","state":"0","unit":"W"},
            {"entity_id":"sensor.haus","label":"Haus","sense":"draw","state":"271","unit":"W"},
            {"entity_id":"sensor.netz","label":"Netz","sense":"grid","state":"-4","unit":"W"}],
          "totals":[
            {"entity_id":"sensor.pv_total","label":"PV-Erzeugung","state":"5012.4","unit":"kWh","today":8.2},
            {"entity_id":"sensor.netzbezug_total","label":"Netzbezug","state":"1803.1","unit":"kWh","today":2.0}],
          "circuits":[]}}"""
    }
}
