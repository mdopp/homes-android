package cloud.dopp.homes

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.Assistent
import cloud.dopp.homes.data.Card
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.ui.HomeActivity
import cloud.dopp.homes.ui.StartDevices
import cloud.dopp.homes.verbindung.Verbindung
import cloud.dopp.homes.verbindung.Zustand
import cloud.dopp.homes.widget.DeviceWidgetProvider
import cloud.dopp.homes.widget.WidgetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.net.ConnectException
import java.util.Calendar

/**
 * The box not reachable (owner, 2026-11-04), drawn: the start screen with its
 * line „Box nicht erreichbar – versucht es wieder …“, „Stand 08:14“ below it, above the
 * favorites read last, a device widget greyed with „Stand 08:14“. Asserted:
 * both say the one answer of [Verbindung]. The
 * drawings land in `build/widget-preview/` (and `$WIDGET_PREVIEW_DIR`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class OfflinePreviewTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun theBoxGoneSince0814() {
        shadowOf(ctx.getSystemService(android.os.PowerManager::class.java)).setIgnoringBatteryOptimizations(ctx.packageName, true)
        ServerStore.setBaseUrl(ctx, "https://haus.example")
        TokenStore.backend = ctx.getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx, "homes_device_TESTTOKEN123")
        Assistent.save(ctx, "Homes")
        Verbindung.ergebnis(ctx, ConnectException("Connection refused"), null)
        // the last answer at 08:14 today, so the drawing says a time a person would see
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 14)
        }.timeInMillis
        ctx.getSharedPreferences("homes_verbindung", Context.MODE_PRIVATE).edit().putLong("stand", at).commit()
        assertEquals(Zustand.BOX_WEG, Verbindung.zustand(ctx))
    }

    private fun write(bmp: Bitmap, name: String) {
        listOfNotNull(File(RepoFiles.root, "app/build/widget-preview"), System.getenv("WIDGET_PREVIEW_DIR")?.let(::File)).forEach { dir ->
            dir.mkdirs()
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** [v] laid out at [wDp]×[hDp] on a launcher's wallpaper, written as [name]. */
    private fun drawWidget(v: View, host: FrameLayout, wDp: Int, hDp: Int, name: String) {
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt()
        val h = (hDp * d).toInt()
        host.addView(v, ViewGroup.LayoutParams(w, h))
        host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF3B2A5C.toInt()) // the owner's purple wallpaper
        host.draw(Canvas(bmp))
        write(bmp, name)
    }

    private fun widget(cls: Class<*>, layout: Int, wDp: Int, hDp: Int): Int {
        val mgr = AppWidgetManager.getInstance(ctx)
        @Suppress("UNCHECKED_CAST")
        val id = shadowOf(mgr).createWidget(cls as Class<out android.appwidget.AppWidgetProvider>, layout)
        mgr.updateAppWidgetOptions(
            id,
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
            },
        )
        return id
    }

    @Test
    fun theDeviceWidgetGreyWithSinceWhen() {
        val stand = Verbindung.stand(ctx)!!
        assertTrue(stand, stand.endsWith("08:14"))
        listOf(64 to 72, 140 to 72).forEach { (w, h) ->
            val id = widget(DeviceWidgetProvider::class.java, R.layout.widget_device, w, h)
            WidgetStore.bind(ctx, id, "light.kueche", "Küche Licht", "light", null)
            val host = FrameLayout(ctx)
            val v = DeviceWidgetProvider.build(ctx, id, Card("light.kueche", "Küche Licht", "light", null, "on", null, brightness = 153))
                .apply(ctx, host)
            assertEquals("the widget says the one answer", stand, v.findViewById<TextView>(R.id.d_state).text.toString())
            assertEquals(View.VISIBLE, v.findViewById<View>(R.id.d_state).visibility)
            assertEquals("no level that is not current", View.GONE, v.findViewById<View>(R.id.d_bar).visibility)
            drawWidget(v, host, w, h, "geraet-${if (w > 100) "2x1" else "1x1"}-nicht-erreichbar")
        }
    }

    @Test
    fun theStartScreenSaysItAboveTheFavoritesReadLast() {
        StartDevices.save(ctx, StartPreviewTest.FAVORITES)
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        val line = a.findViewById<TextView>(R.id.verbindung)
        assertEquals(View.VISIBLE, line.visibility)
        assertEquals(ctx.getString(Zustand.BOX_WEG.text) + "\n" + Verbindung.stand(ctx), line.text.toString())
        val content = (a.findViewById<ViewGroup>(R.id.home_root)).getChildAt(0)
        val w = (411 * a.resources.displayMetrics.density).toInt()
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        val bmp = Bitmap.createBitmap(content.measuredWidth, content.measuredHeight, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(ctx.getColor(R.color.terminal_ground))
        content.draw(Canvas(bmp))
        write(bmp, "start-nicht-erreichbar")
    }
}
