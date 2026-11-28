package cloud.dopp.homes.teilen

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import cloud.dopp.homes.R
import cloud.dopp.homes.ausloeser.WlanAngebot
import kotlin.concurrent.thread

/**
 * „Teilen per Berührung“ (owner approved 2026-11-28: „Ja, aber auch Karten
 * die ich ausgelesen habe“): another phone reading this one while nothing is
 * chosen gets nothing, and this phone asks — one heads-up notification „Ein
 * Telefon möchte etwas von dir. Was teilen?“, at most every 30 s, whose tap
 * opens „Teilen“ ([TeilenActivity]) — or, with „Bei Berührung Kontakt
 * austauschen“ on, opens „Was teilen?“ of the session the touch starts ([Austausch]) at once, on the touch:
 * from the app's screen in front ([Vorne]), else from the HCE service Android's NFC stack still has bound —
 * a start Android allows (field failure 2026-11-28: the notification alone was not seen). Only if that start is
 * refused, or „Was teilen?“ is not in front within [ERSCHEINT_MS], its notification comes instead; it stays until
 * answered or the session ends. Nothing of what could be shared is in a notification.
 */
object Teilen {

    private const val KANAL = "teilen-frage"
    private const val KANAL_ALT = "teilen" // created with the importance it had then, which Android keeps
    private const val ID = 4711
    private const val PAUSE_MS = 30_000L

    /** How long „Was teilen?“ may take to come to the front before its notification comes instead. */
    const val ERSCHEINT_MS = 1_500L

    /** When the person was asked last (elapsed realtime); 0: not yet. Set in tests. */
    @VisibleForTesting
    @Volatile
    var zuletzt = 0L

    /** „Was teilen?“ is in front ([vorn]). */
    @Volatile
    private var wasTeilenVorn = false

    /** „Was teilen?“ came to the front since the last touch opened it. */
    @Volatile
    private var erschienen = false

    private fun main() = Handler(Looper.getMainLooper())

    /** Whether a request at [jetzt] asks again, after one at [vorher] (0: none yet). */
    fun fragt(jetzt: Long, vorher: Long): Boolean = vorher == 0L || jetzt - vorher >= PAUSE_MS

    /**
     * A reader asked for a tag while nothing is chosen; [ctx] is the HCE service it is bound to. The person is asked,
     * unless asked in the last 30 s. With „Bei Berührung Kontakt austauschen“ on (and paired), „Was teilen?“ opens
     * now, and a session starts: its link answers the reader still held to this phone (once, two minutes). A session
     * still going, but not on the screen, is ended for it; one whose time is up no longer counts.
     */
    fun anfrage(ctx: Context, jetzt: Long = SystemClock.elapsedRealtime()) {
        if (!fragt(jetzt, zuletzt)) return
        Austausch.aufraeumen()
        if (wasTeilenVorn && (Austausch.jetzt != null || Austausch.startet)) return // its own screen is there, with its [NFC]
        zuletzt = jetzt
        val app = ctx.applicationContext
        val c = Austausch.client(app)?.takeIf { Austausch.beiBeruehrung(app) }
            ?: return melden(app, app.getString(R.string.teilen_frage), TeilenActivity::class.java)
        val alt = Austausch.jetzt
        Austausch.jetzt = null
        Austausch.gescheitert = null
        Austausch.startet = true
        oeffnen(ctx)
        thread(name = "austausch-beruehrt") {
            val a = c.neu(Austausch.karte(app).first)
            if (a is AustauschClient.Antwort.Neu) {
                Austausch.jetzt = Austausch.Sitzung(a.id, a.link, a.bis)
                WlanAngebot.vorbereiten(app.getString(R.string.austausch_titel), AustauschActivity.ndef(a.link), nurEinmal = true)
                WlanAngebot.anfangen()
            }
            main().post { gestartet(app, a) }
            alt?.let { c.beenden(it.id) }
        }
    }

    /** „Was teilen?“ now: from the screen in front, else from the bound service [ctx]; its notification if refused. */
    private fun oeffnen(ctx: Context) {
        val app = ctx.applicationContext
        erschienen = false
        val von: Context = Vorne.aktivitaet() ?: ctx
        val i = Intent(von, AustauschActivity::class.java).putExtra(AustauschActivity.BERUEHRT, true)
        if (von !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { von.startActivity(i) }.isFailure) return wasTeilen(app)
        main().postDelayed({ if (!erschienen) wasTeilen(app) }, ERSCHEINT_MS) // a refused start throws nothing
    }

    /** The touch's session started or not ([a]), on the main thread: „Was teilen?“ waiting for it is told. */
    private fun gestartet(app: Context, a: AustauschClient.Antwort) {
        Austausch.startet = false
        val s = Austausch.jetzt
        if (a is AustauschClient.Antwort.Neu && s != null) ablaufen(app, s) else Austausch.gescheitert = a
        Austausch.wennGestartet?.invoke(a) // not open (yet): it reads [Austausch.gescheitert] when it comes
    }

    /** „Was teilen?“ came to the front ([an]) or went; in front, its notification goes. */
    fun vorn(ctx: Context, an: Boolean) {
        wasTeilenVorn = an
        if (!an) return
        erschienen = true
        weg(ctx)
    }

    /** At the end of [s]'s time: no longer the session going, its question gone. */
    private fun ablaufen(app: Context, s: Austausch.Sitzung) {
        val bis = AustauschActivity.bisMs(s.bis) ?: return
        main().postDelayed({
            if (Austausch.jetzt === s) {
                Austausch.jetzt = null
                weg(app)
            }
        }, maxOf(0L, bis - System.currentTimeMillis()))
    }

    private fun wasTeilen(app: Context) = melden(app, app.getString(R.string.austausch_beruehrt), AustauschActivity::class.java)

    /** The one heads-up notification, [titel], whose tap opens [ziel]; it stays until answered or the session ends. */
    private fun melden(ctx: Context, titel: String, ziel: Class<*>) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.deleteNotificationChannel(KANAL_ALT)
            val k = NotificationChannel(KANAL, ctx.getString(R.string.teilen_kanal), NotificationManager.IMPORTANCE_HIGH)
            k.enableVibration(true)
            nm.createNotificationChannel(k)
        }
        val tap = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, ziel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, KANAL)
            .setSmallIcon(R.drawable.ic_stat_homes)
            .setContentTitle(titel)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        runCatching { nm.notify(ID, n) } // no permission (Android 13+): nothing shows, nothing else happens
    }

    /** The question answered, or its session over: its notification goes. */
    fun weg(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(ID) }
    }
}
