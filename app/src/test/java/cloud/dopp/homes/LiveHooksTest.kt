package cloud.dopp.homes

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.ui.HomeActivity
import cloud.dopp.homes.widget.WidgetActionReceiver
import cloud.dopp.homes.widget.EnergyWidgetProvider
import cloud.dopp.homes.widget.ToolWidgetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Where the live service may start on Android 12+, and that each of those
 * places does start it: the app's screen, every widget tap (the launcher's
 * click carries the exemption), the configure screens — including the no-UI
 * one the fed widgets without a screen get, so placing one from the tray
 * counts — boot and an app update (LiveServiceTest). And the exemption from
 * battery optimization that lets a restart after a killed process go
 * foreground: asked once after pairing, its state beside the switch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveHooksTest {

    private val serviceClass = "cloud.dopp.homes.realtime.LiveService"

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private val app get() = shadowOf(ctx() as android.app.Application)

    @Before
    fun pairedWithAFedWidget() {
        ServerStore.setBaseUrl(ctx(), "https://homes.example")
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_T1")
        shadowOf(AppWidgetManager.getInstance(ctx())).createWidget(EnergyWidgetProvider::class.java, R.layout.widget_energy)
        drain()
    }

    private fun drain() {
        while (app.nextStartedService != null) { /* drain */ }
    }

    private fun started(): Boolean = app.nextStartedService?.component?.className == serviceClass

    private fun string(name: String): String {
        val id = ctx().resources.getIdentifier(name, "string", ctx().packageName)
        assertTrue("no string resource $name", id != 0)
        return ctx().getString(id)
    }

    /** Every tap a widget can send reaches a receiver that starts the live service. */
    @Test
    fun everyWidgetTapStartsIt() {
        val taps = listOf(
            EnergyWidgetProvider::class.java to EnergyWidgetProvider.ACTION_REFRESH,
            ToolWidgetProvider::class.java to ToolWidgetProvider.ACTION_REFRESH,
            // the device widget's tile: its tap goes to the device receiver
            WidgetActionReceiver::class.java to WidgetActionReceiver.ACTION_TAP,
        )
        val layouts = mapOf(
            EnergyWidgetProvider::class.java to R.layout.widget_energy,
            ToolWidgetProvider::class.java to R.layout.widget_tool,
        )
        val shadow = shadowOf(AppWidgetManager.getInstance(ctx()))
        val ids = layouts.mapValues { (cls, layout) -> shadow.createWidget(cls, layout) }
        val missed = taps.filter { (cls, action) ->
            drain()
            val tap = Intent(ctx(), cls).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, ids[cls] ?: 0)
            (cls.getDeclaredConstructor().newInstance() as android.content.BroadcastReceiver).onReceive(ctx(), tap)
            shadowOf(Looper.getMainLooper()).idle()
            !started()
        }.map { "${it.first.simpleName} ${it.second}" }
        assertTrue("taps that do not start it: $missed", missed.isEmpty())
    }

    /** Opening the app starts it. */
    @Test
    fun theAppsScreenStartsIt() {
        Robolectric.buildActivity(HomeActivity::class.java).setup()
        assertTrue("HomeActivity did not start it", started())
    }

    /** The configure screens with a UI start it when they bind a widget. */
    @Test
    fun theConfigureScreensStartIt() {
        listOf("WidgetConfigActivity.kt", "ToolWidgetConfigActivity.kt").forEach { f ->
            val src = File(RepoFiles.main, "java/cloud/dopp/homes/widget/$f").readText()
            val done = src.substringBefore("setResult(RESULT_OK, resultIntent())").substringAfterLast("fun ")
            assertTrue("$f does not start the live service where it binds", done.contains("Live.ensure("))
        }
    }

    /** The shared no-UI configure screen: it starts the service and says OK at once. */
    @Test
    fun theNoUiConfigureScreenStartsItAndConfirmsAtOnce() {
        @Suppress("UNCHECKED_CAST")
        val cls = Class.forName("cloud.dopp.homes.widget.LiveConfigActivity") as Class<Activity>
        val intent = Intent(ctx(), cls).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 42)
        val a = Robolectric.buildActivity(cls, intent).setup().get()
        assertTrue("it did not finish", a.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(a).resultCode)
        assertEquals(42, shadowOf(a).resultIntent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        assertTrue("it did not start the live service", started())
    }

    /** The row asking for the exemption (so a restart after a killed process goes live by itself): its tap asks; granted, it is gone. */
    @Test
    fun theZulassenRowAsksOnlyWhileTheExemptionIsMissing() {
        val pm = shadowOf(ctx().getSystemService(PowerManager::class.java))
        pm.setIgnoringBatteryOptimizations(ctx().packageName, false)
        var a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        val id = ctx().resources.getIdentifier("live_battery", "id", ctx().packageName)
        assertTrue("no live_battery in the app", id != 0)
        assertEquals(string("live_battery_off"), a.findViewById<TextView>(id).text.toString())
        a.findViewById<TextView>(id).performClick()
        val asked = shadowOf(a).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, asked?.action)
        assertEquals(Uri.parse("package:" + ctx().packageName), asked?.data)

        pm.setIgnoringBatteryOptimizations(ctx().packageName, true)
        a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        val row = ctx().resources.getIdentifier("live_row", "id", ctx().packageName)
        assertEquals("granted, and the row is still there", android.view.View.GONE, a.findViewById<android.view.View>(row).visibility)
    }
}
