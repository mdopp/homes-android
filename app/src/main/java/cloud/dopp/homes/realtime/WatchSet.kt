package cloud.dopp.homes.realtime

import android.content.Context
import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.ScreenGate
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.widget.DeviceWidgetProvider
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The device's **watch set** (#48): the Home Assistant entities its widgets
 * show, posted to `POST /napi/portal/watch` so Homes streams `card_state` for
 * exactly those entities over `/napi/portal/events`: the device of every
 * device widget (the energy widget went, owner 2026-11-28).
 *
 * Best-effort and guarded: it posts only when paired, off the caller's thread,
 * and a failure never touches the widgets. Homes answers 503 while Home
 * Assistant is down; then the post is tried again with backoff
 * ([RealtimeProtocol.watchRetryMs]) while the screen stays on, and a newer post
 * replaces an older one's retries.
 */
object WatchSet {

    /** Bumped by every post: a retry loop that sees a newer one stops. */
    private val generation = AtomicInteger()

    /** The current watch set: the device widgets' entities, non-blank, de-duplicated. */
    fun currentEntities(ctx: Context): List<String> =
        DeviceWidgetProvider.boundEntities(ctx.applicationContext).filter { it.isNotBlank() }.distinct()

    /** What the watch post sends: [currentEntities]. */
    fun body(ctx: Context): JSONObject = ApiClient.watchBody(currentEntities(ctx))

    /** Paired + configured — the only state in which a post can succeed. */
    private fun eligible(ctx: Context): Boolean =
        ServerStore.isConfigured(ctx) && TokenStore.isPaired(ctx)

    private fun screenOn(ctx: Context): Boolean = ScreenGate.open(ctx)

    /**
     * Post the current widget entity set on a background thread. No-op when not
     * paired or while the screen is off: the stream posts it when the screen is
     * on again (LiveService's resync). A 503 is retried with backoff while the screen is on and no newer
     * post started; any other failure is dropped.
     */
    fun postCurrentAsync(ctx: Context) {
        val app = ctx.applicationContext
        if (!eligible(app) || !screenOn(app)) return
        val mine = generation.incrementAndGet()
        thread {
            var attempt = 0
            while (true) {
                val code = runCatching { ApiClient(app).postWatch(body(app)) }.getOrDefault(0)
                val wait = RealtimeProtocol.watchRetryMs(code, attempt++) ?: break
                Thread.sleep(wait)
                if (generation.get() != mine || !eligible(app) || !screenOn(app)) break
            }
        }
    }
}
