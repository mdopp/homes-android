package cloud.dopp.homes.realtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import androidx.core.os.HandlerCompat
import cloud.dopp.homes.konto.Anstoss
import cloud.dopp.homes.konto.HomesKonto
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cloud.dopp.homes.R
import cloud.dopp.homes.kann.KannLeser
import cloud.dopp.homes.data.Assistent
import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.data.request
import cloud.dopp.homes.ui.HomeActivity
import cloud.dopp.homes.widget.DeviceWidgetProvider
import cloud.dopp.homes.widget.EnergyWidgetProvider
import cloud.dopp.homes.widget.ToolWidgetProvider
import cloud.dopp.homes.data.HomesHttp
import cloud.dopp.homes.verbindung.Verbindung
import cloud.dopp.homes.verbindung.Zustand
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * When the live widgets run, and how they are started (#176: the old app's
 * mechanism, widgets only). They run while the app is paired and a widget the
 * stream keeps current ([FED]) is placed — live is the one way (owner,
 * 2026-11-08: the „Live-Widgets“ switch is gone; its stored value is ignored);
 * [LiveService] then decides by the screen whether to hold the connection.
 */
object Live {

    /** The widgets the stream keeps current: frames, refetches on frames, or the screen-on resync. */
    val FED: List<Class<out AppWidgetProvider>> = listOf(
        DeviceWidgetProvider::class.java, EnergyWidgetProvider::class.java, ToolWidgetProvider::class.java,
    )

    /**
     * Homes writes `: ping` every 90 s on HTTP/1.1, where OkHttp's own ping does
     * nothing. A read timeout of three pings turns a silently dead path (a router
     * reboot, a lost NAT entry) into a failure, and the service reconnects.
     */
    val client: OkHttpClient by lazy {
        HomesHttp.client(connect = 15, read = 240)
    }

    /** Opens the stream. Replaced in tests. */
    @Volatile
    var open: (Request, EventSourceListener) -> EventSource =
        { req, listener -> EventSources.createFactory(client).newEventSource(req, listener) }

    /** Posts the watch set. Replaced in tests. */
    @Volatile
    var postWatch: (Context) -> Unit = { WatchSet.postCurrentAsync(it) }

    /** Makes the foreground start fail, as Android refuses it outside an allowed start. Tests only. */
    @VisibleForTesting
    @Volatile
    var refuseForeground = false

    private fun fedPlaced(ctx: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return false
        return FED.any { runCatching { mgr.getAppWidgetIds(ComponentName(ctx, it)) }.getOrNull()?.isNotEmpty() == true }
    }

    fun wanted(ctx: Context): Boolean = ServerStore.isConfigured(ctx) && TokenStore.isPaired(ctx) && fedPlaced(ctx)

    /**
     * On the battery-optimization exemption list: the documented exemption that
     * lets Android's restart of the service after its process died
     * (`START_STICKY`) go foreground on Android 12+, as it let the old app recover.
     */
    fun exempt(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    /**
     * Start the service when it is wanted, else stop it. Android 12+ lets an app
     * start a foreground service only from an allowed start: the app's screen, a
     * configure screen, a widget tap, boot, an app update — or anywhere while the
     * app is exempt from battery optimization. So call this from those;
     * elsewhere the start throws `ForegroundServiceStartNotAllowedException`,
     * which is swallowed, as in the old app.
     */
    fun ensure(ctx: Context) {
        val app = ctx.applicationContext
        val intent = Intent(app, LiveService::class.java)
        if (wanted(app)) {
            runCatching { ContextCompat.startForegroundService(app, intent) }
        } else {
            runCatching { app.stopService(intent) }
        }
    }

    /** Look again from anywhere (a widget removed): stops the service when it is no longer wanted, never starts it. */
    fun recheck(ctx: Context) {
        val app = ctx.applicationContext
        if (!wanted(app)) runCatching { app.stopService(Intent(app, LiveService::class.java)) }
    }
}

/**
 * The live widgets' foreground service: it holds `GET /napi/portal/events`
 * (`card_state`) while the screen is on and nothing while it is off.
 *
 * - It listens for the screen itself — a foreground component gets the
 *   broadcasts, a cached process does not.
 * - Screen on: connect. Once connected: post the watch set and have every fed
 *   widget read again, since Homes sends a card only when it changes.
 * - Screen off: drop the connection. Nothing polls; a widget nobody sees
 *   needs no update.
 * - A card draws the device widgets of its entity, no read; a card of an
 *   energy sensor has the energy widget read again, coalesced.
 * - A 401 stops the service (the device was removed in Homes); any other loss
 *   reconnects with backoff, reset only after a healthy session.
 * - What happened goes to [Verbindung], and the notification says its answer:
 *   „Pausiert, Bildschirm aus“ only while the screen is off, „Box nicht
 *   erreichbar“ while a connect fails with the network up.
 * - After its process died, Android restarts it (`START_STICKY`). That restart
 *   is a background start, which Android 12+ allows documentedly only while
 *   the app is exempt from battery optimization ([Live.exempt]); the app asks
 *   for it once after pairing and shows the state beside its switch. (The
 *   emulators of the e2e, API 31 to 35, let the restart go foreground without
 *   it too — it keeps the allowance of the start it repeats — but that is not
 *   documented, and an OEM build may refuse it.) A refused start ends the
 *   service quietly, without a notification, until the next allowed start.
 *   [BootReceiver] starts it after a reboot and after an app update. No rescue
 *   worker, alarm, wakelock or poll.
 */
class LiveService : Service() {

