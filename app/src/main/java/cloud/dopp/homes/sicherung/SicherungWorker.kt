package cloud.dopp.homes.sicherung

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.Napi.Quelle
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.SicherungClient
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.kann.KannStore
import cloud.dopp.homes.kann.Liste
import cloud.dopp.homes.kann.hat
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Daten sichern's one job (#194, laib ADR 0029): a [Lauf] over the
 * folders the person granted, then, each when its switch is on and its
 * permission granted, the contacts ([KontakteLauf], after the corrections
 * of them the person accepted on Homes, [KorrekturenLauf]) and the SMS and calls
 * ([NachrichtenLauf]). Daily on an unmetered network while the phone
 * charges and its battery is not low, and once at once on "Jetzt sichern".
 *
 * The one background job of the app (the owner's call in #194; CLAUDE.md,
 * ManifestTest). It never goes foreground and posts no notification: when
 * Android stops it (constraints lost, its ten minutes up) it ends between two
 * pieces and WorkManager runs it again with backoff; the upload resumes.
 */
class SicherungWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    @Volatile
    private var client: SicherungClient? = null

    override fun doWork(): Result {
        // The daily and the one-off run share the folders and the uploads: one at a time.
        if (!LAEUFT.compareAndSet(false, true)) return Result.success()
        try {
            val ctx = applicationContext
            val store = SicherungStore(ctx)
            // a part the box lacks (homes ADR 0001 §3) is skipped: no request for it
            val kann = KannStore.kann(ctx)
            val ordner = Lauf.QUELLEN.associateWith { q ->
                store.ordner(q)?.takeIf { kann.hat(Liste.SICHERUNG, q.wire) }?.let { SafOrdner(ctx, it) }
            }
            val kontakte = store.kontakteAn() && kann.hat(Liste.SICHERUNG, Napi.TEIL_KONTAKTE)
            val sms = store.smsAn() && kann.hat(Liste.SICHERUNG, Quelle.SMS.wire)
            // Nothing granted, nothing switched on: no request at all.
            if (ordner.values.all { it == null } && !kontakte && !sms) return Result.success()
            val base = ServerStore.baseUrl(ctx)
            val token = TokenStore.get(ctx)
            if (base == null || token.isNullOrBlank()) {
                val jetzt = System.currentTimeMillis()
                ordner.filterValues { it != null }.keys.forEach { q -> store.melde(q, Meldung(jetzt, Meldung.Art.HOMES, Lauf.NICHT_GEKOPPELT)) }
                if (kontakte) store.meldeKontakte(Meldung(jetzt, Meldung.Art.HOMES, Lauf.NICHT_GEKOPPELT))
                if (sms) store.melde(Quelle.SMS, Meldung(jetzt, Meldung.Art.HOMES, Lauf.NICHT_GEKOPPELT))
                return Result.success()
            }
            val c = SicherungClient(base, token)
            client = c
            val start = System.currentTimeMillis()
            val halt = { isStopped || System.currentTimeMillis() - start > Lauf.FRIST_MS }
            val ergebnisse = ArrayList<Lauf.Ergebnis>()
            val lauf = Lauf(
                client = c,
                gedaechtnis = store,
                ordner = { q -> ordner[q] },
                halt = { isStopped },
                melde = { f -> setProgressAsync(daten(f)) },
            )
            ergebnisse += lauf.laufen()
            val cache = File(ctx.cacheDir, CACHE)
            // a part left out for the time: the next try runs it
            var ausgelassen = false
            if (kontakte && halt()) ausgelassen = true
            if (kontakte && weiter(ergebnisse) && !halt()) {
                // the corrections the person took on Homes first, so the copy that follows carries them (owner 2026-11-07)
                // a side step: Homes not answering it stops nothing after it; the next run asks again
                if (Erlaubnis.hat(ctx, Erlaubnis.KONTAKTE_SCHALTER)) {
                    sicher({ store.meldeKontakte(it) }) { KorrekturenLauf(c, HandyKorrektur(ctx)).laufen() }
                }
                ergebnisse += if (Erlaubnis.hat(ctx, Erlaubnis.KONTAKTE)) {
                    sicher({ store.meldeKontakte(it) }) { KontakteLauf(c, store, HandyKontakte(ctx), File(cache, "kontakte")).laufen() }
                } else {
                    store.meldeKontakte(Meldung(System.currentTimeMillis(), Meldung.Art.KEINE, KontakteLauf.KEINE_ERLAUBNIS))
                    Lauf.Ergebnis.FERTIG
                }
            }
            if (sms && halt()) ausgelassen = true
            if (sms && weiter(ergebnisse) && !halt()) {
                ergebnisse += if (Erlaubnis.hat(ctx, Erlaubnis.SMS)) {
                    sicher({ store.melde(Quelle.SMS, it) }) {
                        NachrichtenLauf(
                            client = c,
                            gedaechtnis = store,
                            marken = store,
                            telefon = HandyTelefon(ctx),
                            ordner = File(cache, NachrichtenLauf.SMS),
                            halt = halt,
                            melde = { f -> setProgressAsync(daten(f)) },
                        ).laufen()
                    }
                } else {
                    store.melde(Quelle.SMS, Meldung(System.currentTimeMillis(), Meldung.Art.KEINE, NachrichtenLauf.KEINE_ERLAUBNIS))
                    Lauf.Ergebnis.FERTIG
                }
            }
            // More to send, no answer, or a busy box: soon again, with backoff.
            // Refused (full, no archive, not paired) or done: the next daily run.
            val nochmal = ausgelassen ||
                ergebnisse.any { it == Lauf.Ergebnis.WEITER || it == Lauf.Ergebnis.UNERREICHBAR || it == Lauf.Ergebnis.BESCHAEFTIGT }
            return if (nochmal && !isStopped) Result.retry() else Result.success()
        } finally {
            client = null
            LAEUFT.set(false)
        }
    }

    /**
     * A part that failed on the phone itself (a provider that threw, a full
     * cache): its sentence, and the next daily run tries again.
     */
    private fun sicher(melde: (Meldung) -> Unit, teil: () -> Lauf.Ergebnis): Lauf.Ergebnis = try {
        teil()
    } catch (e: Exception) {
        melde(Meldung(System.currentTimeMillis(), Meldung.Art.SPAETER, AUF_DEM_HANDY))
        Lauf.Ergebnis.FERTIG
    }

    /** Whether the next part may still ask Homes: not after it was unreachable or busy (the retry comes soon). */
    private fun weiter(bisher: List<Lauf.Ergebnis>): Boolean =
        bisher.none { it == Lauf.Ergebnis.UNERREICHBAR || it == Lauf.Ergebnis.BESCHAEFTIGT }

    override fun onStopped() {
        client?.abbrechen()
    }

    companion object {
        private val LAEUFT = AtomicBoolean(false)

        /** The app's cache folder for the files under way: the contacts' vCards, the encrypted SMS and calls. */
        private const val CACHE = "sicherung"

        const val AUF_DEM_HANDY = "Beim Lesen auf dem Handy ging etwas schief: die App versucht es beim nächsten Mal wieder."

        const val TAEGLICH = "sicherung-taeglich"
        const val JETZT = "sicherung-jetzt"

        private const val QUELLE = "quelle"
        private const val NAME = "name"
        private const val FERTIG = "fertig"
        private const val VON = "von"
        private const val BYTES = "bytes"
        private const val GESAMT = "gesamt"

        /** The daily run: unmetered network, charging, battery not low. */
        fun taeglichBedingungen(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .build()

        /** "Jetzt sichern": at once, but still never over mobile data. */
        fun jetztBedingungen(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        fun taeglich(): PeriodicWorkRequest =
            PeriodicWorkRequest.Builder(SicherungWorker::class.java, 1, TimeUnit.DAYS)
                .setConstraints(taeglichBedingungen())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

        fun jetztAnfrage(): OneTimeWorkRequest =
            OneTimeWorkRequest.Builder(SicherungWorker::class.java)
                .setConstraints(jetztBedingungen())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

        /** The daily run, once a folder is granted; an existing schedule is kept. */
        fun planen(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(TAEGLICH, ExistingPeriodicWorkPolicy.KEEP, taeglich())
        }

        /** "Jetzt sichern": a run now; a tap while one waits or runs adds none. */
        fun jetzt(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(JETZT, ExistingWorkPolicy.KEEP, jetztAnfrage())
        }

        /** No folder left: no daily run either. */
        fun abbestellen(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(TAEGLICH)
        }

        private fun daten(f: Fortschritt): Data = Data.Builder()
            .putString(QUELLE, f.quelle.wire)
            .putString(NAME, f.name)
            .putInt(FERTIG, f.fertig)
            .putInt(VON, f.von)
            .putLong(BYTES, f.bytes)
            .putLong(GESAMT, f.gesamt)
            .build()

        private fun fortschritt(d: Data): Fortschritt? {
            val q = Quelle.entries.firstOrNull { it.wire == d.getString(QUELLE) } ?: return null
            return Fortschritt(q, d.getString(NAME).orEmpty(), d.getInt(FERTIG, 0), d.getInt(VON, 0), d.getLong(BYTES, 0), d.getLong(GESAMT, 0))
        }

        /** Follow both runs for [owner]'s lifetime: [zeige] gets the state as it changes. */
        fun beobachten(ctx: Context, owner: LifecycleOwner, zeige: (Zustand) -> Unit) {
            val wm = WorkManager.getInstance(ctx)
            var taeglich: List<WorkInfo> = emptyList()
            var jetzt: List<WorkInfo> = emptyList()
            fun neu() {
                val alle = taeglich + jetzt
                val laeuft = alle.firstOrNull { it.state == WorkInfo.State.RUNNING }
                zeige(
                    when {
                        laeuft != null -> Zustand.Laeuft(fortschritt(laeuft.progress))
                        jetzt.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> Zustand.Wartet
                        else -> Zustand.Ruht
                    },
                )
            }
            wm.getWorkInfosForUniqueWorkLiveData(TAEGLICH).observe(owner) { taeglich = it.orEmpty(); neu() }
            wm.getWorkInfosForUniqueWorkLiveData(JETZT).observe(owner) { jetzt = it.orEmpty(); neu() }
        }
    }
}

/** What „Daten sichern“ shows of the two runs. */
sealed class Zustand {
    object Ruht : Zustand()

    /** „Jetzt sichern“ waits for its network (an unmetered one). */
    object Wartet : Zustand()

    /** A run is on; [fortschritt] once a file is under way. */
    data class Laeuft(val fortschritt: Fortschritt?) : Zustand()
}
