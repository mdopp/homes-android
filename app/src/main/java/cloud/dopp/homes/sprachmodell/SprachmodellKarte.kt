package cloud.dopp.homes.sprachmodell

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.verbindung.Verbindung
import cloud.dopp.homes.widget.ActionDialog
import cloud.dopp.homes.widget.ActionItem
import cloud.dopp.homes.widget.ActionSheet
import cloud.dopp.homes.widget.ActionTone
import kotlin.concurrent.thread

/**
 * The box's language model as a card on the start screen, below the
 * favourites (owner, 2026-11-17), in their look: Homes' card from
 * `/napi/sprachmodell` drawn as the web page draws it — the title, the
 * model and for whom, the cost as a warning line (else the detail), Homes'
 * quiet hint, a row per preset with a button per duration, and the button
 * back to the household. A duration's button asks first, in the app's one
 * dialog, Homes' question with Homes' yes and no.
 *
 * Read when the screen is shown or resumed; nothing polls. A 404 — no
 * switchable model — leaves no card; a failed read keeps the card shown.
 * The pressed button answers at once („schaltet …“, the others still); the
 * request ends in the new card or in Homes' sentence under the row that was
 * tapped; no answer in time says so and reads the card again.
 */
class SprachmodellKarte(private val a: AppCompatActivity) : DefaultLifecycleObserver {

    private var karte: Karte? = null

    /** The button whose request runs: [taste] of a preset's duration, or [ZURUECK]. */
    private var gedrueckt: String? = null

    /** A request's sentence, and the button it answers. */
    private var fehler: Pair<String, String>? = null

    private var liest = false

    init {
        a.lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        fehler = null
        zeichnen()
        lesen()
    }

    private fun client(): SprachmodellClient? {
        val base = ServerStore.baseUrl(a) ?: return null
        val token = TokenStore.get(a) ?: return null
        return SprachmodellClient(base, token)
    }

    private fun lebt() = !a.isFinishing && !a.isDestroyed

    private fun lesen() {
        if (liest || gedrueckt != null) return
        val c = client()
        if (c == null) {
            karte = null
            zeichnen()
            return
        }
        liest = true
        thread {
            val r = c.lesen()
            a.runOnUiThread {
                liest = false
                when (r) {
                    is SprachmodellClient.Lesen.Da -> karte = r.karte
                    SprachmodellClient.Lesen.Keins -> karte = null
                    SprachmodellClient.Lesen.Nichts -> Unit
                }
                if (lebt()) zeichnen()
            }
        }
    }

    private fun fragen(k: Karte, w: Wahl, d: Dauer) {
        ActionDialog.show(
            activity = a,
            title = k.titel.ifEmpty { w.name },
            message = d.frage,
            sheet = ActionSheet(listOf(ActionItem(R.string.widget_confirm_toggle, ActionTone.PRIMARY, label = d.ja))),
            onPick = { schalten(taste(w, d)) { it.schalten(w.preset, d.stunden) } },
            onCancel = {},
            footer = d.nein.ifEmpty { a.getString(R.string.widget_confirm_no) },
            paintScrim = false,
        )
    }

    private fun schalten(taste: String, tun: (SprachmodellClient) -> SprachmodellClient.Ergebnis) {
        if (gedrueckt != null) return
        val c = client() ?: return
        gedrueckt = taste
        fehler = null
        zeichnen()
        thread {
            val r = tun(c)
            a.runOnUiThread {
                gedrueckt = null
                val satz = when (r) {
                    is SprachmodellClient.Ergebnis.Neu -> {
                        karte = r.karte
                        null
                    }
                    is SprachmodellClient.Ergebnis.Abgelehnt -> r.satz ?: a.getString(R.string.sprachmodell_keine_antwort)
                    SprachmodellClient.Ergebnis.Zeit -> a.getString(R.string.sprachmodell_keine_antwort)
                    SprachmodellClient.Ergebnis.Offline -> a.getString(R.string.anfordern_offline)
                }
                fehler = satz?.let { taste to it }
                if (!lebt()) return@runOnUiThread
                zeichnen()
                if (r is SprachmodellClient.Ergebnis.Zeit) lesen() // what holds now, after no answer
            }
        }
    }

    // --- drawing ---------------------------------------------------------------

    private fun zeichnen() {
        val box = a.findViewById<ViewGroup>(R.id.sprachmodell) ?: return
        box.removeAllViews()
        val k = karte
        box.visibility = if (k == null) View.GONE else View.VISIBLE
        if (k == null) return
        val v = a.layoutInflater.inflate(R.layout.item_sprachmodell, box, false)
        kopf(v, k)
        knoepfe(v.findViewById(R.id.sm_wahl), k)
        if (Verbindung.stand(a) != null) v.alpha = STALE_ALPHA
        box.addView(v)
    }

