package cloud.dopp.homes

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.konto.Anstoss
import cloud.dopp.homes.konto.HomesKonto
import cloud.dopp.homes.konto.HomesKonto.Teil
import cloud.dopp.homes.realtime.LiveService
import cloud.dopp.homes.realtime.RealtimeProtocol
import cloud.dopp.homes.widget.EnergyWidgetProvider
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.time.Duration

/**
 * Push for the Homes account (#209, homes#223): a `gemeinsam` frame on the
 * live stream syncs that part of the account, only with its switch on, at
 * most once per 30 s burst; the stream gone, nothing is left to run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomesKontoPushTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val listeners = mutableListOf<EventSourceListener>()
    private val opened = mutableListOf<EventSource>()

    @Test
    fun theFrameIsRead() {
        assertEquals(RealtimeProtocol.GemeinsamEvent(Teil.KALENDER), RealtimeProtocol.frame("gemeinsam", """{"teil":"kalender"}"""))
        assertEquals(RealtimeProtocol.GemeinsamEvent(Teil.KONTAKTE), RealtimeProtocol.frame("gemeinsam", """{"teil":"kontakte"}"""))
        assertNull(RealtimeProtocol.frame("gemeinsam", """{"teil":"listen"}"""))
        assertNull(RealtimeProtocol.frame("gemeinsam", "kaputt"))
        assertNull(RealtimeProtocol.frame("gemeinsam", null))
    }

    @Test
    fun aBurstCostsTwoSyncsAtMost() {
        val a = Anstoss(30_000)
        assertEquals(0L, a.wann(Teil.KALENDER, 1_000))
        assertEquals(25_000L, a.wann(Teil.KALENDER, 6_000)) // within the window: once at its end
        assertNull(a.wann(Teil.KALENDER, 7_000)) // already due
        assertNull(a.wann(Teil.KALENDER, 20_000))
        assertEquals(0L, a.wann(Teil.KONTAKTE, 7_000)) // the other part has its own window
        a.erledigt(Teil.KALENDER, 31_000)
        assertEquals(28_000L, a.wann(Teil.KALENDER, 33_000))
        a.vergiss()
        assertEquals(0L, a.wann(Teil.KALENDER, 61_000))
    }

    private class FakeSource(private val req: Request) : EventSource {
        override fun request() = req
        override fun cancel() {}
    }

    @Before
    fun pairedWithAWidgetAndTheScreenOn() {
        ServerStore.setBaseUrl(app, "https://homes.example")
        TokenStore.backend = app.getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(app, "homes_device_T1")
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        Reach.set("cloud.dopp.homes.realtime.Live", "open", { req: Request, l: EventSourceListener ->
            listeners += l
            FakeSource(req).also { opened += it }
        })
        Reach.set("cloud.dopp.homes.realtime.Live", "postWatch", { _: Context -> })
        shadowOf(android.appwidget.AppWidgetManager.getInstance(app)).createWidget(EnergyWidgetProvider::class.java, R.layout.widget_energy)
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }

    @After
    fun tearDown() {
        HomesKonto.entfernen(app)
    }

    private fun requests(authority: String) = ShadowContentResolver.getStatus(HomesKonto.konto, authority)?.syncRequests ?: 0

    private fun frame(teil: String) {
        listeners.last().onEvent(opened.last(), null, "gemeinsam", """{"teil":"$teil"}""")
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun aFrameSyncsAnEnabledPartOnceABurstAndAnOffPartNever() {
        HomesKonto.einschalten(app, Teil.KALENDER) // one request of its own
        val nachEinschalten = requests(CalendarContract.AUTHORITY)
        Robolectric.buildService(LiveService::class.java).create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        listeners.last().onOpen(opened.last(), Response.Builder().request(Request.Builder().url("https://homes.example/napi/portal/events").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("x").build())
        shadowOf(Looper.getMainLooper()).idle()

        frame("kalender")
        assertEquals("at once", nachEinschalten + 1, requests(CalendarContract.AUTHORITY))
        frame("kalender")
        frame("kalender")
        assertEquals("the burst waits", nachEinschalten + 1, requests(CalendarContract.AUTHORITY))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertEquals("once at the window's end", nachEinschalten + 2, requests(CalendarContract.AUTHORITY))

        frame("kontakte") // its switch is off
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertEquals(0, requests(ContactsContract.AUTHORITY))
    }
}
