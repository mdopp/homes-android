package cloud.dopp.homes.e2e

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.widget.DeviceWidgetProvider
import cloud.dopp.homes.widget.WidgetStore
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The live e2e's starting point (e2e/live_e2e.sh): the app paired to the fake
 * Homes on the emulator's host, a device widget placed: the stream watches
 * the device (the energy widget went, owner 2026-11-28). Binding needs `appwidget grantbind` for the app, which the script
 * does first; the widgets are bound to a host of the app's own, which is all
 * the live service looks at.
 */
@RunWith(AndroidJUnit4::class)
class LiveE2ESetup {

    @Test
    fun pairAndPlace() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("server", ServerStore.setBaseUrl(ctx, "https://10.0.2.2:8443"))
        assertTrue("token", TokenStore.save(ctx, "homes_device_E2E"))
        val mgr = AppWidgetManager.getInstance(ctx)
        val host = AppWidgetHost(ctx, HOST_ID)
        val device = host.allocateAppWidgetId()
        assertTrue("bind the device widget", mgr.bindAppWidgetIdIfAllowed(device, ComponentName(ctx, DeviceWidgetProvider::class.java)))
        WidgetStore.bind(ctx, device, "light.e2e", "E2E", "light", null)
        // The stores write with apply(); the process ends with the instrumentation.
        assertTrue("server not on disk", written(ctx, "homes_server", "10.0.2.2"))
        assertTrue("device binding not on disk", written(ctx, "homes_widgets", "light.e2e"))
    }

    /** Wait until the prefs file [name] holds [needle]: apply() writes in the background. */
    private fun written(ctx: android.content.Context, name: String, needle: String): Boolean {
        val f = java.io.File(ctx.applicationInfo.dataDir, "shared_prefs/$name.xml")
        repeat(100) {
            if (f.isFile && f.readText().contains(needle)) return true
            Thread.sleep(50)
        }
        return false
    }

    private companion object {
        const val HOST_ID = 4242
    }
}
