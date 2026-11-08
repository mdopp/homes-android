package cloud.dopp.homes.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.StandortClient
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.sicherung.Anzeige
import cloud.dopp.homes.sicherung.Lauf
import cloud.dopp.homes.standort.Aufzeichnung
import cloud.dopp.homes.standort.Modus
import cloud.dopp.homes.standort.Sender
import cloud.dopp.homes.standort.StandortErlaubnis
import cloud.dopp.homes.standort.StandortStore
import cloud.dopp.homes.widget.HomesPages
import kotlin.concurrent.thread

/**
 * Mehr → Standortverlauf (owner 2026-11-02). One switch, off until the
 * person turns it on: it asks for the location, then for „Immer zulassen“
 * (Android 10+; on 11+ Android's own page). A phone without a
 * significant-motion sensor gets a sentence: it keeps the plain interval.
 * Refused, the switch stays off with a sentence; a tap on it leads on.
 * Two modes, „Detailliert (mehr Akku)“ and „Sparsam“. Below: whether the
 * person connected Reitti on Homes (if not, one sentence; a tap opens Homes'
 * page either way — the token form, or, connected, the quiet „Trennen“ there),
 * what waits on the phone, „Jetzt senden“.
 */
class StandortActivity : AppCompatActivity() {

    private lateinit var store: StandortStore

    /** Why the switch is off or what is missing, until the next try; [ziel]: what a tap opens. */
    private class Hinweis(val satz: String, val ziel: Ziel)

    private enum class Ziel { APP_INFO, HINTEN, NICHTS }

    private var hinweis: Hinweis? = null

    @Volatile
    private var verbindung: StandortClient.Verbindung? = null

    private var gefragt = 0L
    private var setzt = false

