package cloud.dopp.homes.ui

import android.app.AppOpsManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import cloud.dopp.homes.R
import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.Napi.Quelle
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.SicherungClient
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.kann.KannLeser
import cloud.dopp.homes.kann.KannStore
import cloud.dopp.homes.kann.Liste
import cloud.dopp.homes.kann.hat
import cloud.dopp.homes.sicherung.Anzeige
import cloud.dopp.homes.sicherung.Auswahl
import cloud.dopp.homes.sicherung.Erlaubnis
import cloud.dopp.homes.sicherung.KontakteLauf
import cloud.dopp.homes.sicherung.Lauf
import cloud.dopp.homes.sicherung.Meldung
import cloud.dopp.homes.sicherung.NachrichtenLauf
import cloud.dopp.homes.sicherung.SafOrdner
import cloud.dopp.homes.sicherung.SicherungStore
import cloud.dopp.homes.sicherung.SicherungWorker
import cloud.dopp.homes.sicherung.Zustand
import kotlin.concurrent.thread

/**
 * Mehr → Daten sichern (#194, laib ADR 0029). The person grants two
 * folders through the system picker — WhatsApp's
 * (`Android/media/com.whatsapp/WhatsApp`, suggested) and the one they chose
 * in Signal — and sees per source whether the folder is granted, when a file
 * last went over, what Homes reports (`/napi/sicherung/stand`) and the last
 * word as a German sentence with the next step. „Jetzt sichern“ starts a run
 * at once and shows its progress. The guide is on the screen itself.
 *
 * No key is asked for or stored here: the keys go into the person's vault
 * on the box (laib#986).
 *
 * Below the two folders, two switches (laib ADR 0029), off until the person
 * turns one on: „Kontakte übertragen“ (READ_CONTACTS) and „SMS und Anrufliste
 * übertragen“ (READ_SMS, READ_CALL_LOG). Turning one on asks for its
 * permission; refused, it stays off with a sentence. A sideloaded app on
 * Android 13+ cannot even ask for SMS and the call log ("restricted
 * settings"): then the sentence says how to allow them in App-Info.
 */
class SicherungActivity : AppCompatActivity() {

    private lateinit var store: SicherungStore

    /** What Homes last reported; null while it is read. */
    @Volatile
    private var stand: SicherungClient.Stand? = null

    /** What a look into each granted folder found, as the line under the folder; null while it looks. */
    private val funde = HashMap<Quelle, String?>()