    /** The picture in the state's colour, the title, the model, the cost as a warning (else the detail), the hint. */
    private fun kopf(v: View, k: Karte) {
        val aktiv = k.zustand != HAUSHALT && k.zustand != WEG
        val farbe = a.getColor(if (aktiv) R.color.homes_accent else R.color.homes_text_muted)
        v.findViewById<ImageView>(R.id.sm_bild).apply {
            imageTintList = ColorStateList.valueOf(farbe)
            backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(farbe, DOT_ALPHA))
        }
        v.backgroundTintList = ColorStateList.valueOf(if (aktiv) ColorUtils.blendARGB(TILE, farbe, WASH) else TILE)
        v.findViewById<TextView>(R.id.sm_titel).text = k.titel
        v.findViewById<TextView>(R.id.sm_zeile).text = k.zeile
        val warnt = k.kosten.isNotEmpty()
        zeile(v.findViewById(R.id.sm_unter), if (warnt) k.kosten else k.detail)
            .setTextColor(a.getColor(if (warnt) R.color.homes_warn else R.color.homes_text_muted))
        zeile(v.findViewById(R.id.sm_hinweis), k.hinweis)
    }

    private fun zeile(t: TextView, text: String): TextView = t.apply {
        this.text = text
        visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** A row per preset, „Zurück zum Haushalt“, and a request's sentence under the button it answers. */
    private fun knoepfe(wahl: LinearLayout, k: Karte) {
        k.wahl.forEach { w ->
            wahl.addView(reihe(k, w))
            w.dauern.firstNotNullOfOrNull { d -> satzZu(taste(w, d)) }?.let { wahl.addView(it) }
        }
        if (k.zurueck.isNotEmpty()) {
            wahl.addView(knopf(k.zurueck, ZURUECK, LinearLayout.LayoutParams.MATCH_PARENT) { schalten(ZURUECK) { it.zurueck() } })
            satzZu(ZURUECK)?.let { wahl.addView(it) }
        }
        // a sentence whose button the new card no longer has: at the card's end
        val tasten = k.wahl.flatMap { w -> w.dauern.map { taste(w, it) } } + listOfNotNull(ZURUECK.takeIf { k.zurueck.isNotEmpty() })
        fehler?.takeIf { it.first !in tasten }?.let { wahl.addView(satz(it.second)) }
    }

    /** One preset: its name, then a button per duration. */
    private fun reihe(k: Karte, w: Wahl): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        addView(
            TextView(a).apply {
                text = w.name
                setTextColor(a.getColor(R.color.homes_text))
                textSize = NAME_SP
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        w.dauern.forEach { d -> addView(knopf(d.label, taste(w, d), ViewGroup.LayoutParams.WRAP_CONTENT) { fragen(k, w, d) }) }
        setPadding(0, dp(GAP_DP), 0, 0)
    }

    private fun knopf(label: String, taste: String, breite: Int, tap: () -> Unit): Button =
        Button(a, null, 0, R.style.Widget_Homes_Button_Control).apply {
            val laeuft = gedrueckt == taste
            text = if (laeuft) a.getString(R.string.home_device_switching) else label
            minHeight = dp(TOUCH_DP)
            minimumHeight = dp(TOUCH_DP)
            isEnabled = gedrueckt == null
            alpha = if (gedrueckt == null || laeuft) 1f else STALE_ALPHA
            tag = taste
            setOnClickListener { tap() }
            layoutParams = LinearLayout.LayoutParams(breite, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = if (breite == ViewGroup.LayoutParams.MATCH_PARENT) 0 else dp(GAP_DP)
                topMargin = if (breite == ViewGroup.LayoutParams.MATCH_PARENT) dp(GAP_DP) else 0
            }
        }

    private fun satzZu(taste: String): View? = fehler?.takeIf { it.first == taste }?.let { satz(it.second) }

    private fun satz(text: String): View = TextView(a).apply {
        this.text = text
        setTextColor(a.getColor(R.color.homes_warn))
        textSize = SATZ_SP
        setPadding(0, dp(GAP_DP / 2), 0, 0)
    }

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private companion object {
        const val ZURUECK = "zurueck-zum-haushalt"
        const val HAUSHALT = "haushalt"
        const val WEG = "weg"
        const val TOUCH_DP = 48
        const val GAP_DP = 8
        const val NAME_SP = 14f
        const val SATZ_SP = 13f
        const val TILE = 0xFF141419.toInt()
        const val WASH = 0.13f
        const val DOT_ALPHA = 0x2E
        const val STALE_ALPHA = 0.55f

        /** A duration's button, by preset and hours. */
        fun taste(w: Wahl, d: Dauer) = "${w.preset}|${d.stunden}"
    }
}