    private val main = Handler(Looper.getMainLooper())

    /** When a `gemeinsam` frame syncs the Homes account (#209). */
    private val anstoss = Anstoss()
    private var source: EventSource? = null
    private var attempt = 0
    private var connectedAtMs = 0L
    private var status: Zustand? = null
    private var inForeground = false
    private var energyQueued = false

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    attempt = 0
                    connect()
                }
                Intent.ACTION_SCREEN_OFF -> drop()
            }
            show()
        }
    }

    /** A network came (back): with the screen on and no stream, connect now, not after the backoff. */
    private val netz = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post {
                if (source != null || !interactive() || !Live.wanted(this@LiveService)) return@post
                attempt = 0
                connect()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Verbindung.live(this, false) // a stream of a killed process is gone
        if (!foreground()) {
            stopSelf() // refused: nothing to undo, nothing shown
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Verbindung.GEAENDERT)
        }
        ContextCompat.registerReceiver(this, screen, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(netz) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foreground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Live.wanted(this)) {
            stop()
            return START_NOT_STICKY
        }
        if (interactive()) connect() else drop()
        show()
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screen) }
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(netz) }
        runCatching { source?.cancel() }
        source = null
        Verbindung.live(this, false)
        super.onDestroy()
    }

    private fun interactive(): Boolean = getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private fun connect() {
        if (source != null) return // open or opening: SCREEN_ON and USER_PRESENT both come on unlock
        val base = ServerStore.baseUrl(this)
        val token = TokenStore.get(this)
        if (base == null || token == null) {
            stop()
            return
        }
        main.removeCallbacks(reconnect)
        connectedAtMs = 0L
        source = Live.open(Napi.EVENTS.request(base, token), listener)
    }

    private fun drop() {
        main.removeCallbacksAndMessages(null)
        anstoss.vergiss()
        runCatching { source?.cancel() }
        source = null
        connectedAtMs = 0L
        energyQueued = false
        Verbindung.live(this, false)
    }

    private fun stop() {
        inForeground = false
        drop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
        stopSelf()
    }

    /** What changed while nobody listened: the watch set, then every fed widget reads again. */
    private fun resync() {
        Live.postWatch(this)
        main.removeCallbacks(watchRepost)
        main.postDelayed(watchRepost, WATCH_REPOST_MS)
        Live.FED.forEach { update(it) }
    }

    /** Ask every placed [provider] widget to read again, through its own onUpdate. */
    private fun update(provider: Class<out AppWidgetProvider>) {
        val ids = AppWidgetManager.getInstance(this).getAppWidgetIds(ComponentName(this, provider))
        if (ids.isEmpty()) return
        sendBroadcast(
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setComponent(ComponentName(this, provider))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
        )
    }

    private val watchRepost = object : Runnable {
        override fun run() {
            Live.postWatch(this@LiveService)
            main.postDelayed(this, WATCH_REPOST_MS)
        }
    }

    private val energyRefresh = Runnable {
        energyQueued = false
        update(EnergyWidgetProvider::class.java)
    }

    private val reconnect = Runnable { if (Live.wanted(this)) connect() else stop() }

    private val listener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            main.post {
                if (eventSource !== source) return@post
                connectedAtMs = SystemClock.elapsedRealtime()
                Verbindung.live(this@LiveService, true)
                show()
                resync()
                // a restarted Homes may offer something else (homes ADR 0001 §3): a 304 when not
                KannLeser.lesen(this@LiveService, immer = true)
            }
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            val ev = RealtimeProtocol.frame(type, data) ?: return
            main.post {
                if (eventSource !== source) return@post
                when (ev) {
                    is RealtimeProtocol.GemeinsamEvent -> gemeinsam(ev.teil)
                    is RealtimeProtocol.CardStateEvent -> {
                        runCatching { DeviceWidgetProvider.wakeEntity(this@LiveService, ev.entityId, ev.card) }
                        // a sensor comes every 10 s at most: at most one energy refetch in that time
                        if (!energyQueued && ev.entityId in WatchSet.energy(this@LiveService)) {
                            energyQueued = true
                            main.postDelayed(energyRefresh, ENERGY_REFRESH_MS)
                        }
                    }
                }
            }
        }

        override fun onClosed(eventSource: EventSource) {
            main.post { gone(eventSource) }
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            main.post {
                if (eventSource !== source) return@post
                // a stream that was open and broke says nothing yet: the next connect tells
                if (connectedAtMs == 0L || response?.code == 401) Verbindung.ergebnis(this@LiveService, t, response?.code)
                if (response?.code == 401) stop() else gone(eventSource)
            }
        }
    }

    /** Homes' shared data changed (#209): the Homes account syncs that part, at most once per 30 s burst. */
    private fun gemeinsam(teil: HomesKonto.Teil) {
        if (!HomesKonto.an(this, teil)) return
        val nach = anstoss.wann(teil, SystemClock.elapsedRealtime()) ?: return
        if (nach == 0L) {
            HomesKonto.anstossen(this, teil)
            return
        }
        // the part itself is the token: one later sync per part, taken back when the stream ends
        HandlerCompat.postDelayed(main, {
            anstoss.erledigt(teil, SystemClock.elapsedRealtime())
            if (source != null) HomesKonto.anstossen(this, teil)
        }, teil, nach)
    }

    private fun gone(eventSource: EventSource) {
        if (eventSource !== source) return
        source = null
        HomesKonto.Teil.values().forEach { main.removeCallbacksAndMessages(it) }
        anstoss.vergiss()
        main.removeCallbacks(watchRepost)
        if (RealtimeProtocol.healthySession(connectedAtMs, SystemClock.elapsedRealtime())) attempt = 0
        connectedAtMs = 0L
        Verbindung.live(this, false)
        show()
        if (!interactive()) return
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, RealtimeProtocol.backoffMillis(attempt++))
    }

    /** Go foreground; false when Android refuses (a start outside an allowed context). */
    private fun foreground(): Boolean = try {
        check(!Live.refuseForeground) { "refused" }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        inForeground = true
        true
    } catch (e: Exception) {
        false
    }

    /** Show [Verbindung]'s answer, re-posting only on a change and only while in the foreground. */
    private fun show() {
        val now = Verbindung.zustand(this)
        if (now == status) return
        status = now
        if (!inForeground) return
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification()) }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm?.getNotificationChannel(CHANNEL) == null) {
            runCatching { nm?.deleteNotificationChannel("live_widgets") } // v1 showed a badge; a channel is fixed once made
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.live_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.live_channel_desc)
                    setShowBadge(false) // nothing to act on: no „1“ on the app's icon (owner, 2026-11-04)
                },
            )
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_homes)
            .setContentTitle(Assistent.name(this))
            .setContentText(getString((status ?: Verbindung.zustand(this)).text))
            .setContentIntent(tap)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL = "live_widgets_v2"
        private const val NOTIFICATION_ID = 4801

        /** Re-post the watch set well inside Homes' one-hour TTL. */
        private const val WATCH_REPOST_MS = 20L * 60L * 1000L

        /** Homes sends a sensor's card at most every 10 s: the energy widget reads at most that often. */
        private const val ENERGY_REFRESH_MS = 10_000L
    }
}

/**
 * Starts the live widgets again after a reboot and after an app update (which
 * kills the process), when they are wanted. Both are allowed starts.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Live.ensure(context)
            // Standortverlauf: Android forgets location requests over a reboot and an update
            cloud.dopp.homes.standort.Aufzeichnung.nachStart(context)
        }
    }
}
