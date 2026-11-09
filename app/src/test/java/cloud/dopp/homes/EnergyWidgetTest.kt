package cloud.dopp.homes

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.widget.EnergyPicture
import cloud.dopp.homes.widget.EnergyRender
import cloud.dopp.homes.widget.EnergyWidgetProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The one energy widget (owner, 2026-11-02, redrawn 2026-11-03): the flow now
 * as a picture with the battery's charge, and "heute" (what the counters added
 * since midnight) beside it, and a tap opens
 * Homes' Energie page at the paired address — not hard-coded — counted as
 * this widget's (`von=widget.energy`, #180).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnergyWidgetTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun unpair() = ServerStore.clear(ctx)

    /** Homes' energy picture (internal/napi/energy.go): the flow, and totals with today. */
    private val energy = ApiClient.parseEnergy(
        JSONObject(
            """{"ok":true,"energy":{
              "flow":[
                {"entity_id":"sensor.pv","label":"PV","sense":"supply","state":"3200","unit":"W"},
                {"entity_id":"sensor.haus","label":"Haus","sense":"draw","state":"800","unit":"W"},
                {"entity_id":"sensor.netz","label":"Netz","sense":"grid","state":"-2100","unit":"W"},
                {"entity_id":"sensor.akku","label":"Akku","sense":"battery","state":"-300","unit":"W"}],
              "totals":[
                {"entity_id":"sensor.pv_total","label":"PV-Erzeugung","state":"5012.4","unit":"kWh","today":12.4},
                {"entity_id":"sensor.einspeisung_total","label":"Einspeisung","state":"2306","unit":"kWh","today":6.0},
                {"entity_id":"sensor.netzbezug_total","label":"Netzbezug","state":"1803.1","unit":"kWh","today":3.1},
                {"entity_id":"sensor.batterie_laden","label":"Batterie geladen","state":"700.5","unit":"kWh"}],
              "charge":{"entity_id":"sensor.akku_soc","name":"Akku-Füllstand in Prozent","label":"Akku-Füllstand","state":"83","unit":"%"},
              "circuits":[]}}""",
        ),
    )!!

    /**
     * Draw the picture into a placed widget of [w]×[h] dp, applied to a view of
     * our own: the provider's own refresh of the placed widget runs on a thread
     * and must not draw over what is asserted here.
     */
    private fun drawn(w: Int, h: Int, paired: Boolean = true, e: cloud.dopp.homes.data.Energy = energy): View {
        val mgr = AppWidgetManager.getInstance(ctx)
        val id = shadowOf(mgr).createWidget(EnergyWidgetProvider::class.java, R.layout.widget_energy)
        mgr.updateAppWidgetOptions(
            id,
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h)
            },
        )
        val views = EnergyRender.build(
            ctx, id, e, paired,
            EnergyWidgetProvider.tapPending(ctx, id),
        )
        return views.apply(ctx, android.widget.FrameLayout(ctx))
    }

    private fun View.text(name: String): String {
        val id = ctx.resources.getIdentifier(name, "id", ctx.packageName)
        assertTrue("no view $name", id != 0)
        return findViewById<TextView>(id).text.toString()
    }

    private fun View.picture(name: Int): android.graphics.Bitmap? =
        (findViewById<ImageView>(name).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap

    /** Owner, 2026-11-03: a picture like the Energie page, the battery's charge in it, today's kWh beside it; no „Heute“ heading (owner, 2026-11-08). */
    @Test
    fun itShowsThePictureAndTodaysTotalsBesideIt() {
        val v = drawn(300, 150)
        val bmp = v.picture(R.id.e_flow)
        assertTrue("the flow is a picture", bmp != null && bmp.width > 100)
        val said = v.findViewById<View>(R.id.e_flow).contentDescription.toString()
        assertTrue(said, said.contains("Akku 83\u00a0% entlädt 300 W") && said.contains("Solar erzeugt 3,2 kW") && said.contains("Solar → Haus 800 W"))
        // heute: what each counter added since midnight; no today is a dash, never the lifetime count
        assertEquals(View.VISIBLE, v.findViewById<View>(R.id.e_today_block).visibility)
        assertEquals("Erzeugt", v.text("e_today_l0"))
        assertEquals("12,4 kWh", v.text("e_today_v0"))
        assertEquals("6,0 kWh", v.text("e_today_v1"))
        assertEquals("3,1 kWh", v.text("e_today_v2"))
        assertNull("„In den Akku“ is gone", v.findViewById<View?>(ctx.resources.getIdentifier("e_today_v3", "id", ctx.packageName)))
        assertEquals("Autarkie", v.text("e_autarkie_l"))
        assertEquals("100\u00a0%", v.text("e_autarkie_v"))
        assertEquals("a „Heute“ heading", 0, ctx.resources.getIdentifier("e_today_label", "id", ctx.packageName))
        assertTrue("„Jetzt“ as a label", "Jetzt" !in said)
    }

    /** Heute in the picture's colours: the sun's, feeding in, drawing. */
    @Test
    fun heuteWearsThePicturesColours() {
        val v = drawn(300, 150)
        fun color(name: String) = v.findViewById<TextView>(ctx.resources.getIdentifier(name, "id", ctx.packageName)).currentTextColor
        assertEquals(EnergyPicture.SOLAR, color("e_today_v0"))
        assertEquals(EnergyPicture.EINSP, color("e_today_v1"))
        assertEquals(EnergyPicture.BEZUG, color("e_today_v2"))
    }

    /** A Haus that says nothing about today, or a cell too narrow: the picture alone, no row of dashes. */
    @Test
    fun withoutTodayOrRoomThePictureIsAlone() {
        val noToday = energy.copy(totals = energy.totals.map { it.copy(today = null) })
        assertEquals(View.GONE, drawn(300, 150, e = noToday).findViewById<View>(R.id.e_today_block).visibility)
        val narrow = drawn(150, 150)
        assertEquals(View.GONE, narrow.findViewById<View>(R.id.e_today_block).visibility)
        assertTrue(narrow.picture(R.id.e_flow) != null)
    }

    /** No capitals anywhere in the widget's layouts (owner, 2026-11-03). */
    @Test
    fun noCapitals() {
        listOf("widget_energy.xml", "widget_energy_small.xml").forEach {
            val xml = java.io.File(RepoFiles.main, "res/layout/$it").readText()
            assertTrue(it, !xml.contains("textAllCaps=\"true\""))
        }
    }

    /** One cell high: the battery's charge as a ring, the grid's direction beside it; with room the house and Heute. */
    @Test
    fun smallItShowsTheChargeAndTheGrid() {
        val bare = drawn(200, 60)
        assertTrue("the ring is a picture", bare.picture(R.id.e_ring) != null)
        assertEquals("Akku 83\u00a0%, entlädt", bare.findViewById<View>(R.id.e_ring).contentDescription.toString())
        assertEquals("2,1 kW", bare.text("e_main"))
        assertEquals("ins Netz", bare.text("e_title"))
        assertEquals(View.GONE, bare.findViewById<View>(R.id.e_sub).visibility)
        assertEquals(View.GONE, bare.findViewById<View>(R.id.e_today).visibility)
        val taller = drawn(200, 100)
        assertEquals("Haus verbraucht 800 W", taller.text("e_sub"))
        assertEquals("Heute 12,4 kWh erzeugt", taller.text("e_today"))
        assertEquals(View.VISIBLE, taller.findViewById<View>(R.id.e_today).visibility)
        assertNull("no today from Haus, no line", EnergyRender.todayLine(energy.copy(totals = emptyList())))
        // four cells wide, one high: the house and Heute beside the number
        val wide = drawn(300, 60)
        assertEquals(View.VISIBLE, wide.findViewById<View>(R.id.e_side).visibility)
        assertEquals("Haus verbraucht 800 W", wide.text("e_side1"))
        assertEquals("Heute 12,4 kWh erzeugt", wide.text("e_side2"))
    }

    /** A box that does not send the charge yet: the grid in the ring, the house in words. */
    @Test
    fun smallWithoutTheChargeShowsTheGrid() {
        val v = drawn(250, 100, e = energy.copy(charge = null))
        assertEquals("Haus verbraucht", v.text("e_title"))
        assertEquals("800 W", v.text("e_main"))
        assertEquals("Akku entlädt 300 W", v.text("e_sub"))
    }

    /** Not paired: the widget says so instead of drawing. */
    @Test
    fun notPairedSaysSo() {
        val v = drawn(300, 150, paired = false)
        assertEquals(View.VISIBLE, v.findViewById<View>(R.id.e_note).visibility)
        assertEquals("In der App koppeln", v.text("e_note"))
    }

    /** A tap opens https://<the paired Homes>/energie in the browser, once counted. */
    @Test
    fun aTapOpensHomesEnergiePage() {
        ServerStore.setBaseUrl(ctx, "https://homes.dopp.example")
        val v = drawn(250, 120)
        v.findViewById<View>(R.id.e_root).performClick()
        val opened = shadowOf(ctx as android.app.Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened?.action)
        assertEquals("https://homes.dopp.example/energie?von=widget.energy", opened?.dataString)
        assertTrue(opened!!.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    /** Another box, another domain: the page follows the pairing, nothing is hard-coded. */
    @Test
    fun theAddressIsThePairedOne() {
        ServerStore.setBaseUrl(ctx, "https://homes.anderes.example")
        drawn(110, 40).findViewById<View>(R.id.e_root).performClick()
        assertEquals(
            "https://homes.anderes.example/energie?von=widget.energy",
            shadowOf(ctx as android.app.Application).nextStartedActivity?.dataString,
        )
    }

    /** No reload arrows (owner, 2026-11-03): the live stream keeps the widget current. */
    @Test
    fun noReloadArrows() {
        listOf("widget_energy.xml", "widget_energy_small.xml").forEach {
            val xml = java.io.File(RepoFiles.main, "res/layout/$it").readText()
            assertTrue(it, !xml.contains("ic_refresh"))
        }
    }

    /** Every widget on solid black without an outline, as Android's own (owner, 2026-11-03). */
    @Test
    fun blackGroundNoOutline() {
        val bg = java.io.File(RepoFiles.main, "res/drawable/widget_bg.xml").readText()
        assertTrue(bg, bg.contains("<solid android:color=\"#FF000000\"") && !bg.contains("<stroke") && !bg.contains("<gradient"))
    }
}