    private val erlaubeVorne = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vorneBeantwortet() }
    private val erlaubeHinten = registerForActivityResult(ActivityResultContracts.RequestPermission()) { hintenBeantwortet() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_standort)
        store = StandortStore(this)
        padForSystemBars(findViewById(R.id.standort_root))
        findViewById<SwitchCompat>(R.id.standort_an).setOnCheckedChangeListener { _, an -> if (!setzt) schalte(an) }
        findViewById<RadioGroup>(R.id.standort_modus).setOnCheckedChangeListener { _, id ->
            if (setzt) return@setOnCheckedChangeListener
            store.setzeModus(if (id == R.id.standort_detailliert) Modus.DETAILLIERT else Modus.SPARSAM)
            Aufzeichnung.anpassen(applicationContext, neu = true)
        }
        findViewById<TextView>(R.id.standort_meldung).setOnClickListener { tippeHinweis() }
        // not connected: Homes' page with the token form; connected: the same page, where „Trennen“ lives (owner 2026-11-08)
        findViewById<TextView>(R.id.standort_reitti).setOnClickListener {
            if (verbindung == StandortClient.Verbindung.NichtVerbunden || verbindung is StandortClient.Verbindung.Verbunden) {
                runCatching { startActivity(HomesPages.intent(this, HomesPages.STANDORT)) }
            }
        }
        findViewById<Button>(R.id.standort_jetzt).setOnClickListener { senden(sagen = true) }
    }

    override fun onResume() {
        super.onResume()
        if (store.an() && !StandortErlaubnis.genug(this)) {
            // the permission went in Android's settings: the switch says so
            Aufzeichnung.aus(applicationContext)
        }
        render()
        ladeVerbindung()
        if (store.an()) senden(sagen = false) // the screen open: what waits goes, the pause does not hold
    }

    private fun an(): Boolean = store.an() && StandortErlaubnis.genug(this)

    // --- the switch -------------------------------------------------------------

    private fun schalte(an: Boolean) {
        hinweis = null
        if (!an) {
            store.setzeAn(false)
            Aufzeichnung.aus(applicationContext)
            render()
            return
        }
        if (!StandortErlaubnis.vorne(this)) {
            gefragt = SystemClock.elapsedRealtime()
            try {
                erlaubeVorne.launch(StandortErlaubnis.VORNE)
            } catch (e: Exception) {
                vorneBeantwortet()
            }
            return
        }
        weiterNachHinten()
    }

    private fun vorneBeantwortet() {
        if (StandortErlaubnis.vorne(this)) {
            weiterNachHinten()
            return
        }
        store.setzeAn(false)
        val ohneDialog = SystemClock.elapsedRealtime() - gefragt < OHNE_DIALOG_MS &&
            StandortErlaubnis.VORNE.none { shouldShowRequestPermissionRationale(it) }
        hinweis = if (ohneDialog) {
            Hinweis(getString(R.string.standort_einstellungen), Ziel.APP_INFO)
        } else {
            Hinweis(getString(R.string.standort_abgelehnt), Ziel.NICHTS)
        }
        render()
    }

    /** The location is allowed; on Android 10+ „Immer zulassen“ is a second, separate step. */
    private fun weiterNachHinten() {
        if (StandortErlaubnis.hinten(this)) {
            eingeschaltet()
            return
        }
        hinweis = Hinweis(getString(R.string.standort_immer), Ziel.HINTEN)
        render()
        fragHinten()
    }

    private fun fragHinten() {
        gefragt = SystemClock.elapsedRealtime()
        try {
            erlaubeHinten.launch(StandortErlaubnis.HINTEN)
        } catch (e: Exception) {
            hintenBeantwortet()
        }
    }

    private fun hintenBeantwortet() {
        if (StandortErlaubnis.genug(this)) {
            eingeschaltet()
            return
        }
        store.setzeAn(false)
        hinweis = Hinweis(getString(R.string.standort_immer), Ziel.APP_INFO)
        render()
    }

    private fun eingeschaltet() {
        hinweis = null
        store.setzeAn(true)
        Aufzeichnung.anpassen(applicationContext, neu = true)
        render()
    }

    private fun tippeHinweis() {
        val h = hinweis
        when (h?.ziel) {
            Ziel.APP_INFO -> appInfo()
            Ziel.HINTEN -> fragHinten()
            else -> {}
        }
    }

    private fun appInfo() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (e: Exception) {
            // no settings screen: the sentence stands
        }
    }

    // --- the state --------------------------------------------------------------

    private fun render() {
        setzt = true
        findViewById<SwitchCompat>(R.id.standort_an).isChecked = an()
        findViewById<RadioGroup>(R.id.standort_modus).check(
            if (store.modus() == Modus.DETAILLIERT) R.id.standort_detailliert else R.id.standort_sparsam,
        )
        setzt = false

        val satz = hinweis?.satz ?: when {
            store.an() && !StandortErlaubnis.genug(this) -> getString(R.string.standort_weg)
            an() && !Aufzeichnung.bewegungssensor(this) -> getString(R.string.standort_ohne_sensor)
            an() -> store.meldung()
            else -> null
        }
        val meldung = findViewById<TextView>(R.id.standort_meldung)
        meldung.text = satz.orEmpty()
        meldung.visibility = if (satz == null) View.GONE else View.VISIBLE

        findViewById<TextView>(R.id.standort_reitti).text = when (val v = verbindung) {
            null -> if (gekoppelt()) getString(R.string.standort_reitti_laedt) else Lauf.NICHT_GEKOPPELT
            is StandortClient.Verbindung.Verbunden -> getString(R.string.standort_reitti_verbunden)
            StandortClient.Verbindung.NichtVerbunden -> getString(R.string.standort_reitti_fehlt)
            is StandortClient.Verbindung.Fehler -> v.satz
            StandortClient.Verbindung.NichtGekoppelt -> Lauf.NICHT_GEKOPPELT
            StandortClient.Verbindung.Unerreichbar -> getString(R.string.standort_reitti_unerreichbar)
        }
        findViewById<TextView>(R.id.standort_reitti).setTextColor(
            getColor(if (verbindung is StandortClient.Verbindung.Verbunden) R.color.homes_text_muted else R.color.homes_accent),
        )
        renderStand()
    }

    private fun renderStand() {
        val app = applicationContext
        thread {
            val wartet = Aufzeichnung.puffer(app).anzahl()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val zeilen = mutableListOf(
                    if (wartet > 0) getString(R.string.standort_wartet, wartet) else getString(R.string.standort_wartet_keine),
                    store.zuletztGesendet()?.let { (zeit, n) -> getString(R.string.standort_zuletzt, Anzeige.zeit(zeit), n) }
                        ?: getString(R.string.standort_zuletzt_nie),
                )
                if (store.verloren() > 0) zeilen += getString(R.string.standort_verloren, store.verloren())
                findViewById<TextView>(R.id.standort_stand).text = zeilen.joinToString("\n")
            }
        }
    }

    private fun gekoppelt(): Boolean = ServerStore.isConfigured(this) && TokenStore.isPaired(this)

    private fun ladeVerbindung() {
        val base = ServerStore.baseUrl(this)
        val token = TokenStore.get(this)
        if (base == null || token.isNullOrBlank()) return
        thread {
            val v = StandortClient(base, token).verbindung()
            runOnUiThread {
                verbindung = v
                if (!isFinishing) render()
            }
        }
    }

    /** Sends what waits; [sagen]: the person tapped, the line under the button says how it went. */
    private fun senden(sagen: Boolean) {
        val app = applicationContext
        if (sagen) zustand(getString(R.string.standort_sendet))
        thread {
            val lauf = runCatching { Aufzeichnung.senden(app, gedraengt = true) }.getOrNull()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (sagen) {
                    zustand(
                        when (lauf) {
                            null -> Lauf.NICHT_GEKOPPELT
                            Sender.Lauf.NICHTS_FAELLIG -> getString(R.string.standort_nichts)
                            Sender.Lauf.ALLES_GESENDET, Sender.Lauf.TEIL_GESENDET -> getString(R.string.standort_gesendet)
                            Sender.Lauf.BEHALTEN -> store.meldung() ?: Sender.UNERREICHBAR
                        },
                    )
                }
                render()
            }
        }
    }

    private fun zustand(text: String) {
        findViewById<TextView>(R.id.standort_zustand).text = text
    }

    private companion object {
        /** Faster than a person can read a dialog: Android answered without one. */
        const val OHNE_DIALOG_MS = 700L
    }
}
