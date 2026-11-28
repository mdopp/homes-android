package cloud.dopp.homes.teilen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import cloud.dopp.homes.R

/**
 * „Teilen per Berührung“ (owner approved 2026-11-28: „Ja, aber auch Karten
 * die ich ausgelesen habe“): another phone reading this one while nothing is
 * chosen gets nothing, and this phone asks — one heads-up notification „Ein
 * Telefon möchte etwas von dir. Was teilen?“, at most every 30 s, whose tap
 * opens the choice ([TeilenActivity]). No activity is started from the
 * background; nothing of what could be shared is in the notification.
 */
object Teilen {

    private const val KANAL = "teilen"
    private const val ID = 4711
    private const val PAUSE_MS = 30_000L

    @Volatile
    private var zuletzt = 0L

    /** Whether a request at [jetzt] asks again, after one at [vorher] (0: none yet). */
    fun fragt(jetzt: Long, vorher: Long): Boolean = vorher == 0L || jetzt - vorher >= PAUSE_MS

    /** A reader asked for a tag while nothing is chosen: the person is asked, unless asked in the last 30 s. */
    fun anfrage(ctx: Context, jetzt: Long = SystemClock.elapsedRealtime()) {
        if (!fragt(jetzt, zuletzt)) return
        zuletzt = jetzt
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(KANAL, ctx.getString(R.string.teilen_kanal), NotificationManager.IMPORTANCE_HIGH))
        }
        val tap = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, TeilenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, KANAL)
            .setSmallIcon(R.drawable.ic_stat_homes)
            .setContentTitle(ctx.getString(R.string.teilen_frage))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setTimeoutAfter(PAUSE_MS)
            .build()
        runCatching { nm.notify(ID, n) } // no permission (Android 13+): nothing shows, nothing else happens
    }

    /** The question answered: its notification goes. */
    fun weg(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(ID) }
    }
}
