package cloud.dopp.homes.data

import android.content.Context
import android.os.PowerManager
import java.io.IOException

/**
 * With the screen off nothing goes over the network for the widgets
 * (docs/CONTRACT.md (c)). The live stream holds that by itself; this is the
 * gate every other widget request to Homes passes, in [ApiClient] and
 * [BoxApiClient] (the assistant's name). The backup job ([SicherungClient], (e)) is the one widget-free
 * caller that does not: it runs while the phone charges, mostly with the screen off. The second,
 * pending the owner's yes (homes ADR 0004 open question 2), is „Ladestation melden“'s one request
 * when the phone begins or stops charging (`ausloeser/Ladestation.kt`, behind its switch). A widget update Android
 * delivers late, after the screen went off, reads nothing and keeps its last
 * card; the stream's resync redraws every fed widget when the screen is on
 * again. Seen on API 31 in the live e2e, step 2: a WATCH 4 s after screen off.
 */
object ScreenGate {

    /** Instead of a request while the screen is off: an IOException, so every caller treats it as offline. */
    class ScreenOffException : IOException("screen off: no request to Homes")

    /** Whether a request may go out now. Unknown counts as on: no PowerManager, no gate. */
    fun open(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    /** Throws [ScreenOffException] while the screen is off. */
    fun check(ctx: Context) {
        if (!open(ctx)) throw ScreenOffException()
    }
}
