package cloud.dopp.homes.ausloeser

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import cloud.dopp.homes.R
import cloud.dopp.homes.ausloeser.AusloeserClient.Ausgang
import cloud.dopp.homes.widget.ActionDialog
import cloud.dopp.homes.widget.ActionItem
import cloud.dopp.homes.widget.ActionSheet
import cloud.dopp.homes.widget.ActionTone
import kotlin.concurrent.thread

/**
 * A tag's automations on the Auslöser screen (homes ADR 0004 §8): the ones
 * that start with it, each with „Lösen“; „Mit einer Automation verbinden“
 * (HA's automations by name); „Neue Automation“ (one sentence, for the
 * Bote). Every change is asked of Homes first and shown as its sentence in
 * the app's one dialog; only „Ja“ writes it. An admin's is done at once,
 * anyone else's waits for an admin: the wish is kept on the phone and asked
 * after while the screen is open — never in the background — until Homes
 * says what came of it.
 *
 * Its views are looked up once, on [a]: nothing here searches inside a view.
 */
internal class TagAutomationen(
    private val a: Activity,
    private val kopplung: () -> Pair<String, String>?,
    /** The tags read again: a change shows in the list and here. */
    private val neuLaden: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val karte = a.findViewById<View>(R.id.ausloeser_automationen)
    private val liste = a.findViewById<LinearLayout>(R.id.ausloeser_auto_liste)
    private val verbinden = a.findViewById<Button>(R.id.ausloeser_auto_verbinden)
    private val suche = a.findViewById<EditText>(R.id.ausloeser_auto_suche)
    private val treffer = a.findViewById<LinearLayout>(R.id.ausloeser_auto_treffer)
    private val neu = a.findViewById<Button>(R.id.ausloeser_auto_neu)
    private val satz = a.findViewById<EditText>(R.id.ausloeser_auto_satz)
    private val weiter = a.findViewById<Button>(R.id.ausloeser_auto_weiter)
    private val stand = a.findViewById<TextView>(R.id.ausloeser_auto_stand)

    private var tag: AusloeserClient.Tag? = null
    private var laeuft = false
    private val suchen = Runnable { suchen() }
    private val nachsehen = object : Runnable {
        override fun run() {
            nachsehen()
            main.postDelayed(this, NACHSEHEN_MS)
        }
    }

    init {
        verbinden.setOnClickListener {
            zeigeSuche(!suche.isVisible)
            if (suche.isVisible) suchen()
        }
        suche.doAfterTextChanged {
            main.removeCallbacks(suchen)
            main.postDelayed(suchen, TIPPEN_MS)
        }
        neu.setOnClickListener { zeigeSatz(!satz.isVisible) }
        weiter.setOnClickListener {
            val t = tag ?: return@setOnClickListener
            val text = satz.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener stand(a.getString(R.string.ausloeser_auto_satz_fehlt))
            fragen(AusloeserClient.Art.NEU, t, null, text)
        }
    }

    /** [t]'s automations, or nothing while no tag of HA's is being changed. */
    fun zeige(t: AusloeserClient.Tag?) {
        val anderer = t?.tag != tag?.tag
        tag = t
        karte.isVisible = t != null
        if (t == null) return
        if (anderer) {
            zeigeSuche(false)
            zeigeSatz(false)
            satz.text.clear()
            suche.text.clear()
            stand("")
        }
        satz.hint = a.getString(R.string.ausloeser_auto_satz_fuer, t.name.ifEmpty { a.getString(R.string.ausloeser_ohne_namen) })
        liste.removeAllViews()
        if (t.automationen.isEmpty()) liste.addView(zeile(a.getString(R.string.ausloeser_auto_keine)))
        t.automationen.forEach { auto ->
            liste.addView(zeile(auto.name))
            liste.addView(knopf(a.getString(R.string.ausloeser_auto_loesen)) { fragen(AusloeserClient.Art.LOESEN, t, auto.id, null) })
        }
        knoepfe()
    }

    /** Asking after the waiting wishes: from the screen's onResume to its onPause. */
    fun anfangen() {
        main.removeCallbacks(nachsehen)
        main.post(nachsehen)
    }

    fun aufhoeren() = main.removeCallbacksAndMessages(null)

    private fun stand(text: String) {
        stand.text = text
    }

    private fun zeile(text: String) = TextView(a).apply {
        setTextAppearance(R.style.Text_Homes_Body)
        this.text = text
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun knopf(text: String, tippen: () -> Unit) = Button(a, null, 0, R.style.Widget_Homes_Button_Ghost).apply {
        this.text = text
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        isEnabled = !laeuft
        setOnClickListener { tippen() }
    }

    private fun zeigeSuche(an: Boolean) {
        suche.isVisible = an
        treffer.isVisible = an
        if (!an) treffer.removeAllViews()
    }

    private fun zeigeSatz(an: Boolean) {
        satz.isVisible = an
        weiter.isVisible = an
    }

    /** While a change runs every button here waits; the line says what runs. */
    private fun knoepfe() {
        listOf(verbinden, neu, weiter).forEach { it.isEnabled = !laeuft }
        for (i in 0 until liste.childCount) (liste.getChildAt(i) as? Button)?.isEnabled = !laeuft
        for (i in 0 until treffer.childCount) (treffer.getChildAt(i) as? Button)?.isEnabled = !laeuft
    }

    private fun laeuft(an: Boolean, text: String? = null) {
        laeuft = an
        text?.let { stand(it) }
        knoepfe()
    }

    // --- HA's automations to link to ------------------------------------------------

    private fun suchen() {
        val t = tag ?: return
        if (!suche.isVisible) return // the field cleared by a new tag: nothing to look for
        val k = kopplung() ?: return stand(Ausloeser.NICHT_GEKOPPELT)
        val q = suche.text.toString()
        treffer.removeAllViews()
        treffer.addView(zeile(a.getString(R.string.ausloeser_auto_sucht)))
        thread(name = "automationen") {
            val antwort = AusloeserClient(k.first, k.second).automationen(q)
            a.runOnUiThread { if (!a.isFinishing && tag?.tag == t.tag && suche.text.toString() == q) zeigeTreffer(t, antwort) }
        }
    }

    /** What the search found: the automations [t] is not linked to yet, each a button; or why not. */
    private fun zeigeTreffer(t: AusloeserClient.Tag, antwort: Ausgang) {
        treffer.removeAllViews()
        if (antwort !is Ausgang.Automationen) return treffer.addView(zeile(Ausloeser.fehler(antwort) ?: AusloeserClient.UNLESBAR))
        val schon = t.automationen.map { it.id }.toSet()
        val frei = antwort.automationen.filter { it.id !in schon }
        if (frei.isEmpty()) treffer.addView(zeile(a.getString(R.string.ausloeser_auto_nichts)))
        frei.forEach { auto ->
            val name = if (auto.an) auto.name else a.getString(R.string.ausloeser_auto_aus, auto.name)
            treffer.addView(knopf(name) { fragen(AusloeserClient.Art.VERBINDEN, t, auto.id, null) })
        }
    }

    // --- a change: the sentence first, then confirmed -------------------------------

    private fun fragen(art: AusloeserClient.Art, t: AusloeserClient.Tag, automation: String?, text: String?) {
        val k = kopplung() ?: return stand(Ausloeser.NICHT_GEKOPPELT)
        laeuft(true, a.getString(R.string.ausloeser_auto_fragt))
        thread(name = "tag-aendern") {
            val antwort = AusloeserClient(k.first, k.second, AusloeserClient.LANGSAM).aendern(art, t.tag, automation, text, bestaetigt = false)
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                laeuft(false)
                when {
                    antwort is Ausgang.Aenderung && antwort.bestaetigen -> bestaetigen(art, t, automation, text, antwort)
                    antwort is Ausgang.Aenderung -> stand(antwort.satz)
                    else -> stand(Ausloeser.fehler(antwort) ?: AusloeserClient.UNLESBAR)
                }
            }
        }
    }

    private fun bestaetigen(art: AusloeserClient.Art, t: AusloeserClient.Tag, automation: String?, text: String?, frage: Ausgang.Aenderung) {
        stand("")
        val titel = when (art) {
            AusloeserClient.Art.VERBINDEN -> R.string.ausloeser_auto_frage_verbinden
            AusloeserClient.Art.LOESEN -> R.string.ausloeser_auto_frage_loesen
            AusloeserClient.Art.NEU -> R.string.ausloeser_auto_frage_neu
        }
        val nachricht = if (frage.freigabeNoetig) {
            frage.satz + "\n\n" + a.getString(R.string.ausloeser_auto_an_admins) + " " + a.getString(R.string.ausloeser_auto_hinweis_admins)
        } else {
            frage.satz
        }
        val ja = if (frage.freigabeNoetig) R.string.ausloeser_auto_bitten else R.string.ausloeser_auto_ja
        ActionDialog.show(
            activity = a,
            title = a.getString(titel),
            message = nachricht,
            sheet = ActionSheet(listOf(ActionItem(ja, ActionTone.PRIMARY))),
            onPick = { schreiben(art, t, automation, text) },
            onCancel = {},
            paintScrim = false,
        )
    }

    private fun schreiben(art: AusloeserClient.Art, t: AusloeserClient.Tag, automation: String?, text: String?) {
        val k = kopplung() ?: return stand(Ausloeser.NICHT_GEKOPPELT)
        laeuft(true, a.getString(R.string.ausloeser_auto_schreibt))
        thread(name = "tag-aendern") {
            val antwort = AusloeserClient(k.first, k.second, AusloeserClient.LANGSAM).aendern(art, t.tag, automation, text, bestaetigt = true)
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                laeuft(false)
                if (antwort !is Ausgang.Aenderung) return@runOnUiThread stand(Ausloeser.fehler(antwort) ?: AusloeserClient.UNLESBAR)
                val id = antwort.freigabe
                if (antwort.wartet && id != null) Ausloeser.wartet(a, id, t.tag)
                // an admin's new automation: the Bote's reply, with the code it asks for
                stand(listOfNotNull(antwort.satz, antwort.bote).joinToString("\n\n"))
                if (art == AusloeserClient.Art.NEU) satz.text.clear()
                zeigeSuche(false)
                zeigeSatz(false)
                neuLaden()
            }
        }
    }

    // --- the wishes that wait for an admin --------------------------------------------

    private fun nachsehen() {
        val wartende = Ausloeser.wartende(a)
        val k = kopplung() ?: return
        if (wartende.isEmpty()) return
        thread(name = "freigaben") {
            val client = AusloeserClient(k.first, k.second)
            val enden = wartende.keys.mapNotNull { id -> ende(client.freigabe(id))?.let { id to it } }
            a.runOnUiThread {
                if (a.isFinishing || enden.isEmpty()) return@runOnUiThread
                enden.forEach { (id, _) -> Ausloeser.erledigt(a, id) }
                stand(enden.joinToString("\n") { it.second })
                neuLaden()
            }
        }
    }

    /** What came of a wish, in Homes' sentence; null while it waits, or when Homes was not reached (asked again). */
    private fun ende(f: Ausgang): String? = when {
        f is Ausgang.Freigabe && !f.wartet -> f.satz
        // gone on Homes (decided long ago, or Homes forgot it): it waits no more
        f is Ausgang.Abgelehnt && f.code == GONE -> f.satz
        else -> null
    }

    companion object {
        /** How often a waiting wish is asked after while the screen is open. */
        const val NACHSEHEN_MS = 15_000L

        /** A search goes once the typing pauses. */
        private const val TIPPEN_MS = 350L

        /** A wish Homes no longer has. */
        private const val GONE = 404
    }
}