    private val waehleWhatsapp = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { gewaehlt(Quelle.WHATSAPP, it) }
    }

    private val waehleSignal = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { gewaehlt(Quelle.SIGNAL, it) }
    }

    /** The two switches: their permissions, and how each is kept. */
    /** A switch: its permissions, and its key in `/napi/kann`'s `sicherung` (homes ADR 0001 §2). */
    private enum class Schalter(val rechte: Array<String>, val key: String) {
        KONTAKTE(Erlaubnis.KONTAKTE_SCHALTER, Napi.TEIL_KONTAKTE),
        SMS(Erlaubnis.SMS, Quelle.SMS.wire),
    }

    /** Why a switch stayed off, until the next try; [appInfo]: a tap opens the app's settings. */
    private class Hinweis(val satz: String, val appInfo: Boolean)

    private val hinweise = HashMap<Schalter, Hinweis>()

    /** When the permission was asked for: an answer within a blink came without a dialog. */
    private var gefragt = 0L

    private val erlaubeKontakte = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        antwort(Schalter.KONTAKTE)
    }

    private val erlaubeSms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        antwort(Schalter.SMS)
    }

    /** True while the screen sets a switch itself: that is no tap. */
    private var setzt = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sicherung)
        store = SicherungStore(this)
        padForSystemBars(findViewById(R.id.sicherung_root))
        karte(Quelle.WHATSAPP).findViewById<TextView>(R.id.quelle_name).setText(R.string.sicherung_whatsapp)
        karte(Quelle.SIGNAL).findViewById<TextView>(R.id.quelle_name).setText(R.string.sicherung_signal)
        Lauf.QUELLEN.forEach { q ->
            karte(q).findViewById<Button>(R.id.quelle_waehlen).setOnClickListener { waehle(q) }
            karte(q).findViewById<TextView>(R.id.quelle_meldung).setOnClickListener { if (ordnerFehlt(q)) waehle(q) }
        }
        Schalter.entries.forEach { art ->
            schalterView(art).setOnCheckedChangeListener { _, an -> if (!setzt) schalte(art, an) }
            meldungView(art).setOnClickListener { tippeMeldung(art) }
        }
        findViewById<Button>(R.id.sicherung_jetzt).setOnClickListener { jetzt() }
        findViewById<Button>(R.id.sicherung_zurueck).setOnClickListener {
            startActivity(Intent(this, ZurueckholenActivity::class.java))
        }
        SicherungWorker.beobachten(this, this, ::zeige)
    }

    override fun onResume() {
        super.onResume()
        KannLeser.lesen(this) // what the box can do: a part it lacks is a trace (homes ADR 0001 §10)
        render()
        ladeStand()
        Lauf.QUELLEN.forEach(::schaueNach)
    }

    /** Look into [q]'s granted folder off the main thread: is the backup the app would send there? */
    private fun schaueNach(q: Quelle) {
        val baum = store.ordner(q)?.takeIf { freigegeben(q) } ?: return
        funde[q] = null
        val app = applicationContext
        thread {
            val o = SafOrdner(app, baum)
            val satz = if (q == Quelle.SIGNAL) {
                Auswahl.neuesteSignal(o.dateien("", false).orEmpty())?.let {
                    getString(R.string.sicherung_fund_signal, Anzeige.zeit(it.geaendert), Anzeige.groesse(it.groesse))
                } ?: getString(R.string.sicherung_fund_signal_keine)
            } else {
                Auswahl.msgstoreDatei(o.dateien(Auswahl.DATENBANKEN, false).orEmpty())?.let {
                    getString(R.string.sicherung_fund_whatsapp, Anzeige.zeit(it.geaendert), Anzeige.groesse(it.groesse))
                } ?: getString(R.string.sicherung_fund_whatsapp_keine)
            }
            runOnUiThread {
                funde[q] = satz
                if (!isFinishing) renderQuelle(q)
            }
        }
    }

    /** The folder as the Files app names it, and below it what is in there. */
    private fun ordnerZeile(q: Quelle): String {
        val baum = store.ordner(q) ?: return getString(R.string.sicherung_ordner_nein)
        val name = runCatching { Anzeige.ordner(DocumentsContract.getTreeDocumentId(baum)) }.getOrDefault(baum.toString())
        if (!freigegeben(q)) return getString(R.string.sicherung_ordner_weg, name)
        val fund = if (funde.containsKey(q)) funde[q] ?: getString(R.string.sicherung_fund_sucht) else null
        return getString(R.string.sicherung_ordner_ja, name) + (fund?.let { "\n$it" } ?: "")
    }

    private fun karte(q: Quelle): View =
        findViewById(if (q == Quelle.SIGNAL) R.id.sicherung_signal else R.id.sicherung_whatsapp)

    // --- the folders ----------------------------------------------------------

    private fun freigegeben(q: Quelle): Boolean = store.ordner(q)?.let { SafOrdner.freigegeben(this, it) } == true

    /** A folder was chosen once and its grant is gone: the tap on the sentence opens the picker. */
    private fun ordnerFehlt(q: Quelle): Boolean =
        (store.ordner(q) != null && !freigegeben(q)) || store.meldung(q)?.art == Meldung.Art.ORDNER

    /** The box lacks [key] in its backup (homes ADR 0001 §3): the part cannot be switched on here. */
    private fun fehlt(key: String): Boolean = !KannStore.kann(this).hat(Liste.SICHERUNG, key)

    private fun waehle(q: Quelle) {
        if (fehlt(q.wire)) return
        try {
            if (q == Quelle.WHATSAPP) waehleWhatsapp.launch(SafOrdner.WHATSAPP_START) else waehleSignal.launch(store.ordner(q))
        } catch (e: Exception) {
            zustand(getString(R.string.sicherung_kein_picker))
        }
    }

    /** Keep the grant, check WhatsApp's folder is WhatsApp's, give back the folder chosen before. */
    private fun gewaehlt(q: Quelle, baum: Uri) {
        if (!SafOrdner.behalten(this, baum)) {
            zustand(getString(R.string.sicherung_ordner_verweigert))
            return
        }
        val app = applicationContext
        thread {
            val passt = q != Quelle.WHATSAPP || SafOrdner(app, baum).let { o ->
                o.hat(Auswahl.DATENBANKEN) || o.hat(Auswahl.MEDIEN)
            }
            runOnUiThread {
                if (!passt) {
                    runCatching { contentResolver.releasePersistableUriPermission(baum, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    zustand(getString(R.string.sicherung_ordner_falsch))
                    return@runOnUiThread
                }
                val vorher = store.ordner(q)
                if (vorher != null && vorher != baum) {
                    runCatching { contentResolver.releasePersistableUriPermission(vorher, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
                store.setzeOrdner(q, baum)
                SicherungWorker.planen(app)
                if (!isFinishing) {
                    render()
                    schaueNach(q)
                }
            }
        }
    }

    // --- the state ------------------------------------------------------------

    private fun render() {
        Lauf.QUELLEN.forEach(::renderQuelle)
        Schalter.entries.forEach(::renderSchalter)
        val s = stand
        findViewById<TextView>(R.id.sicherung_frei).text = when {
            !gekoppelt() -> Lauf.NICHT_GEKOPPELT
            s is SicherungClient.Stand.Da -> s.freiBytes?.let { getString(R.string.sicherung_box_frei, Anzeige.groesse(it)) }.orEmpty()
            s is SicherungClient.Stand.Fehler -> s.satz
            s is SicherungClient.Stand.NichtGekoppelt -> Lauf.NICHT_GEKOPPELT
            else -> ""
        }
    }

    private fun renderQuelle(q: Quelle) {
        val k = karte(q)
        val fehlt = Spuren.teil(this, k.findViewById(R.id.quelle_spur), KannStore.kann(this), Liste.SICHERUNG, q.wire, ::render)
        k.findViewById<Button>(R.id.quelle_waehlen).isEnabled = !fehlt
        val frei = freigegeben(q)
        k.findViewById<TextView>(R.id.quelle_ordner).text = ordnerZeile(q)
        k.findViewById<Button>(R.id.quelle_waehlen).setText(
            if (store.ordner(q) == null) R.string.sicherung_ordner_waehlen else R.string.sicherung_ordner_neu,
        )
        k.findViewById<TextView>(R.id.quelle_zuletzt).text = store.uebertragen(q)?.let { (zeit, n) ->
            resources.getQuantityString(R.plurals.sicherung_zuletzt, n, Anzeige.zeit(zeit), n)
        } ?: getString(R.string.sicherung_zuletzt_nie)
        val box = k.findViewById<TextView>(R.id.quelle_box)
        val boxText = boxZeile(q)
        box.text = boxText
        box.visibility = if (boxText.isEmpty()) View.GONE else View.VISIBLE

        val m = store.meldung(q)
        val satz = when {
            store.ordner(q) != null && !frei -> if (q == Quelle.SIGNAL) Lauf.ORDNER_SIGNAL else Lauf.ORDNER_WHATSAPP
            m == null -> null
            m.art == Meldung.Art.OK -> getString(R.string.sicherung_geprueft, Anzeige.zeit(m.zeit))
            else -> m.satz
        }
        val meldung = k.findViewById<TextView>(R.id.quelle_meldung)
        meldung.text = satz.orEmpty()
        meldung.visibility = if (satz == null) View.GONE else View.VISIBLE
        val ruhig = m?.art == Meldung.Art.OK && frei
        meldung.setTextColor(getColor(if (ruhig) R.color.homes_text_muted else R.color.homes_accent))
    }

    /** What Homes reports for [q]. */
    private fun boxZeile(q: Quelle): String = when (val s = stand) {
        null -> if (gekoppelt()) getString(R.string.sicherung_box_laedt) else ""
        is SicherungClient.Stand.Da -> {
            val qs = s.quellen[q]
            if (qs == null || (qs.dateien == 0L && qs.zuletzt == null)) {
                getString(R.string.sicherung_box_nie)
            } else {
                val zuletzt = Anzeige.rfc3339(qs.zuletzt)?.let { Anzeige.zeit(it) } ?: qs.zuletzt.orEmpty()
                val zeile = resources.getQuantityString(R.plurals.sicherung_box, qs.dateien.toInt(), qs.dateien.toInt(), Anzeige.groesse(qs.bytes), zuletzt)
                if (qs.wartend > 0) zeile + "\n" + resources.getQuantityString(R.plurals.sicherung_box_wartend, qs.wartend.toInt(), qs.wartend.toInt()) else zeile
            }
        }
        SicherungClient.Stand.Unerreichbar -> getString(R.string.sicherung_box_unerreichbar)
        else -> "" // Homes' sentence stands once, above the cards
    }

    private fun gekoppelt(): Boolean = ServerStore.isConfigured(this) && TokenStore.isPaired(this)

    /** Ask Homes what reached the box; the screen shows it as it comes. */
    private fun ladeStand() {
        val base = ServerStore.baseUrl(this)
        val token = TokenStore.get(this)
        if (base == null || token.isNullOrBlank()) return
        thread {
            val s = SicherungClient(base, token).stand()
            runOnUiThread {
                stand = s
                if (!isFinishing) render()
            }
        }
    }

    // --- „Jetzt sichern“ ------------------------------------------------------

    private fun jetzt() {
        if (Lauf.QUELLEN.none(::freigegeben) && !an(Schalter.KONTAKTE) && !an(Schalter.SMS)) {
            zustand(getString(R.string.sicherung_jetzt_ohne_ordner))
            return
        }
        SicherungWorker.jetzt(applicationContext)
        zustand(getString(R.string.sicherung_jetzt_angestossen))
    }

    private var lief = false

    private fun zeige(z: Zustand) {
        val balken = findViewById<ProgressBar>(R.id.sicherung_balken)
        when (z) {
            is Zustand.Laeuft -> {
                lief = true
                val f = z.fortschritt
                if (f == null || f.gesamt <= 0) {
                    balken.isIndeterminate = true
                    zustand(getString(R.string.sicherung_laeuft))
                } else {
                    balken.isIndeterminate = false
                    balken.progress = (f.bytes.coerceIn(0, f.gesamt) * 1000 / f.gesamt).toInt()
                    val quelle = getString(
                        when (f.quelle) {
                            Quelle.SIGNAL -> R.string.sicherung_signal
                            Quelle.SMS -> R.string.sicherung_sms
                            else -> R.string.sicherung_whatsapp
                        },
                    )
                    zustand(
                        resources.getQuantityString(
                            R.plurals.sicherung_laeuft_gesamt, f.von, quelle,
                            Anzeige.zahl(f.fertig), Anzeige.zahl(f.von), Anzeige.anteil(f.bytes, f.gesamt),
                        ),
                    )
                }
                balken.visibility = View.VISIBLE
            }
            Zustand.Wartet -> {
                balken.visibility = View.GONE
                zustand(getString(R.string.sicherung_wartet))
            }
            Zustand.Ruht -> {
                balken.visibility = View.GONE
                zustand(getString(R.string.sicherung_ruht))
                if (lief) {
                    lief = false
                    render()
                    ladeStand()
                }
            }
        }
    }

    private fun zustand(text: String) {
        findViewById<TextView>(R.id.sicherung_zustand).text = text
    }

    // --- Kontakte, SMS und Anrufe (laib ADR 0029) --------------------------------

    private fun schalterView(art: Schalter): SwitchCompat =
        findViewById(if (art == Schalter.KONTAKTE) R.id.handy_kontakte else R.id.handy_sms)

    private fun meldungView(art: Schalter): TextView =
        findViewById(if (art == Schalter.KONTAKTE) R.id.handy_kontakte_meldung else R.id.handy_sms_meldung)

    private fun standView(art: Schalter): TextView =
        findViewById(if (art == Schalter.KONTAKTE) R.id.handy_kontakte_stand else R.id.handy_sms_stand)

    /** On in the app, and still allowed by Android. */
    private fun an(art: Schalter): Boolean = gewollt(art) && Erlaubnis.hat(this, art.rechte)

    private fun gewollt(art: Schalter): Boolean = if (art == Schalter.KONTAKTE) store.kontakteAn() else store.smsAn()

    private fun merke(art: Schalter, an: Boolean) {
        if (art == Schalter.KONTAKTE) store.setzeKontakteAn(an) else store.setzeSmsAn(an)
    }

    /** A tap on a switch: off is off; on asks Android first, unless the permission is there. */
    private fun schalte(art: Schalter, an: Boolean) {
        hinweise.remove(art)
        if (an && fehlt(art.key)) {
            renderSchalter(art)
            return
        }
        if (!an) {
            merke(art, false)
            renderSchalter(art)
            return
        }
        if (Erlaubnis.hat(this, art.rechte)) {
            eingeschaltet(art)
            return
        }
        gefragt = SystemClock.elapsedRealtime()
        try {
            (if (art == Schalter.KONTAKTE) erlaubeKontakte else erlaubeSms).launch(art.rechte)
        } catch (e: Exception) {
            antwort(art)
        }
    }

    private fun eingeschaltet(art: Schalter) {
        merke(art, true)
        SicherungWorker.planen(applicationContext)
        renderSchalter(art)
    }

    /** Android's answer: allowed, the switch is on; refused, it stays off and says why. */
    private fun antwort(art: Schalter) {
        if (Erlaubnis.hat(this, art.rechte)) {
            eingeschaltet(art)
            return
        }
        merke(art, false)
        // No dialog was shown: the answer came at once, and Android gives no reason to ask again.
        val ohneDialog = SystemClock.elapsedRealtime() - gefragt < OHNE_DIALOG_MS &&
            art.rechte.none { shouldShowRequestPermissionRationale(it) }
        hinweise[art] = when {
            art == Schalter.SMS && (gesperrt() || (Build.VERSION.SDK_INT >= 33 && ohneDialog)) ->
                Hinweis(getString(R.string.handy_sms_gesperrt), appInfo = true)
            ohneDialog -> Hinweis(
                getString(if (art == Schalter.KONTAKTE) R.string.handy_kontakte_einstellungen else R.string.handy_sms_einstellungen),
                appInfo = true,
            )
            else -> Hinweis(
                getString(if (art == Schalter.KONTAKTE) R.string.handy_kontakte_abgelehnt else R.string.handy_sms_abgelehnt),
                appInfo = false,
            )
        }
        renderSchalter(art)
    }

    /**
     * Android 13+ keeps SMS and the call log from an app installed outside a
     * store ("restricted settings") until the person allows it in App-Info.
     * The app op says so where Android has it; elsewhere the instant refusal does.
     */
    private fun gesperrt(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val ops = getSystemService(AppOpsManager::class.java) ?: return false
        return runCatching {
            ops.unsafeCheckOpNoThrow(OP_EINGESCHRAENKT, applicationInfo.uid, packageName) == AppOpsManager.MODE_ERRORED
        }.getOrDefault(false)
    }

    private fun tippeMeldung(art: Schalter) {
        val h = hinweise[art]
        when {
            h?.appInfo == true -> try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            } catch (e: Exception) {
                // no settings screen: the sentence stands
            }
            // switched on once, and Android took the permission back since: ask again
            h == null && gewollt(art) && !Erlaubnis.hat(this, art.rechte) -> schalte(art, true)
        }
    }

    private fun renderSchalter(art: Schalter) {
        val spur: ViewGroup = findViewById(if (art == Schalter.KONTAKTE) R.id.handy_kontakte_spur else R.id.handy_sms_spur)
        val fehlt = Spuren.teil(this, spur, KannStore.kann(this), Liste.SICHERUNG, art.key, ::render)
        setzt = true
        schalterView(art).isChecked = an(art) && !fehlt
        schalterView(art).isEnabled = !fehlt
        setzt = false

        // Off: the card says what goes where, and nothing else.
        val stand = standView(art)
        val standText = if (!an(art)) null else standZeile(art)
        stand.text = standText.orEmpty()
        stand.visibility = if (standText == null) View.GONE else View.VISIBLE

        val satz = meldungSatz(art)
        val meldung = meldungView(art)
        meldung.text = satz.orEmpty()
        // a part the box lacks says only its trace, never an orange line (homes ADR 0001 §10)
        meldung.visibility = if (satz == null || fehlt) View.GONE else View.VISIBLE
    }

    /** The line under [art]'s switch: why it stayed off, or its last word when that was not good. */
    private fun meldungSatz(art: Schalter): String? {
        val m = if (art == Schalter.KONTAKTE) store.kontakteMeldung() else store.meldung(Quelle.SMS)
        return hinweise[art]?.satz
            ?: if (gewollt(art) && !Erlaubnis.hat(this, art.rechte)) {
                if (art == Schalter.KONTAKTE) KontakteLauf.KEINE_ERLAUBNIS else NachrichtenLauf.KEINE_ERLAUBNIS
            } else if (gewollt(art) && m != null && m.art != Meldung.Art.OK) {
                m.satz
            } else {
                null
            }
    }

    /** „Letzte Übertragung: <date> – <what>“, the same scheme on both cards; the same words before the first. */
    private fun standZeile(art: Schalter): String {
        val letzte: Pair<Long, String>? = if (art == Schalter.KONTAKTE) {
            store.kontakte()?.let { (zeit, e) -> zeit to Anzeige.kontakte(e.kontakte, e.neu, e.geaendert, e.entfernt) }
        } else {
            store.letzte()?.let { (zeit, zahlen) -> zeit to Anzeige.nachrichten(zahlen) }
                ?: store.uebertragen(Quelle.SMS)?.let { (zeit, _) -> zeit to "" }
        }
        return when {
            letzte == null -> getString(R.string.handy_stand_nie)
            letzte.second.isEmpty() -> getString(R.string.handy_stand, Anzeige.zeit(letzte.first), "").removeSuffix(" – ")
            else -> getString(R.string.handy_stand, Anzeige.zeit(letzte.first), letzte.second)
        }
    }

    private companion object {
        /** Faster than a person can read a dialog: Android answered without one. */
        const val OHNE_DIALOG_MS = 700L

        /** Android 13's app op for "restricted settings", by its name: no public constant. */
        const val OP_EINGESCHRAENKT = "android:access_restricted_settings"
    }
}
