package cloud.dopp.homes

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.Assistent
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.ui.HomeActivity
import cloud.dopp.homes.ui.StartDevices
import cloud.dopp.homes.verbindung.Verbindung
import cloud.dopp.homes.verbindung.Zustand
import cloud.dopp.homes.widget.WidgetActionActivity
import cloud.dopp.homes.widget.WidgetActionReceiver
import cloud.dopp.homes.widget.WidgetTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The app's one screen (#176): unpaired it asks for Homes' address; paired it
 * shows the assistant's name, the person's favorite devices first (owner, 2026-11-02),
 * and offers every widget — the house's first, the Box's apart, for whoever
 * looks after it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeScreenTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        ServerStore.clear(ctx())
        TokenStore.clear(ctx())
        Assistent.clear(ctx())
        StartDevices.clear(ctx())
    }

    private fun paired() {
        ServerStore.setBaseUrl(ctx(), "https://127.0.0.1:1")
        // Robolectric has no keystore: the token goes to plain prefs here.
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_TESTTOKEN123")
    }

    private fun open(): HomeActivity = Robolectric.buildActivity(HomeActivity::class.java).setup().get()

    @Test
    fun unpairedItAsksForTheAddress() {
        val a = open()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.connect_section).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.connected_section).visibility)
        assertEquals("Homes", a.findViewById<TextView>(R.id.assistent_name).text.toString())
    }

    @Test
    fun pairedItOffersEveryWidgetAndTheBoxApart() {
        paired()
        val a = open()
        assertEquals(View.GONE, a.findViewById<View>(R.id.connect_section).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.connected_section).visibility)
        // one list, no groups (owner, 2026-11-03): one device and the energy, then the tools
        val list = a.findViewById<ViewGroup>(R.id.widgets_group)
        assertEquals(WidgetTypes.ALL.size, list.childCount)
        assertEquals(listOf("Ein Gerät", "Energie"), (0 until list.childCount).map { texts(list.getChildAt(it)).single() })
        assertNull("no Box group", a.findViewById<View>(ctx().resources.getIdentifier("box_group", "id", ctx().packageName).takeIf { it != 0 } ?: View.NO_ID))
        val all = texts(a.window.decorView)
        listOf("Box", "Haushalt").forEach { assertTrue("a group heading: $it", it !in all) }
    }

    @Test
    fun theScreenShowsTheAssistantsNameAndTheAppsIcon() {
        paired()
        Assistent.save(ctx(), "Mira")
        val a = open()
        assertEquals("Mira", a.findViewById<TextView>(R.id.assistent_name).text.toString())
        assertEquals(ctx().getString(R.string.home_claim), a.findViewById<TextView>(R.id.home_claim).text.toString())
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.assistent_icon).visibility)
        // the fixed look: green phosphor on the dark ground, whatever the box says
        assertEquals(0xFF3DDC84.toInt(), ctx().getColor(R.color.terminal_green))
        assertEquals(0xFF0C120E.toInt(), ctx().getColor(R.color.terminal_ground))
    }

    @Test
    fun unpairingForgetsHomesAndTheAssistantsName() {
        paired()
        Assistent.save(ctx(), "Mira")
        val a = open()
        a.findViewById<View>(R.id.logout_btn).performClick()
        assertNull(TokenStore.get(ctx()))
        assertNull(ServerStore.baseUrl(ctx()))
        assertEquals("Homes", Assistent.name(ctx()))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.connect_section).visibility)
    }

    // --- the devices: favorites first (owner, 2026-11-02) ------------------------

    /** Homes' answer to `/napi/portal/favorites`, the person's ☆ things in their order. */
    private val favorites = """{"ok":true,"favorites":[
        {"entity_id":"light.kueche","name":"Küche","domain":"light","state":"on","brightness":153,"page":"/raeume/light.kueche"},
        {"entity_id":"lock.haustuer","name":"Haustür","domain":"lock","state":"locked","supported_features":1},
        {"entity_id":"sensor.bad","name":"Bad","domain":"sensor","state":"21.5","unit":"°C","page":"/raeume/sensor.bad"}]}"""

    private fun texts(v: View): List<String> =
        if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else listOfNotNull((v as? TextView)?.takeIf { it.visibility == View.VISIBLE }?.text?.toString())

    private fun deviceRows(a: HomeActivity): List<View> = Kacheln.of(a)

    /** A tile's words under its name: only what its picture cannot say, and „schaltet …“ after a tap. */
    private fun words(tile: View): String? = tile.findViewById<TextView>(R.id.row_room).takeIf { it.visibility == View.VISIBLE }?.text?.toString()

    @Test
    fun theFavoritesComeFirstThenAlleGeraete() {
        paired()
        StartDevices.save(ctx(), favorites)
        val a = open()
        val section = a.findViewById<ViewGroup>(R.id.connected_section)
        val group = a.findViewById<View>(R.id.devices_group)
        val order = (0 until section.childCount).map { section.getChildAt(it) }
        assertTrue("the devices come before the widgets", order.indexOf(group) < order.indexOf(a.findViewById(R.id.widgets_card)))
        assertTrue("the devices come before the live switch", order.indexOf(group) < order.indexOf(a.findViewById(R.id.widgets_card)))
        // the picture says on and locked; the level beside it, the name below; a sensor keeps its reading
        val rows = deviceRows(a).map { texts(it) }
        assertEquals(listOf(listOf("60 %", "Küche"), listOf("Haustür"), listOf("Bad", "21,5 °C")), rows)
        assertEquals(listOf("Küche, an · 60 %", "Haustür, abgeschlossen", "Bad, 21,5 °C"), deviceRows(a).map { it.contentDescription })
        // „Alle Geräte“ is the head's „Alle →“, no row of its own
        val all = a.findViewById<TextView>(R.id.devices_all)
        assertEquals(View.VISIBLE, all.visibility)
        assertEquals("Alle →", all.text.toString())
        assertEquals("Alle Geräte", all.contentDescription)
    }

    /** A lamp's tap switches it, the way the device widget's did: through the device receiver. */
    @Test
    fun aTapSwitchesTheFavorite() {
        paired()
        StartDevices.save(ctx(), favorites)
        val a = open()
        boxNotReachable()
        deviceRows(a)[0].performClick()
        assertNotEquals("no „schaltet …“ Homes cannot hear", ctx().getString(R.string.home_device_switching), words(deviceRows(a)[0]))
        Verbindung.ergebnis(ctx(), null, 200) // Homes answers again
        shadowOf(a.application).clearBroadcastIntents()
        deviceRows(a)[0].performClick()
        val tap = shadowOf(a.application).broadcastIntents.single { it.action == WidgetActionReceiver.ACTION_TAP }
        assertEquals("light.kueche", tap.getStringExtra(WidgetActionReceiver.EXTRA_ENTITY))
        assertEquals(WidgetActionReceiver.OP_TOGGLE, tap.getStringExtra(WidgetActionReceiver.EXTRA_OP))
        assertEquals("the tap answers at once", ctx().getString(R.string.home_device_switching), words(deviceRows(a)[0]))
    }

    /** The start screen's read of the favorites goes to a port nothing listens on: wait until it told [Verbindung]. */
    private fun boxNotReachable() {
        val until = System.currentTimeMillis() + 10_000
        while (Verbindung.zustand(ctx()) != Zustand.BOX_WEG && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(Zustand.BOX_WEG, Verbindung.zustand(ctx()))
    }

    /** A lock never switches on a tap: it opens its chooser, with the latch only when the card says it has one (#92). */
    @Test
    fun aLockOpensItsChooser() {
        paired()
        StartDevices.save(ctx(), favorites)
        val a = open()
        shadowOf(a.application).clearBroadcastIntents()
        deviceRows(a)[1].performClick()
        val tap = shadowOf(a.application).broadcastIntents.single { it.action == WidgetActionReceiver.ACTION_TAP }
        assertEquals(WidgetActionReceiver.OP_LOCK_CHOOSE, tap.getStringExtra(WidgetActionReceiver.EXTRA_OP))
        assertEquals(1, tap.getIntExtra(WidgetActionActivity.EXTRA_FEATURES, -1))
    }

    /** A sensor cannot be switched: its tap opens its page on Homes; „Alle →“ opens the devices page; a long press the device. */
    @Test
    fun theRestOpensHomes() {
        paired()
        StartDevices.save(ctx(), favorites)
        val a = open()
        deviceRows(a)[2].performClick()
        assertEquals("https://127.0.0.1:1/raeume/sensor.bad", shadowOf(a).nextStartedActivity?.dataString)
        a.findViewById<View>(R.id.devices_all).performClick()
        assertEquals("https://127.0.0.1:1/raeume", shadowOf(a).nextStartedActivity?.dataString)
        deviceRows(a)[0].performLongClick()
        assertEquals("https://127.0.0.1:1/raeume/light.kueche", shadowOf(a).nextStartedActivity?.dataString)
    }

    /** No favorites yet: one sentence how to get some, and „Alle →“ all the same. */
    @Test
    fun noFavoritesSaysHow() {
        paired()
        StartDevices.save(ctx(), """{"ok":true,"favorites":[]}""")
        val a = open()
        assertEquals(listOf(ctx().getString(R.string.home_devices_none)), Kacheln.notes(a))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.devices_all).visibility)
    }

    /** Unpairing forgets the favorites with the rest. */
    @Test
    fun unpairingForgetsTheFavorites() {
        paired()
        StartDevices.save(ctx(), favorites)
        open().findViewById<View>(R.id.logout_btn).performClick()
        assertNull(StartDevices.load(ctx()))
    }

    /** The favorites cannot be read (a Homes not yet updated): one sentence, and „Alle →“ all the same. */
    @Test
    fun alleGeraeteStaysWhenTheFavoritesFail() {
        paired() // 127.0.0.1:1 answers nothing
        val a = open()
        repeat(100) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (Kacheln.notes(a).contains(ctx().getString(R.string.home_devices_unreadable))) return@repeat
            Thread.sleep(50)
        }
        assertEquals(listOf(ctx().getString(R.string.home_devices_unreadable)), Kacheln.notes(a))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.devices_all).visibility)
    }

    // --- the screen's order and the favorites' fine controls (owner, 2026-11-03) ---

    private fun children(a: HomeActivity): List<View> =
        a.findViewById<ViewGroup>(R.id.connected_section).let { g -> (0 until g.childCount).map { g.getChildAt(it) } }

    /**
     * The row asking for „Zulassen“ is the last row of the widgets' card, in its
     * row style (owner, 2026-11-03), under „Widget hinzufügen“ and its hint — no
     * live switch (owner, 2026-11-08: live is the one way); „Gekoppelt mit …“
     * right above „Kopplung aufheben“.
     */
    @Test
    fun theZulassenRowIsTheWidgetCardsLastRowAndThePairingSitsByUnpair() {
        paired()
        val a = open()
        val order = children(a)
        val title = order.indexOfFirst { (it as? TextView)?.text?.toString() == ctx().getString(R.string.home_widgets_title) }
        val card = a.findViewById<ViewGroup>(R.id.widgets_card)
        assertTrue("the widgets' title, then the card", title in 0 until order.indexOf(card))
        assertEquals("the widgets, then the row", listOf(R.id.widgets_group, R.id.live_row), (0 until card.childCount).map { card.getChildAt(it).id })
        assertEquals("a live switch", 0, ctx().resources.getIdentifier("live_switch", "id", ctx().packageName))
        assertEquals("Widgets aktuell halten", a.findViewById<TextView>(R.id.live_title).text.toString())
        assertEquals("Tippen und „Zulassen“ wählen", a.findViewById<TextView>(R.id.live_battery).text.toString())
        assertEquals("no paragraph of its own", -1, order.indexOf(a.findViewById(R.id.live_battery)))
        val status = order.indexOf(a.findViewById(R.id.status))
        assertEquals("„Gekoppelt mit …“ right above „Kopplung aufheben“", order.indexOf(a.findViewById(R.id.logout_btn)) - 1, status)
        assertEquals("Gekoppelt mit 127.0.0.1", a.findViewById<TextView>(R.id.status).text.toString())
    }

    /** The row is there only while the exemption is missing; granted, it is gone (owner 2026-11-08: nothing to explain). */
    @Test
    fun zulassenOnlyWhileTheExemptionIsMissing() {
        paired()
        val pm = shadowOf(ctx().getSystemService(android.os.PowerManager::class.java))
        pm.setIgnoringBatteryOptimizations(ctx().packageName, true)
        assertEquals("granted, yet the row is there", View.GONE, open().findViewById<View>(R.id.live_row).visibility)
        pm.setIgnoringBatteryOptimizations(ctx().packageName, false)
        val a = open()
        assertEquals("missing, and no row asks", View.VISIBLE, a.findViewById<View>(R.id.live_row).visibility)
        assertTrue("Zulassen" in a.findViewById<TextView>(R.id.live_battery).text.toString())
    }

    private val shutter = """{"ok":true,"favorites":[
        {"entity_id":"cover.wohnzimmer","name":"Rollladen","domain":"cover","device_class":"shutter","state":"open","current_position":40,"supported_features":15},
        {"entity_id":"cover.garage","name":"Garage","domain":"cover","device_class":"garage","state":"closed","supported_features":3},
        {"entity_id":"climate.bad","name":"Bad","domain":"climate","state":"heat","current_temperature":21.5,"temperature":22,"target_temp_step":0.5}]}"""

    private fun button(tile: View, label: String) = Kacheln.buttons(tile).single { it.contentDescription.endsWith(": $label") }

    /**
     * A shutter's tile carries ▲ ■ ▼ (owner, 2026-11-04): ▲ sends
     * cover.open_cover on that cover through the device receiver; a tap on
     * the tile or its level opens the sheet with Auf, ½, Zu and Stopp. A
     * garage has ▲ ▼ as HA says it can, each a call without data, so Homes'
     * rule asks before it opens; its tap stays the tap that asks.
     */
    @Test
    fun aShutterFavoriteHasHochStoppRunter() {
        paired()
        StartDevices.save(ctx(), shutter)
        val a = open()
        val rows = deviceRows(a)
        assertEquals(listOf("Rollladen: Hoch", "Rollladen: Stopp", "Rollladen: Runter"), Kacheln.buttons(rows[0]).map { it.contentDescription })
        assertEquals(listOf("Garage: Hoch", "Garage: Runter"), Kacheln.buttons(rows[1]).map { it.contentDescription })
        val d = a.resources.displayMetrics.density
        Kacheln.buttons(rows[0]).forEach { assertTrue("40 dp wide, 48 dp high", it.width >= 40 * d - 1 && it.height >= 48 * d - 1) }
        shadowOf(a.application).clearBroadcastIntents()
        button(rows[0], "Hoch").performClick()
        val call = shadowOf(a.application).broadcastIntents.single { it.action == WidgetActionReceiver.ACTION_TAP }
        assertEquals("cover.wohnzimmer", call.getStringExtra(WidgetActionReceiver.EXTRA_ENTITY))
        assertEquals(WidgetActionReceiver.OP_CALL, call.getStringExtra(WidgetActionReceiver.EXTRA_OP))
        assertEquals("cover.open_cover", call.getStringExtra(WidgetActionReceiver.EXTRA_SERVICE))
        assertNull(call.getStringExtra(WidgetActionReceiver.EXTRA_DATA))
        shadowOf(a.application).clearBroadcastIntents()
        button(rows[1], "Hoch").performClick()
        val garage = shadowOf(a.application).broadcastIntents.single { it.action == WidgetActionReceiver.ACTION_TAP }
        assertEquals("cover.garage", garage.getStringExtra(WidgetActionReceiver.EXTRA_ENTITY))
        assertEquals("cover.open_cover", garage.getStringExtra(WidgetActionReceiver.EXTRA_SERVICE))
        assertNull("no data: Homes' rule asks", garage.getStringExtra(WidgetActionReceiver.EXTRA_DATA))
        shadowOf(a.application).clearBroadcastIntents()
        rows[1].performClick()
        assertEquals(
            WidgetActionReceiver.OP_TOGGLE,
            shadowOf(a.application).broadcastIntents.single { it.action == WidgetActionReceiver.ACTION_TAP }.getStringExtra(WidgetActionReceiver.EXTRA_OP),
        )
        // its position is the level that opens the sheet, no slider on the tile (owner, 2026-11-03)
        assertEquals("40 %", rows[0].findViewById<TextView>(R.id.row_level).text.toString())
        fun seekBars(v: View): List<View> =
            if (v is android.widget.SeekBar || v is com.google.android.material.slider.Slider) listOf(v)
            else if (v is ViewGroup) (0 until v.childCount).flatMap { seekBars(v.getChildAt(it)) } else emptyList()
        assertTrue("no slider on a tile", deviceRows(a).all { seekBars(it).isEmpty() })
        shadowOf(a.application).clearBroadcastIntents()
        rows[0].performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val sheet = org.robolectric.shadows.ShadowDialog.getLatestDialog() as com.google.android.material.bottomsheet.BottomSheetDialog
        assertTrue("the tile opens the sheet", sheet.isShowing)
        assertTrue("opening sends nothing", shadowOf(a.application).broadcastIntents.none { it.action == WidgetActionReceiver.ACTION_TAP })
        fun labels(v: View): List<String> =
            if (v is android.widget.Button) listOf(v.text.toString()) else if (v is ViewGroup) (0 until v.childCount).flatMap { labels(v.getChildAt(it)) } else emptyList()
        assertEquals(listOf("Auf", "½", "Zu", "Stopp", "Fertig"), labels(sheet.window!!.decorView))
    }

    /** A heating says its reading and setpoint and goes wärmer by its step, the new setpoint in the call's data. */
    @Test
    fun aHeatingFavoriteGoesWaermer() {
        paired()
        StartDevices.save(ctx(), shutter)
        val a = open()
        val row = deviceRows(a)[2]
        assertEquals(listOf("Bad", "21,5 °C · Soll 22,0 °C"), texts(row).take(2))
        shadowOf(a.application).clearBroadcastIntents()
        button(row, "Wärmer").performClick()
        val call = shadowOf(a.application).broadcastIntents.single { it.action == WidgetActionReceiver.ACTION_TAP }
        assertEquals("climate.set_temperature", call.getStringExtra(WidgetActionReceiver.EXTRA_SERVICE))
        assertEquals(22.5, org.json.JSONObject(call.getStringExtra(WidgetActionReceiver.EXTRA_DATA)!!).getDouble("temperature"), 0.0)
    }
}
