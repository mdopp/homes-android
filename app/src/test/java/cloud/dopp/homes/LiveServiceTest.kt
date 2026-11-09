package cloud.dopp.homes

import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.ui.HomeActivity
import cloud.dopp.homes.widget.DeviceWidgetProvider
import cloud.dopp.homes.widget.EnergyWidgetProvider
import cloud.dopp.homes.widget.ToolWidgetProvider
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * The live widgets (#176, the owner's call: the old app's mechanism, for
 * widgets only). One foreground service holds `/napi/portal/events` while the
 * screen is on: it listens for the screen itself, connects on screen-on, drops
 * the connection on screen-off, and once connected posts the watch set and has
 * every widget it keeps current read again. A 401 stops it; the switch in the
 * app turns it off. Without a paired Homes or a widget to feed, it does not run.
 *
 * [Reach] finds the service and its seams by name (red first): `Live.open`
 * stands in for the SSE connection, `Live.postWatch` for the watch post.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveServiceTest {

    private val live = "cloud.dopp.homes.realtime.Live"
    private val serviceClass = "cloud.dopp.homes.realtime.LiveService"

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    /** A connection that never touches the network. */
    private class FakeSource(private val req: Request) : EventSource {
        var cancelled = false
        override fun request() = req
        override fun cancel() { cancelled = true }
    }

    private val opened = mutableListOf<FakeSource>()
    private val listeners = mutableListOf<EventSourceListener>()
    private var watchPosts = 0

    /** Every widget the stream keeps current. */
    private val fed: List<Pair<Class<out android.appwidget.AppWidgetProvider>, Int>> = listOf(
        EnergyWidgetProvider::class.java to R.layout.widget_energy,
        ToolWidgetProvider::class.java to R.layout.widget_tool,
        DeviceWidgetProvider::class.java to R.layout.widget_device,
    )

    @Before
    fun pairedWithWidgetsAndTheScreenOn() {
        ServerStore.setBaseUrl(ctx(), "https://homes.example")
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_T1")
        screen(true)
        runCatching {
            Reach.set(live, "open", { req: Request, l: EventSourceListener ->
                listeners += l
                FakeSource(req).also { opened += it }
            })
        }
        runCatching { Reach.set(live, "postWatch", { _: Context -> watchPosts++ }) }
    }

    @After
    fun foregroundAllowedAgain() {
        runCatching { Reach.set(live, "refuseForeground", false) }
    }

    private fun place(vararg which: Pair<Class<out android.appwidget.AppWidgetProvider>, Int>) {
        val shadow = shadowOf(AppWidgetManager.getInstance(ctx()))
        which.forEach { (cls, layout) -> shadow.createWidget(cls, layout) }
    }

    private fun screen(on: Boolean) {
        shadowOf(ctx().getSystemService(PowerManager::class.java)).setIsInteractive(on)
    }

    private fun broadcast(action: String) {
        ctx().sendBroadcast(Intent(action))
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Suppress("UNCHECKED_CAST")
    private fun start(): ServiceController<Service> {
        val cls = Class.forName(serviceClass) as Class<Service>
        val c = Robolectric.buildService(cls).create().startCommand(0, 1)
        idle()
        return c
    }

    private fun response(code: Int) = Response.Builder()
        .request(Request.Builder().url("https://homes.example/napi/portal/events").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("x").build()

    @Test
    fun screenOnConnectsScreenOffDisconnects() {
        place(fed.first())
        start()
        assertEquals("one connection while the screen is on", 1, opened.size)
        val req = opened[0].request()
        assertEquals("https://homes.example/napi/portal/events", req.url.toString())
        assertEquals("Bearer homes_device_T1", req.header("Authorization"))
        assertEquals("text/event-stream", req.header("Accept"))

        screen(false)
        broadcast(Intent.ACTION_SCREEN_OFF)
        assertTrue("screen off drops the connection", opened[0].cancelled)
        assertEquals(1, opened.size)

        screen(true)
        broadcast(Intent.ACTION_SCREEN_ON)
        broadcast(Intent.ACTION_USER_PRESENT)
        assertEquals("screen on connects again, once for both broadcasts", 2, opened.size)
        assertFalse(opened[1].cancelled)
    }

    @Test
    fun withTheScreenOffItDoesNotConnect() {
        place(fed.first())
        screen(false)
        start()
        assertEquals(0, opened.size)
    }

    /**
     * The catch-up (#137, #138): Homes sends a card only when it changes, so what
     * changed while the screen was off reaches a widget only through this. Every
     * placed instance of every fed widget is asked, each once — the energy
     * widget must not keep the morning's sun, a lamp tile not its old state.
     */
    @Test
    fun onceConnectedItPostsTheWatchSetAndReadsEveryFedWidgetAgain() {
        place(*fed.toTypedArray())
        place(fed[0], fed[0], fed[2])
        start()
        val app = shadowOf(ctx() as android.app.Application)
        app.clearBroadcastIntents()
        listeners.last().onOpen(opened.last(), response(200))
        idle()
        assertTrue("no watch post", watchPosts >= 1)
        val mgr = AppWidgetManager.getInstance(ctx())
        fed.forEach { (cls, _) ->
            val placed = mgr.getAppWidgetIds(ComponentName(ctx(), cls)).sorted()
            val asked = app.broadcastIntents.filter { it.component?.className == cls.name }.flatMap {
                it.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)?.toList()
                    ?: listOf(it.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
            }.sorted()
            assertEquals("${cls.simpleName}: every placed instance, each once", placed, asked)
        }
    }

    @Test
    fun a401StopsTheService() {
        place(fed.first())
        val c = start()
        listeners.last().onFailure(opened.last(), null, response(401))
        idle()
        assertTrue("still running after a 401", shadowOf(c.get()).isStoppedBySelf)
        assertEquals("tried again after a 401", 1, opened.size)
    }

    @Test
    fun anotherFailureIsTriedAgainWithBackoff() {
        place(fed.first())
        start()
        listeners.last().onFailure(opened.last(), null, response(502))
        idle()
        assertEquals("not at once", 1, opened.size)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertEquals("again after the first backoff", 2, opened.size)
    }

    /**
     * The „1“ on the app's icon (owner, 2026-11-04): a phone that made the old
     * channel, which showed a badge, gets a new one without — a channel's
     * settings are fixed once it is made — and the old one is gone.
     */
    @Test
    fun anOldChannelWithABadgeIsReplacedByOneWithout() {
        val nm = ctx().getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("live_widgets", "Live-Widgets", NotificationManager.IMPORTANCE_LOW))
        assertTrue(nm.getNotificationChannel("live_widgets").canShowBadge())
        place(fed.first())
        val s = shadowOf(start().get())
        val channel = nm.getNotificationChannel(s.lastForegroundNotification.channelId)
        assertFalse("shows a badge", channel.canShowBadge())
        assertTrue("the old channel is still there", nm.getNotificationChannel("live_widgets") == null)
        assertEquals(listOf(channel.id), nm.notificationChannels.map { it.id }.filter { it.startsWith("live_widgets") })
    }

    @Test
    fun withoutAWidgetToFeedItDoesNotRun() {
        val c = start()
        assertEquals(0, opened.size)
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
    }

    @Test
    fun itRunsInTheForegroundWithAQuietNotification() {
        place(fed.first())
        val c = start()
        val s = shadowOf(c.get())
        assertNotNull("no foreground notification", s.lastForegroundNotification)
        assertTrue(s.lastForegroundNotificationId != 0)
        val nm = ctx().getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(s.lastForegroundNotification.channelId)
        assertNotNull("no channel", channel)
        assertEquals("Live-Widgets", channel.name.toString())
        assertTrue("not low importance: ${channel.importance}", channel.importance <= NotificationManager.IMPORTANCE_LOW)
        assertFalse("shows a badge", channel.canShowBadge())
        val tap = shadowOf(s.lastForegroundNotification.contentIntent).savedIntent
        assertEquals(HomeActivity::class.java.name, tap.component?.className)
    }

    /** The „Live-Widgets“ switch is gone (owner, 2026-11-08): live is the one way, and an old install's stored off is ignored. */
    @Test
    fun anOldInstallsSwitchOffIsIgnored() {
        place(fed.first())
        ctx().getSharedPreferences("homes_live", Context.MODE_PRIVATE).edit().putBoolean("on", false).commit()
        start()
        assertEquals("not connected: something still reads the old switch", 1, opened.size)
        assertEquals("a live_switch in the app", 0, ctx().resources.getIdentifier("live_switch", "id", ctx().packageName))
    }

    @Test
    fun theEnergyFlowIsWatchedAndItsFramesReadTheEnergyWidgetAgain() {
        place(fed.first())
        Reach.call("cloud.dopp.homes.realtime.WatchSet", "rememberEnergy", ctx(), listOf("sensor.pv_power"))
        @Suppress("UNCHECKED_CAST")
        val watched = Reach.call("cloud.dopp.homes.realtime.WatchSet", "currentEntities", ctx()) as List<String>
        assertTrue("energy not watched: $watched", "sensor.pv_power" in watched)

        start()
        listeners.last().onOpen(opened.last(), response(200))
        idle()
        val app = shadowOf(ctx() as android.app.Application)
        app.clearBroadcastIntents()
        listeners.last().onEvent(
            opened.last(), null, "card_state",
            """{"entity_id":"sensor.pv_power","card":{"entity_id":"sensor.pv_power","domain":"sensor","state":"812"}}""",
        )
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(15))
        val asked = app.broadcastIntents.mapNotNull { it.component?.className }.toSet()
        assertTrue("energy widget not read: $asked", EnergyWidgetProvider::class.java.name in asked)
    }

    /** A frame for anything but an energy sensor reads nothing: no device widgets any more. */
    @Test
    fun anotherFrameReadsNothing() {
        place(*fed.toTypedArray())
        Reach.call("cloud.dopp.homes.realtime.WatchSet", "rememberEnergy", ctx(), listOf("sensor.pv_power"))
        start()
        listeners.last().onOpen(opened.last(), response(200))
        idle()
        val app = shadowOf(ctx() as android.app.Application)
        app.clearBroadcastIntents()
        listeners.last().onEvent(
            opened.last(), null, "card_state",
            """{"entity_id":"light.flur","card":{"entity_id":"light.flur","domain":"light","state":"on"}}""",
        )
        listeners.last().onEvent(opened.last(), null, "active_set", """{"entity_ids":["light.flur"]}""")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(15))
        assertEquals(emptyList<String>(), app.broadcastIntents.mapNotNull { it.component?.className })
    }

    /** An app update kills the process; MY_PACKAGE_REPLACED, an allowed start, brings it back. */
    @Test
    fun anUpdateStartsItAgain() {
        place(fed.first())
        val receiver = Class.forName("cloud.dopp.homes.realtime.BootReceiver").getDeclaredConstructor()
            .newInstance() as android.content.BroadcastReceiver
        receiver.onReceive(ctx(), Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        val started = shadowOf(ctx() as android.app.Application).nextStartedService
        assertEquals(serviceClass, started?.component?.className)
    }

    @Test
    fun bootStartsItAgain() {
        place(fed.first())
        val receiver = Class.forName("cloud.dopp.homes.realtime.BootReceiver").getDeclaredConstructor()
            .newInstance() as android.content.BroadcastReceiver
        receiver.onReceive(ctx(), Intent(Intent.ACTION_BOOT_COMPLETED))
        val started = shadowOf(ctx() as android.app.Application).nextStartedService
        assertEquals(serviceClass, started?.component?.className)
        assertEquals(
            ComponentName(ctx(), serviceClass),
            started?.component,
        )
    }

    // --- from the review of the live service: the screen-off guarantees ---------

    /** A reconnect pending when the screen goes off never fires, however long it stays off. */
    @Test
    fun screenOffWhileBackingOffNeverReconnects() {
        place(fed.first())
        start()
        listeners.last().onFailure(opened.last(), null, response(502))
        idle()
        screen(false)
        broadcast(Intent.ACTION_SCREEN_OFF)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofHours(2))
        assertEquals("reconnected with the screen off", 1, opened.size)
    }

    /** A failure after the screen went off but before SCREEN_OFF arrived schedules nothing. */
    @Test
    fun aFailureInTheScreenOffGapDoesNotReconnect() {
        place(fed.first())
        start()
        screen(false) // SCREEN_OFF not delivered yet
        listeners.last().onFailure(opened.last(), null, response(502))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(10))
        assertEquals("reconnected with the screen off", 1, opened.size)
    }

    /** The watch set is re-posted every 20 minutes while the screen is on, and never with it off. */
    @Test
    fun theWatchRepostRunsOnlyWithTheScreenOn() {
        place(fed.first())
        start()
        listeners.last().onOpen(opened.last(), response(200))
        idle()
        val first = watchPosts
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(21))
        assertEquals("no re-post after 20 min", first + 1, watchPosts)
        screen(false)
        broadcast(Intent.ACTION_SCREEN_OFF)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofHours(3))
        assertEquals("re-posted with the screen off", first + 1, watchPosts)
    }

    /** The open of a stream screen-off already dropped neither resyncs nor posts. */
    @Test
    fun aLateOpenAfterScreenOffIsIgnored() {
        place(fed.first())
        start()
        val l = listeners.last()
        val s = opened.last()
        screen(false)
        broadcast(Intent.ACTION_SCREEN_OFF)
        val app = shadowOf(ctx() as android.app.Application)
        app.clearBroadcastIntents()
        l.onOpen(s, response(200))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofHours(1))
        assertEquals("a late open posted the watch set", 0, watchPosts)
        assertTrue("a late open resynced", app.broadcastIntents.none { it.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE })
    }

    /** Destroying the service closes the stream and stops listening for the screen. */
    @Test
    fun destroyClosesTheStreamAndStopsListening() {
        place(fed.first())
        val c = start()
        c.destroy()
        idle()
        assertTrue("stream left open after onDestroy", opened.last().cancelled)
        screen(false)
        broadcast(Intent.ACTION_SCREEN_OFF)
        screen(true)
        broadcast(Intent.ACTION_SCREEN_ON)
        assertEquals("a destroyed service connected", 1, opened.size)
    }

    /** It asks Android to start it again when the process dies. */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun itIsSticky() {
        place(fed.first())
        val s = Robolectric.buildService(Class.forName(serviceClass) as Class<Service>).create().get()
        assertEquals(Service.START_STICKY, s.onStartCommand(null, 0, 1))
    }

    /** When Android refuses the foreground start (a restart from the background), no notification is left behind. */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun aRefusedForegroundLeavesNoNotification() {
        place(fed.first())
        Reach.set(live, "refuseForeground", true)
        screen(false)
        val c = Robolectric.buildService(Class.forName(serviceClass) as Class<Service>).create()
        c.get().onStartCommand(null, 0, 1)
        idle()
        val nm = shadowOf(ctx().getSystemService(NotificationManager::class.java))
        assertEquals(
            "orphan notification: ${nm.allNotifications.map { it.extras.getString("android.text") }}",
            0, nm.allNotifications.size,
        )
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
    }

    /** Stopping removes the notification, too (a 401 after it was shown). */
    @Test
    fun stoppingRemovesTheNotification() {
        place(fed.first())
        start()
        listeners.last().onOpen(opened.last(), response(200))
        idle()
        listeners.last().onFailure(opened.last(), null, response(401))
        idle()
        val nm = shadowOf(ctx().getSystemService(NotificationManager::class.java))
        assertEquals("a notification after the stop", 0, nm.allNotifications.size)
    }

    /** Only a session that stayed up past the healthy threshold resets the backoff. */
    @Test
    fun theBackoffResetsOnlyAfterAHealthySession() {
        place(fed.first())
        start()
        val looper = shadowOf(Looper.getMainLooper())
        // two quick failures: 2 s, then 4 s
        listeners.last().onFailure(opened.last(), null, response(502))
        looper.idleFor(java.time.Duration.ofSeconds(2))
        assertEquals(2, opened.size)
        listeners.last().onFailure(opened.last(), null, response(502))
        looper.idleFor(java.time.Duration.ofSeconds(3))
        assertEquals("the second retry came before 4 s", 2, opened.size)
        looper.idleFor(java.time.Duration.ofSeconds(1))
        assertEquals(3, opened.size)
        // a healthy session: open, then 121 s up, then lost — back to 2 s
        listeners.last().onOpen(opened.last(), response(200))
        looper.idleFor(java.time.Duration.ofSeconds(121))
        listeners.last().onFailure(opened.last(), null, response(502))
        looper.idleFor(java.time.Duration.ofSeconds(2))
        assertEquals("the backoff did not reset after a healthy session", 4, opened.size)
    }

    /** Homes pings over HTTP/1.1, where OkHttp's ping does nothing: a silent dead path is caught by the read timeout. */
    @Test
    fun aSilentlyDeadConnectionTimesOut() {
        val client = Reach.get(Reach.obj(live), "client") as okhttp3.OkHttpClient
        val homesPingMs = 90_000
        assertTrue("read timeout ${client.readTimeoutMillis} ms", client.readTimeoutMillis in (2 * homesPingMs)..(3 * homesPingMs))
    }

    /** Placing a fed widget from the tray runs its onUpdate, which may not start a foreground service. */
    @Test
    fun aWidgetUpdateDoesNotStartTheService() {
        val app = shadowOf(ctx() as android.app.Application)
        fed.forEach { (cls, layout) ->
            while (app.nextStartedService != null) { /* drain */ }
            shadowOf(AppWidgetManager.getInstance(ctx())).createWidget(cls, layout)
            idle()
            assertEquals("${cls.simpleName}.onUpdate started it", null, app.nextStartedService?.component?.className)
        }
    }

    /** Removing a widget may stop the service, never start it: that broadcast is no allowed start. */
    @Test
    fun removingAWidgetNeverStartsTheService() {
        val app = shadowOf(ctx() as android.app.Application)
        val mgr = AppWidgetManager.getInstance(ctx())
        place(*fed.toTypedArray())
        fed.forEach { (cls, _) ->
            while (app.nextStartedService != null) { /* drain */ }
            val id = mgr.getAppWidgetIds(ComponentName(ctx(), cls)).first()
            cls.getDeclaredConstructor().newInstance().onDeleted(ctx(), intArrayOf(id))
            idle()
            assertEquals("${cls.simpleName}.onDeleted started it", null, app.nextStartedService?.component?.className)
        }
    }

    /** The start is a foreground-service start: a plain startService from a widget tap is refused on Android 8+. */
    @Test
    fun ensureStartsAForegroundService() {
        val src = java.io.File(RepoFiles.main, "java/cloud/dopp/homes/realtime/LiveService.kt").readText()
        val ensure = src.substringAfter("fun ensure(").substringBefore("\n    }")
        assertTrue(ensure, ensure.contains("startForegroundService("))
        assertTrue(ensure, !Regex("""[^d]startService\(""").containsMatchIn(ensure))
    }
}
