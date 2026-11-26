package cloud.dopp.homes.ausloeser

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import cloud.dopp.homes.R
import cloud.dopp.homes.ausloeser.AusloeserClient.Ausgang
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.realtime.Live
import cloud.dopp.homes.sicherung.Anzeige
import cloud.dopp.homes.ui.padForSystemBars
import cloud.dopp.homes.widget.ActionDialog
import cloud.dopp.homes.widget.ActionItem
import cloud.dopp.homes.widget.ActionSheet
import cloud.dopp.homes.widget.ActionTone
import kotlin.concurrent.thread

/**
 * Mehr → Auslöser (homes ADR 0004 §1, §2), shown while the box offers it
 * (`mehr` `ausloeser`, the geraete module): HA's tags; „Neuen Tag beschreiben“
 * — a name (the house's rooms as suggestions), the tag made in HA, „Halte den
 * Tag ans Telefon …“, Homes' URI written onto it, done or why not; a tag
 * tapped in the list is renamed („Sichern“) or removed after one question,
 * and a touched unknown tag's „Benennen“ ([EXTRA_TAG]) names that id — never
 * written again, so its automations keep working. While the screen is in
 * front, a blank tag held to the phone is offered for a new one. And the
 * switch „Ladestation melden“, off until the person turns it on. Every press
 * answers on its button at once and ends in the line below it; a tag that
 * does not come within a minute is said too.
 */
class AusloeserActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())

    /** The tag made in HA and not yet written, with its name: a second try writes it again, it makes no new one. */
    @Volatile
    private var offen: Pair<String, Ausgang.Angelegt>? = null

    @Volatile
    private var wartet = false

    /** The tag being named or renamed instead of a new one; [ausListe]: it is in HA's list, so it can be removed. */
    private var bearbeitet: AusloeserClient.Tag? = null
    private var ausListe = false
    private var setzt = false

    private val zuSpaet = Runnable {
        warteAus()
        stand(getString(R.string.ausloeser_kein_tag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ausloeser)
        padForSystemBars(findViewById(R.id.ausloeser_root))
        findViewById<SwitchCompat>(R.id.ausloeser_laden).setOnCheckedChangeListener { _, an ->
            if (setzt) return@setOnCheckedChangeListener
            Ausloeser.setzeLadenMelden(this, an)
            Live.ensure(this) // the running live service takes the switch at its next start command
            renderLaden()
        }
        findViewById<Button>(R.id.ausloeser_schreiben).setOnClickListener { tippe() }
        findViewById<Button>(R.id.ausloeser_entfernen).setOnClickListener { bearbeitet?.let(::frageEntfernen) }
        findViewById<Button>(R.id.ausloeser_zurueck).setOnClickListener {
            bearbeite(null, false)
            stand("")
        }
        findViewById<TextView>(R.id.ausloeser_stand).setOnClickListener {
            if (schreiber.bereit(this) == TagSchreiber.Bereit.AUS) runCatching { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
        }
        intent?.getStringExtra(EXTRA_TAG)?.let { bearbeite(AusloeserClient.Tag(it, ""), false) }
    }

    override fun onResume() {
        super.onResume()
        renderLaden()
        ladeTags()
        // reader mode while in front: written while waiting, else a blank tag is offered (§2)
        schreiber.an(this, { offen?.takeIf { wartet }?.second?.uri }, ::kam)
    }

    override fun onPause() {
        schreiber.aus(this)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun gekoppelt(): Pair<String, String>? {
        val base = ServerStore.baseUrl(this) ?: return null
        val token = TokenStore.get(this)?.takeIf { it.isNotBlank() } ?: return null
        return base to token
    }

    private fun client(k: Pair<String, String>) = AusloeserClient(k.first, k.second)

    // --- the tags -----------------------------------------------------------------

    private fun ladeTags() {
        val liste = findViewById<LinearLayout>(R.id.ausloeser_liste)
        val kopplung = gekoppelt() ?: return zeigeTags(liste, Ausloeser.NICHT_GEKOPPELT, emptyList())
        if (liste.childCount == 0) zeigeTags(liste, getString(R.string.ausloeser_tags_laden), emptyList())
        thread(name = "tags") {
            val a = client(kopplung).tags()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (a is Ausgang.Tags) {
                    zeigeTags(liste, if (a.tags.isEmpty()) getString(R.string.ausloeser_tags_keine) else null, a.tags)
                    zeigeVorschlaege(a.vorschlaege)
                } else {
                    zeigeTags(liste, Ausloeser.fehler(a) ?: AusloeserClient.UNLESBAR, emptyList())
                }
            }
        }
    }

    private fun text(t: String, tippen: (() -> Unit)? = null) = TextView(this).apply {
        setTextAppearance(R.style.Text_Homes_Body)
        text = t
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tippen?.let {
            setBackgroundResource(android.R.drawable.list_selector_background)
            val pad = resources.getDimensionPixelSize(R.dimen.space_xs)
            setPadding(0, pad, 0, pad)
            setOnClickListener { _ -> it() }
        }
    }

    private fun zeigeTags(liste: LinearLayout, satz: String?, tags: List<AusloeserClient.Tag>) {
        liste.removeAllViews()
        satz?.let { liste.addView(text(it)) }
        tags.forEach { t ->
            val name = t.name.ifEmpty { getString(R.string.ausloeser_ohne_namen) }
            liste.addView(text(name) { bearbeite(t, true) }) // the id is never shown (§2)
        }
    }

    /** The suggestions as one-tap words under the name field. */
    private fun zeigeVorschlaege(vorschlaege: List<String>) {
        val reihe = findViewById<LinearLayout>(R.id.ausloeser_vorschlaege)
        reihe.removeAllViews()
        vorschlaege.forEach { v ->
            reihe.addView(
                Button(this, null, 0, R.style.Widget_Homes_Button_Ghost).apply {
                    text = v
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginEnd = resources.getDimensionPixelSize(R.dimen.space_xs) }
                    setOnClickListener {
                        // the screen's field: inside apply, a bare findViewById would search this button
                        this@AusloeserActivity.findViewById<EditText>(R.id.ausloeser_name).apply {
                            setText(v)
                            setSelection(v.length)
                        }
                    }
                },
            )
        }
    }

    /** Name or rename [tag] instead of writing a new one; null: back to „Neuen Tag beschreiben“. */
    private fun bearbeite(tag: AusloeserClient.Tag?, ausListe: Boolean) {
        if (wartet) warteAus()
        bearbeitet = tag
        this.ausListe = ausListe
        findViewById<TextView>(R.id.ausloeser_name_titel).text = getString(
            when {
                tag == null -> R.string.ausloeser_neu_titel
                tag.name.isEmpty() -> R.string.ausloeser_benennen_titel
                else -> R.string.ausloeser_umbenennen_titel
            },
        )
        findViewById<EditText>(R.id.ausloeser_name).setText(tag?.name.orEmpty())
        findViewById<View>(R.id.ausloeser_aendern).visibility = if (tag == null) View.GONE else View.VISIBLE
        findViewById<View>(R.id.ausloeser_entfernen).visibility = if (ausListe) View.VISIBLE else View.GONE
        knopfRuht()
    }

    /** The main button as it stands when nothing runs. */
    private fun knopfRuht() {
        findViewById<Button>(R.id.ausloeser_schreiben).apply {
            isEnabled = true
            text = getString(
                when {
                    bearbeitet != null -> R.string.ausloeser_sichern
                    offen != null -> R.string.ausloeser_nochmal
                    else -> R.string.ausloeser_schreiben
                },
            )
        }
    }

    private fun laeuft(knopf: Int, satz: String) {
        findViewById<Button>(R.id.ausloeser_schreiben).apply {
            isEnabled = false
            text = getString(knopf)
        }
        stand(satz)
    }

    // --- a new tag, or a name ------------------------------------------------------

    private fun tippe() {
        if (wartet) {
            warteAus()
            stand(getString(R.string.ausloeser_abgebrochen))
            return
        }
        val name = findViewById<EditText>(R.id.ausloeser_name).text.toString().trim()
        val kopplung = gekoppelt()
        val tag = bearbeitet
        val fehlt = when {
            name.isEmpty() -> getString(R.string.ausloeser_name_fehlt)
            kopplung == null -> Ausloeser.NICHT_GEKOPPELT
            tag != null -> null // a name only: no NFC needed
            schreiber.bereit(this) == TagSchreiber.Bereit.KEIN_NFC -> getString(R.string.ausloeser_kein_nfc)
            schreiber.bereit(this) == TagSchreiber.Bereit.AUS -> getString(R.string.ausloeser_nfc_aus)
            else -> null
        }
        if (fehlt != null || kopplung == null) return stand(fehlt.orEmpty())
        val schon = offen?.takeIf { it.first == name }
        when {
            tag != null -> sichern(kopplung, tag, name)
            schon != null -> warte(schon)
            else -> anlegen(kopplung, name)
        }
    }

    private fun anlegen(kopplung: Pair<String, String>, name: String) {
        laeuft(R.string.ausloeser_legt_an, getString(R.string.ausloeser_legt_an_satz, name))
        thread(name = "tag-anlegen") {
            val a = client(kopplung).benennen(name)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (a is Ausgang.Angelegt) {
                    offen = name to a
                    warte(name to a)
                    ladeTags()
                } else {
                    knopfRuht()
                    stand(Ausloeser.fehler(a) ?: AusloeserClient.UNLESBAR)
                }
            }
        }
    }

    /** [tag] named [name] in HA; the tag itself is not written again (§2). */
    private fun sichern(kopplung: Pair<String, String>, tag: AusloeserClient.Tag, name: String) {
        laeuft(R.string.ausloeser_sichert, getString(R.string.ausloeser_sichert_satz, name))
        thread(name = "tag-benennen") {
            val a = client(kopplung).benennen(name, tag.tag)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (a is Ausgang.Angelegt) {
                    bearbeite(null, false)
                    stand(getString(R.string.ausloeser_benannt, name))
                    ladeTags()
                } else {
                    knopfRuht()
                    stand(Ausloeser.fehler(a) ?: AusloeserClient.UNLESBAR)
                }
            }
        }
    }

    private fun frageEntfernen(tag: AusloeserClient.Tag) {
        val name = tag.name.ifEmpty { getString(R.string.ausloeser_ohne_namen) }
        ActionDialog.show(
            activity = this,
            title = getString(R.string.ausloeser_entfernen_titel, name),
            message = getString(R.string.ausloeser_entfernen_frage, name),
            sheet = ActionSheet(listOf(ActionItem(R.string.ausloeser_entfernen, ActionTone.DANGEROUS)), R.string.ausloeser_entfernen_hinweis),
            onPick = { entfernen(tag, name) },
            onCancel = {},
            paintScrim = false,
        )
    }

    private fun entfernen(tag: AusloeserClient.Tag, name: String) {
        val kopplung = gekoppelt() ?: return stand(Ausloeser.NICHT_GEKOPPELT)
        laeuft(R.string.ausloeser_entfernt_laeuft, getString(R.string.ausloeser_entfernt_satz, name))
        thread(name = "tag-entfernen") {
            val a = client(kopplung).entfernen(tag.tag)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (a == Ausgang.Entfernt) {
                    bearbeite(null, false)
                    stand(getString(R.string.ausloeser_entfernt, name))
                    ladeTags()
                } else {
                    knopfRuht()
                    stand(Ausloeser.fehler(a) ?: AusloeserClient.UNLESBAR)
                }
            }
        }
    }

    private fun warte(tag: Pair<String, Ausgang.Angelegt>) {
        offen = tag
        wartet = true
        findViewById<Button>(R.id.ausloeser_schreiben).apply {
            isEnabled = true
            text = getString(R.string.ausloeser_abbrechen)
        }
        stand(getString(R.string.ausloeser_halte, tag.first))
        main.removeCallbacks(zuSpaet)
        main.postDelayed(zuSpaet, WARTEN_MS)
    }

    /** A tag came, from NFC's thread: written while waiting, else looked at. */
    private fun kam(e: TagSchreiber.Ergebnis) = runOnUiThread {
        if (isFinishing) return@runOnUiThread
        when (e) {
            TagSchreiber.Ergebnis.Leer -> if (!wartet) leerAngeboten()
            TagSchreiber.Ergebnis.Beschrieben -> if (!wartet) stand(getString(R.string.ausloeser_schon_beschrieben))
            TagSchreiber.Ergebnis.Fertig, is TagSchreiber.Ergebnis.Fehler -> geschrieben(e)
        }
    }

    /** A blank tag while nothing waits: offered for „Neuen Tag beschreiben“ (§2). */
    private fun leerAngeboten() {
        if (bearbeitet != null) bearbeite(null, false)
        stand(getString(R.string.ausloeser_leer))
        findViewById<EditText>(R.id.ausloeser_name).requestFocus()
    }

    private fun geschrieben(e: TagSchreiber.Ergebnis) {
        val tag = offen
        if (!wartet || tag == null) return
        if (e == TagSchreiber.Ergebnis.Fertig) offen = null
        warteAus() // the button says what is next: a new tag, or the same one again
        if (e is TagSchreiber.Ergebnis.Fehler) return stand(e.satz)
        findViewById<EditText>(R.id.ausloeser_name).text.clear()
        stand(getString(R.string.ausloeser_fertig, tag.first))
    }

    private fun warteAus() {
        wartet = false
        main.removeCallbacks(zuSpaet)
        knopfRuht()
    }

    private fun stand(text: String) {
        findViewById<TextView>(R.id.ausloeser_stand).text = text
    }

    // --- the charger --------------------------------------------------------------

    private fun renderLaden() {
        val an = Ausloeser.ladenMelden(this)
        setzt = true
        findViewById<SwitchCompat>(R.id.ausloeser_laden).isChecked = an
        setzt = false
        val zeilen = mutableListOf<String>()
        if (an && !Live.wanted(this)) zeilen += getString(R.string.ausloeser_laden_ohne_widget)
        Ausloeser.zuletzt(this)?.let { (satz, um) -> zeilen += getString(R.string.ausloeser_laden_zuletzt, satz, Anzeige.zeit(um)) }
        findViewById<TextView>(R.id.ausloeser_laden_stand).text = zeilen.joinToString("\n")
    }

    companion object {
        /** A touched unknown tag's id: „Benennen“ names it here (§2). */
        const val EXTRA_TAG = "cloud.dopp.homes.ausloeser.TAG"

        /** The tag the person holds to the phone; replaced in tests. */
        @VisibleForTesting
        @Volatile
        var schreiber: TagSchreiber = NfcSchreiber

        /** How long the screen waits for a tag before it says none came. */
        private const val WARTEN_MS = 60_000L
    }
}
