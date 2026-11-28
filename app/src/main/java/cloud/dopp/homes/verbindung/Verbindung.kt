package cloud.dopp.homes.verbindung

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import cloud.dopp.homes.R
import cloud.dopp.homes.data.HomesHttp
import cloud.dopp.homes.data.ScreenGate
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.realtime.Live
import cloud.dopp.homes.widget.DeviceWidgetProvider
import okhttp3.OkHttpClient
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The one source of what the app says about its line to Homes ([Zustand]):
 * the live notification, the start screen and the widgets read it here, none
 * guesses on its own. What it knows are facts the requests leave behind — the
 * live stream ([live]) and every read of [cloud.dopp.homes.data.ApiClient]
 * ([gemeldet]) —, kept across a killed process; the screen, the pairing and
 * whether the stream is wanted it looks up each time. Nothing here sends a
 * request: with the screen off it only remembers.
 */
object Verbindung {

    /** App-internal: the answer changed. The start screen and the live notification draw again. */
    const val GEAENDERT = "cloud.dopp.homes.verbindung.GEAENDERT"

    private const val PREFS = "homes_verbindung"
    private const val FEHLER = "fehler"
    private const val LIVE = "live"
    private const val STAND = "stand"

    private fun p(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The answer now. */
    fun zustand(ctx: Context): Zustand {
        val p = p(ctx)
        return Zustand.aus(
            gekoppelt = ServerStore.isConfigured(ctx) && TokenStore.isPaired(ctx),
            bildschirm = ScreenGate.open(ctx),
            gewollt = Live.wanted(ctx),
            live = p.getBoolean(LIVE, false),
            fehler = p.getString(FEHLER, null)?.let { f -> Zustand.values().firstOrNull { it.name == f } },
        )
    }

    /** The live stream opened ([offen]: Homes is there, now) or ended. */
    fun live(ctx: Context, offen: Boolean) = schreiben(ctx) { p ->
        if (offen) remove(FEHLER)
        if (offen || p.getBoolean(LIVE, false)) putLong(STAND, System.currentTimeMillis())
        putBoolean(LIVE, offen)
    }

    /**
     * A request to Homes ended: with [t] when no answer came, with [code]
     * when one did. A failure is kept as what it says ([Zustand.ausFehler]);
     * Homes' answer clears it.
     */
    fun ergebnis(ctx: Context, t: Throwable?, code: Int?) {
        val fehler = Zustand.ausFehler(t, code, netz(ctx))
        when {
            fehler != null -> schreiben(ctx) { putString(FEHLER, fehler.name) }
            t == null && code != null -> schreiben(ctx) {
                remove(FEHLER)
                putLong(STAND, System.currentTimeMillis())
            }
        }
    }

    /** Whether the phone has a network at all; without the system's answer, yes. */
    private fun netz(ctx: Context): Boolean =
        ctx.getSystemService(ConnectivityManager::class.java)?.let { it.activeNetwork != null } ?: true

    /** [client] telling [ergebnis] how each of its requests ended. */
    fun gemeldet(ctx: Context, client: OkHttpClient): OkHttpClient {
        val app = ctx.applicationContext
        return HomesHttp.mit(client) { chain ->
            val resp = try {
                chain.proceed(chain.request())
            } catch (e: IOException) {
                ergebnis(app, e, null)
                throw e
            }
            ergebnis(app, null, resp.code)
            resp
        }
    }

    /**
     * „Stand 08:14“ while the values on screen are the last ones known
     * ([Zustand.alt]); null while they are current.
     */
    fun stand(ctx: Context): String? {
        // a failed request, not the pairing missing: unpaired, the widgets say „koppeln“ themselves
        if (!zustand(ctx).alt || p(ctx).getString(FEHLER, null) == null) return null
        val ms = p(ctx).getLong(STAND, 0L)
        if (ms <= 0L) return ctx.getString(R.string.verbindung_offline)
        return ctx.getString(R.string.verbindung_stand, zeit(ms, System.currentTimeMillis()))
    }

    /** [ms] as the widgets say it: „08:14“ on the day of [jetzt], else „03.11. 08:14“. Pure. */
    fun zeit(ms: Long, jetzt: Long, zone: TimeZone = TimeZone.getDefault()): String {
        fun f(muster: String) = SimpleDateFormat(muster, Locale.GERMANY).apply { timeZone = zone }
        val tag = f("yyyyMMdd")
        return f(if (tag.format(Date(ms)) == tag.format(Date(jetzt))) "HH:mm" else "dd.MM. HH:mm").format(Date(ms))
    }

    /**
     * The start screen's line about the line (owner, 2026-11-04): there while
     * the values are not current ([Zustand.alt]), gone while live, connecting
     * or paused; drawn again on each change while [activity] is in front. A
     * tap while Homes refuses the token runs [neuKoppeln].
     */
    fun zeigen(activity: ComponentActivity, view: TextView, neuKoppeln: () -> Unit) {
        fun zeichnen() {
            val z = zustand(activity)
            view.visibility = if (z.alt) View.VISIBLE else View.GONE
            view.text = listOfNotNull(activity.getString(z.text), stand(activity)).joinToString("\n")
            view.setOnClickListener(if (z == Zustand.NICHT_GEKOPPELT) View.OnClickListener { neuKoppeln() } else null)
            view.isClickable = z == Zustand.NICHT_GEKOPPELT
        }
        val empfaenger = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = zeichnen()
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                ContextCompat.registerReceiver(activity, empfaenger, IntentFilter(GEAENDERT), ContextCompat.RECEIVER_NOT_EXPORTED)
                zeichnen()
            }

            override fun onPause(owner: LifecycleOwner) {
                runCatching { activity.unregisterReceiver(empfaenger) }
            }
        })
    }

    /** Change the facts; when the answer changed, say so, and when the widgets' values became old or current, redraw them. */
    @Synchronized
    private fun schreiben(ctx: Context, aendern: SharedPreferences.Editor.(SharedPreferences) -> Unit) {
        val vorher = zustand(ctx)
        val p = p(ctx)
        p.edit { aendern(p) }
        val nachher = zustand(ctx)
        if (nachher == vorher) return
        runCatching { ctx.sendBroadcast(Intent(GEAENDERT).setPackage(ctx.packageName)) }
        if (nachher.alt == vorher.alt) return
        runCatching { DeviceWidgetProvider.zeichnen(ctx) }
    }
}
