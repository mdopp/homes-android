package cloud.dopp.homes

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.ui.HomeActivity
import cloud.dopp.homes.ui.StartDevices
import cloud.dopp.homes.widget.WidgetActionReceiver
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import org.json.JSONObject
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
import org.robolectric.shadows.ShadowLooper

/**
 * A favorite's level opens a sheet (owner, 2026-11-03, as Homes' devices page
 * since homes#230): the row has no slider, so a scroll over the favorites
 * sends nothing; the „60 %“ opens a bottom sheet whose quick steps send one
 * call each and leave it open, and Fertig closes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LevelSheetTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private val favorites = """{"ok":true,"favorites":[
        {"entity_id":"light.kueche","name":"Küchenlicht","domain":"light","state":"on","brightness":153,"supported_color_modes":["brightness"]},
        {"entity_id":"light.flur","name":"Flurlicht","domain":"light","state":"off","supported_color_modes":["brightness"]},
        {"entity_id":"cover.wohnzimmer","name":"Rollo","domain":"cover","device_class":"shutter","state":"closed","current_position":0,"supported_features":15},
        {"entity_id":"light.stehlampe","name":"Stehlampe","domain":"light","state":"off","supported_color_modes":["onoff"]},
        {"entity_id":"light.keller","name":"Kellerlicht","domain":"light","state":"unavailable","supported_color_modes":["brightness"]}]}"""

    @Before
    fun paired() {
        ServerStore.clear(ctx())
        StartDevices.clear(ctx())
        ServerStore.setBaseUrl(ctx(), "https://127.0.0.1:1")
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_TESTTOKEN123")
        StartDevices.save(ctx(), favorites)
    }

    private fun open(): HomeActivity = Robolectric.buildActivity(HomeActivity::class.java).setup().visible().get().also {
        ShadowLooper.idleMainLooper()
    }

    /** The tiles in the favourites' order: the start screen puts what wants attention first (owner 2026-11-27). */
    private fun rows(a: HomeActivity): List<View> = Kacheln.of(a).sortedBy { t ->
        listOf("Küchenlicht", "Flurlicht", "Rollo", "Stehlampe", "Kellerlicht").indexOfFirst { t.contentDescription?.startsWith("$it,") == true }
    }

    /** A tile's words under its name, null when its picture says it all. */
    private fun words(tile: View): String? = tile.findViewById<TextView>(R.id.row_room).takeIf { it.visibility == View.VISIBLE }?.text?.toString()

    private fun level(row: View) = row.findViewById<TextView>(R.id.row_level)

    private fun calls(a: HomeActivity) = shadowOf(a.application).broadcastIntents.filter { it.action == WidgetActionReceiver.ACTION_TAP }

    private fun sheet(): BottomSheetDialog? = ShadowDialog.getLatestDialog() as? BottomSheetDialog

    private fun <T : View> all(v: View, type: Class<T>): List<T> =
        (if (type.isInstance(v)) listOf(type.cast(v)!!) else emptyList()) +
            (if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it), type) } else emptyList())

    private fun step(label: String): Button =
        all(sheet()!!.window!!.decorView, Button::class.java).single { it.text.toString() == label }

    /**
     * Each tile: the picture says on or off, the level beside it to tap. A
     * lamp HA can dim that is off has „aus“ as its level (owner,
     * 2026-11-04); a lamp that only switches has no words and nothing to
     * tap; one out of reach says so and has nothing to tap.
     */
    @Test
    fun theTileSaysItsLevelAndAnOffDimmerAusToTap() {
        val r = rows(open())
        assertNull("the lit picture says „an“", words(r[0]))
        assertEquals("60 %", level(r[0]).text.toString())
        assertEquals("Küchenlicht Helligkeit 60 %, einstellen", level(r[0]).contentDescription)
        // the dimmer that is off: „aus“ is its level
        assertNull(words(r[1]))
        assertEquals(View.VISIBLE, level(r[1]).visibility)
        assertEquals("aus", level(r[1]).text.toString())
        assertEquals("Flurlicht Helligkeit aus, einstellen", level(r[1]).contentDescription)
        assertEquals("Flurlicht, aus", r[1].contentDescription)
        assertNull("the lowered blind says „zu“", words(r[2]))
        assertEquals("0 %", level(r[2]).text.toString())
        // the lamp that only switches: its picture, nothing to tap; the one out of reach says so
        assertNull(words(r[3]))
        assertEquals(View.GONE, level(r[3]).visibility)
        assertEquals("Stehlampe, aus", r[3].contentDescription)
        assertEquals("nicht erreichbar", words(r[4]))
        assertEquals(View.GONE, level(r[4]).visibility)
    }

    /** The off dimmer's „aus“ opens its sheet saying „aus“; a step turns it on at that level in one call, the sheet staying open. */
    @Test
    fun anOffDimmersAusOpensTheSheetAndAStepTurnsItOn() {
        val a = open()
        shadowOf(a.application).clearBroadcastIntents()
        level(rows(a)[1]).performClick()
        ShadowLooper.idleMainLooper()
        val v = sheet()!!.window!!.decorView
        assertTrue(sheet()!!.isShowing)
        assertEquals("Flurlicht", v.findViewById<TextView>(R.id.sheet_title).text.toString())
        assertEquals("aus", v.findViewById<TextView>(R.id.sheet_value).text.toString())
        assertEquals(0f, v.findViewById<Slider>(R.id.sheet_slider).value)
        assertTrue("opening sends nothing", calls(a).isEmpty())
        step("25 %").performClick()
        ShadowLooper.idleMainLooper()
        val one = calls(a).single()
        assertEquals("light.flur", one.getStringExtra(WidgetActionReceiver.EXTRA_ENTITY))
        assertEquals("light.turn_on", one.getStringExtra(WidgetActionReceiver.EXTRA_SERVICE))
        assertEquals(25, JSONObject(one.getStringExtra(WidgetActionReceiver.EXTRA_DATA)!!).getInt("brightness_pct"))
        assertTrue("the sheet stays open", sheet()!!.isShowing)
        assertEquals("25 %", v.findViewById<TextView>(R.id.sheet_value).text.toString())
    }

    /** A vertical swipe across the favorites, through the level, sends nothing and opens nothing. */
    @Test
    fun aScrollOverTheFavoritesSendsNothing() {
        val a = open()
        shadowOf(a.application).clearBroadcastIntents()
        val target = level(rows(a)[0])
        val at = IntArray(2).also { target.getLocationInWindow(it) }
        val x = at[0] + target.width / 2f
        val y0 = at[1] + target.height / 2f
        val root = a.window.decorView
        val t = SystemClock.uptimeMillis()
        fun ev(action: Int, dt: Long, y: Float) = MotionEvent.obtain(t, t + dt, action, x, y, 0).also { root.dispatchTouchEvent(it); it.recycle() }
        ev(MotionEvent.ACTION_DOWN, 0, y0)
        for (i in 1..10) ev(MotionEvent.ACTION_MOVE, i * 16L, y0 - i * 40f)
        ev(MotionEvent.ACTION_UP, 200, y0 - 400f)
        ShadowLooper.idleMainLooper()
        assertTrue("a scroll sent ${calls(a).map { it.extras }}", calls(a).isEmpty())
        assertNull("a scroll opened a sheet", sheet()?.takeIf { it.isShowing })
    }

    /** The touch target reaches at least 48 dp: a tap just under the small „60 %“ opens the sheet too. */
    @Test
    fun theLevelIsAtLeast48dpTall() {
        val a = open()
        val target = level(rows(a)[0])
        val d = a.resources.displayMetrics.density
        assertTrue("the text itself is small here", target.height < 48 * d)
        val at = IntArray(2).also { target.getLocationInWindow(it) }
        val x = at[0] + target.width / 2f
        val y = at[1] + target.height / 2f + 22 * d // within 48 dp around its middle, outside its text
        val t = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 50L).forEach { (act, dt) ->
            MotionEvent.obtain(t, t + dt, act, x, y, 0).also { a.window.decorView.dispatchTouchEvent(it); it.recycle() }
        }
        ShadowLooper.idleMainLooper()
        assertTrue("no sheet from a tap at the edge of 48 dp", sheet()?.isShowing == true)
    }

    /** The „60 %“ opens the sheet: the name, the level large, the slider at it, the steps, Fertig; no call yet. */
    @Test
    fun tappingTheLevelOpensTheSheet() {
        val a = open()
        shadowOf(a.application).clearBroadcastIntents()
        level(rows(a)[0]).performClick()
        ShadowLooper.idleMainLooper()
        val s = sheet()
        assertNotNull(s)
        assertTrue(s!!.isShowing)
        val v = s.window!!.decorView
        assertEquals("Küchenlicht", v.findViewById<TextView>(R.id.sheet_title).text.toString())
        assertEquals("60 %", v.findViewById<TextView>(R.id.sheet_value).text.toString())
        assertEquals(60f, v.findViewById<Slider>(R.id.sheet_slider).value)
        assertEquals(listOf("25 %", "50 %", "75 %", "100 %", "Fertig"), all(v, Button::class.java).map { it.text.toString() })
        assertTrue("opening sends nothing", calls(a).isEmpty())
    }

    /** Each quick step is exactly one call, and the sheet stays open; Fertig closes it. */
    @Test
    fun aStepSendsOneCallAndFertigCloses() {
        val a = open()
        level(rows(a)[0]).performClick()
        ShadowLooper.idleMainLooper()
        shadowOf(a.application).clearBroadcastIntents()
        step("25 %").performClick()
        ShadowLooper.idleMainLooper()
        val one = calls(a).single()
        assertEquals("light.kueche", one.getStringExtra(WidgetActionReceiver.EXTRA_ENTITY))
        assertEquals("light.turn_on", one.getStringExtra(WidgetActionReceiver.EXTRA_SERVICE))
        assertEquals(25, JSONObject(one.getStringExtra(WidgetActionReceiver.EXTRA_DATA)!!).getInt("brightness_pct"))
        assertTrue("the sheet stays open", sheet()!!.isShowing)
        assertEquals("25 %", sheet()!!.window!!.decorView.findViewById<TextView>(R.id.sheet_value).text.toString())
        step("100 %").performClick()
        assertEquals(2, calls(a).size)
        step("Fertig").performClick()
        ShadowLooper.idleMainLooper()
        assertFalse(sheet()!!.isShowing)
        assertEquals("Fertig sends nothing", 2, calls(a).size)
    }

    /** A closed cover's „0 %“ opens its sheet with Auf, ½, Zu and Stopp; Auf sets the position 100. Back closes it. */
    @Test
    fun aClosedCoversSheetGoesAuf() {
        val a = open()
        level(rows(a)[2]).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals("Rollo", sheet()!!.window!!.decorView.findViewById<TextView>(R.id.sheet_title).text.toString())
        assertEquals(listOf("Auf", "½", "Zu", "Stopp", "Fertig"), all(sheet()!!.window!!.decorView, Button::class.java).map { it.text.toString() })
        shadowOf(a.application).clearBroadcastIntents()
        step("Stopp").performClick()
        assertEquals("cover.stop_cover", calls(a).single().getStringExtra(WidgetActionReceiver.EXTRA_SERVICE))
        shadowOf(a.application).clearBroadcastIntents()
        step("Auf").performClick()
        val one = calls(a).single()
        assertEquals("cover.set_cover_position", one.getStringExtra(WidgetActionReceiver.EXTRA_SERVICE))
        assertEquals(100, JSONObject(one.getStringExtra(WidgetActionReceiver.EXTRA_DATA)!!).getInt("position"))
        sheet()!!.onBackPressed()
        ShadowLooper.idleMainLooper()
        assertFalse(sheet()!!.isShowing)
    }
}
