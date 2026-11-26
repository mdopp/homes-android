package cloud.dopp.homes.ausloeser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import kotlin.concurrent.thread

/**
 * „Ladestation melden“ (homes ADR 0004 §1): while the live service runs
 * ([cloud.dopp.homes.realtime.LiveService], the one that already does) and the
 * switch is on, a receiver registered in it hears the phone begin and stop
 * charging and sends the one `POST /napi/ausloeser` `{art: laden}` — also with
 * the screen off. It is the app's second exception to „no network while the
 * screen is off“ (the first is Daten sichern), pending the owner's yes (ADR
 * 0004 open question 2, AGENTS.md). No job, alarm, worker or wakelock: the
 * service's foreground process carries the one request, and a phone that just
 * began charging is out of Doze anyway. Android has no manifest receiver for
 * these broadcasts since 8.0; this one exists only while the service does.
 */
class Ladestation(private val service: Context) {

    private var registriert = false

    private val empfaenger = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val an = when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> true
                Intent.ACTION_POWER_DISCONNECTED -> false
                else -> return
            }
            melden(context.applicationContext, an, if (an) Ausloeser.ladeart(eingesteckt(context)) else Napi.Ladeart.UNBEKANNT)
        }
    }

    /** Listen while the switch is on, else not; called on each start of the service and when the switch changes. */
    fun anpassen() {
        val soll = Ausloeser.ladenMelden(service)
        if (soll == registriert) return
        if (soll) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            ContextCompat.registerReceiver(service, empfaenger, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            runCatching { service.unregisterReceiver(empfaenger) }
        }
        registriert = soll
    }

    /** The service ends: nothing listens any more. */
    fun aus() {
        if (registriert) runCatching { service.unregisterReceiver(empfaenger) }
        registriert = false
    }

    companion object {
        /** Sends the report. Replaced in tests, which run it in place. */
        @VisibleForTesting
        @Volatile
        var senden: (Context, () -> Unit) -> Unit = { _, lauf -> thread(name = "ladestation") { lauf() } }

        /** How the phone is plugged in now: the sticky battery broadcast says it; POWER_CONNECTED does not. */
        private fun eingesteckt(ctx: Context): Int =
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) // a protected broadcast, read only
                ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

        /** One request, its outcome kept as the screen's line. Unpaired: nothing goes, and the line says so. */
        fun melden(app: Context, an: Boolean, ladeart: Napi.Ladeart) {
            val base = ServerStore.baseUrl(app)
            val token = TokenStore.get(app)
            if (base == null || token.isNullOrBlank()) {
                Ausloeser.merke(app, Ausloeser.NICHT_GEKOPPELT, System.currentTimeMillis())
                return
            }
            senden(app) {
                val ausgang = AusloeserClient(base, token).laden(an, ladeart)
                Ausloeser.merke(app, Ausloeser.ladenZeile(an, ladeart, ausgang), System.currentTimeMillis())
            }
        }
    }
}
