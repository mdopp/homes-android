package cloud.dopp.homes

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.kann.KannStore
import cloud.dopp.homes.realtime.Live
import cloud.dopp.homes.realtime.LiveService
import cloud.dopp.homes.verbindung.Verbindung
import cloud.dopp.homes.verbindung.Zustand
import cloud.dopp.homes.widget.ToolWidgetProvider
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.ConnectException
import java.net.SocketException
import java.time.Duration

/**
 * The live notification says [Verbindung]'s answer (owner, 2026-11-04: the
 * box unreachable read „Pausiert, Bildschirm aus“). The bug: the screen-off
 * text stayed after the screen came on, and a failing connect said nothing
 * until its third try. Now „Pausiert“ is the screen off only; a connect
 * refused is „Box nicht erreichbar“ at once; a stream that broke is
 * „Verbinde …“ until the next connect tells; with the screen off nothing goes out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveVerbindungTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private class FakeSource(private val req: Request) : EventSource {
        override fun request() = req
        override fun cancel() = Unit
    }

    private val opened = mutableListOf<FakeSource>()
    private val listeners = mutableListOf<EventSourceListener>()
    private val openBefore = Live.open
    private val watchBefore = Live.postWatch

    @Before
    fun pairedWithAWidgetAndTheScreenOn() {
        ServerStore.setBaseUrl(ctx, "https://homes.example")
        TokenStore.backend = ctx.getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx, "homes_device_T1")
        screen(true)
        Live.open = { req, l -> listeners += l; FakeSource(req).also { opened += it } }
        Live.postWatch = { }
        // a tool widget not yet bound: the stream is wanted, and no widget's read reaches the network —
        // Robolectric hands a widget update to every provider, so the box offers no energy widget here
        KannStore.neu(ctx, """{"version":1,"module":[],"app":{"widgets":["geraet"],"mehr":[]}}""", null)
        shadowOf(android.appwidget.AppWidgetManager.getInstance(ctx)).createWidget(ToolWidgetProvider::class.java, R.layout.widget_tool)
    }

    @After
    fun seamsBack() {
        Live.open = openBefore
        Live.postWatch = watchBefore
    }

    private fun screen(on: Boolean) = shadowOf(ctx.getSystemService(PowerManager::class.java)).setIsInteractive(on)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun broadcast(action: String) {
        ctx.sendBroadcast(Intent(action))
        idle()
    }

    private fun start() {
        Robolectric.buildService(LiveService::class.java).create().startCommand(0, 1)
        idle()
    }

    private fun text(): String {
        val n: Notification = shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications.last()
        return n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    }

    private fun say(z: Zustand) = ctx.getString(z.text)

    private fun response(code: Int) = Response.Builder()
        .request(Request.Builder().url("https://homes.example/napi/portal/events").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("x").build()

    /** The owner's report, step by step: the screen on again and the box not answering. */
    @Test
    fun theBoxNotAnsweringAfterTheScreenComesOnIsNotPaused() {
        start()
        screen(false)
        broadcast(Intent.ACTION_SCREEN_OFF)
        assertEquals(say(Zustand.PAUSE_BILDSCHIRM), text())

        screen(true)
        broadcast(Intent.ACTION_SCREEN_ON)
        assertEquals("connecting, not paused, while the screen is on", say(Zustand.VERBINDET), text())
        listeners.last().onFailure(opened.last(), ConnectException("Connection refused"), null)
        idle()
        assertEquals(say(Zustand.BOX_WEG), text())
        assertEquals(Zustand.BOX_WEG, Verbindung.zustand(ctx))
    }

    @Test
    fun itTriesAgainWithBackoffAndNotWithTheScreenOff() {
        start()
        listeners.last().onFailure(opened.last(), ConnectException("refused"), null)
        idle()
        assertEquals(1, opened.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals("again after 2 s", 2, opened.size)
        listeners.last().onFailure(opened.last(), ConnectException("refused"), null)
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals("not again before 4 s", 2, opened.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals("again after 4 s", 3, opened.size)

        screen(false)
        broadcast(Intent.ACTION_SCREEN_OFF)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(10))
        assertEquals("no request while the screen is off", 3, opened.size)
        assertEquals("the values stay old while the screen is off", say(Zustand.BOX_WEG), text())

        screen(true)
        broadcast(Intent.ACTION_SCREEN_ON)
        assertEquals("the screen on tries at once", 4, opened.size)
        listeners.last().onOpen(opened.last(), response(200))
        idle()
        assertEquals(say(Zustand.LIVE), text())
        assertEquals(null, Verbindung.stand(ctx))
    }

    @Test
    fun aStreamThatBrokeIsConnectingUntilTheNextTryTells() {
        start()
        listeners.last().onOpen(opened.last(), response(200))
        idle()
        assertEquals(say(Zustand.LIVE), text())
        listeners.last().onFailure(opened.last(), SocketException("Connection reset"), null)
        idle()
        assertEquals(say(Zustand.VERBINDET), text())
    }

    @Test
    fun theProxyAnsweringForAMissingHomesIsTheBoxNotReachable() {
        start()
        listeners.last().onFailure(opened.last(), null, response(502))
        idle()
        assertEquals(say(Zustand.BOX_WEG), text())
    }

    @Test
    fun aRefusedTokenPointsToPairing() {
        start()
        listeners.last().onFailure(opened.last(), null, response(401))
        idle()
        assertEquals(Zustand.NICHT_GEKOPPELT, Verbindung.zustand(ctx))
    }
}
