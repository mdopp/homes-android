package cloud.dopp.homes.ausloeser

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.IntentCompat
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
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
 * Mehr → Auslöser (homes ADR 0004 §1, §2, §4), shown while the box offers it
 * (`mehr` `ausloeser`, the geraete module): HA's tags; „Neuen Tag beschreiben“
 * — a name (the house's rooms as suggestions), the tag made in HA, „Halte den
 * Tag ans Telefon …“, Homes' URI written onto it, done or why not; a tag
 * tapped in the list is renamed („Sichern“) or removed after one question,
 * and a touched unknown tag's „Benennen“ ([EXTRA_TAG]) names that id — never
 * written again, so its automations keep working. „Was soll der Tag tun?“
 * offers exactly the actions the box lists (§4: HA's report first, the
 * scanner's where the box has one), sent with „Sichern“ and „Tag beschreiben“. While the screen is in
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

    /** The actions the box offers a tag, as the list said them last (§4); empty: an older Homes, none sent. */
    private var aktionen: List<AusloeserClient.Aktion> = emptyList()

    /** The chosen action: the tag's own while it is changed, HA's (`""`) for a new one. */
    private var gewaehlt = ""

    /** The tag's automations, while one of HA's tags is changed (§8). */
    private lateinit var automationen: TagAutomationen

    /** A blank tag Android handed over (LeerTagActivity), not written yet. */
    private var gehalten: Parcelable? = null

    private val zuSpaet = Runnable {
        warteAus()
        stand(getString(R.string.ausloeser_kein_tag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ausloeser)
        padForSystemBars(findViewById(R.id.ausloeser_root))
        automationen = TagAutomationen(this, ::gekoppelt, ::ladeTags)
        findViewById<SwitchCompat>(R.id.ausloeser_laden).setOnCheckedChangeListener { _, an ->
            if (setzt) return@setOnCheckedChangeListener
            Ausloeser.setzeLadenMelden(this, an)
            Live.ensure(this) // the running live service takes the switch at its next start command
            renderLaden()
        }
        findViewById<Button>(R.id.ausloeser_schreiben).setOnClickListener { tippe() }
        findViewById<Button>(R.id.ausloeser_entfernen).setOnClickListener { bearbeitet?.let(::frageEntfernen) }
        findViewById<Button>(R.id.ausloeser_zurueck).setOnClickListener {
            zuklappen()
            stand("")
        }
        findViewById<TextView>(R.id.ausloeser_neu_zeile).setOnClickListener { neuerTag(getString(R.string.ausloeser_neu_titel)) }
        findViewById<TextView>(R.id.ausloeser_stand).setOnClickListener {
            if (schreiber.bereit(this) == TagSchreiber.Bereit.AUS) runCatching { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
        }
        intent?.getStringExtra(EXTRA_TAG)?.let { bearbeite(AusloeserClient.Tag(it, ""), false) }
        // a blank tag touched anywhere (LeerTagActivity): the form for it, the tag kept to write it at once
        intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_LEER, Parcelable::class.java) }?.let {
            gehalten = it
            leerAngeboten()
        }
    }

    override fun onResume() {
        super.onResume()
        renderLaden()
        ladeTags()
        automationen.anfangen()
        // reader mode while in front: written while waiting, else a blank tag is offered (§2)
        schreiber.an(this, { offen?.takeIf { wartet }?.second?.uri }, ::kam)
    }

    override fun onPause() {
        schreiber.aus(this)
        automationen.aufhoeren() // a waiting wish is asked after only while the screen is open
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
        if (liste.isEmpty()) zeigeTags(liste, getString(R.string.ausloeser_tags_laden), emptyList())
        thread(name = "tags") {
            val a = client(kopplung).tags()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (a is Ausgang.Tags) {
                    aktionen = a.aktionen
                    zeigeTags(liste, if (a.tags.isEmpty()) getString(R.string.ausloeser_tags_keine) else null, a.tags)
                    folge(a.tags)
                    zeigeVorschlaege(a.vorschlaege)
                    zeigeAktionen()
                } else {
                    zeigeTags(liste, Ausloeser.fehler(a) ?: AusloeserClient.UNLESBAR, emptyList())
                }
            }
        }
    }

    /** The tag being changed, as HA has it now: its automations follow a change (§8). */
    private fun folge(tags: List<AusloeserClient.Tag>) {
        val jetzt = bearbeitet?.let { b -> tags.firstOrNull { it.tag == b.tag } } ?: return
        bearbeitet = jetzt
        automationen.zeige(jetzt.takeIf { ausListe && jetzt.aktion.isEmpty() })
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
        val wartend = Ausloeser.wartende(this).values.toSet()
        tags.forEach { t ->
            val name = t.name.ifEmpty { getString(R.string.ausloeser_ohne_namen) }
            // what it does, under the name, when it is not HA's report (§4); the id is never shown (§2)
            val tut = aktionen.firstOrNull { it.id == t.aktion && t.aktion.isNotEmpty() }?.titel
            // its automations (§8), and a wish of this phone's that waits for an admin
            val unter = listOfNotNull(
                tut,
                t.automationen.takeIf { it.isNotEmpty() }?.let { a -> getString(R.string.ausloeser_tag_startet, a.joinToString(", ") { it.name }) },
                getString(R.string.ausloeser_tag_wartet).takeIf { t.tag in wartend },
                t.zuletzt?.let(::zuletzt),
            )
            val zeile = unter.fold(name) { z, u -> getString(R.string.ausloeser_tag_mit_aktion, z, u) }
            liste.addView(text(zeile) { bearbeite(t, true) })
        }
    }

    /** When a tag last ran (§9): „zuletzt heute 18:12, Anna – Bürolicht aus“; without a readable time its text alone. */
    private fun zuletzt(z: AusloeserClient.Zuletzt): String {
        val wann = z.wann?.let { Ausloeser.wann(it) } ?: return z.text
        return if (z.person.isEmpty()) {
            getString(R.string.ausloeser_tag_zuletzt_ohne, wann, z.text)
        } else {
            getString(R.string.ausloeser_tag_zuletzt, wann, z.person, z.text)
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

    /** One choice per action the box lists, [gewaehlt] checked; nothing while it lists none. */
    private fun zeigeAktionen() {
        val gruppe = findViewById<RadioGroup>(R.id.ausloeser_aktion)
        setzt = true
        gruppe.setOnCheckedChangeListener(null)
        gruppe.removeAllViews()
        if (aktionen.isNotEmpty() && aktionen.none { it.id == gewaehlt }) gewaehlt = aktionen.first().id
        aktionen.forEach { a ->
            val knopf = RadioButton(this).apply {
                id = View.generateViewId()
                setTextAppearance(R.style.Text_Homes_Body)
                text = a.titel
                tag = a.id
            }
            gruppe.addView(knopf)
            if (a.id == gewaehlt) gruppe.check(knopf.id)
        }
        gruppe.setOnCheckedChangeListener { g, id ->
            if (!setzt) gewaehlt = g.findViewById<RadioButton>(id)?.tag as? String ?: gewaehlt
        }
        setzt = false
        val sichtbar = if (aktionen.isEmpty()) View.GONE else View.VISIBLE
        gruppe.visibility = sichtbar
        findViewById<View>(R.id.ausloeser_aktion_frage).visibility = sichtbar
    }

    /** The action to send: the chosen one, or none at all to an older Homes that lists none (it keeps the tag's). */
    private fun aktion(): String? = gewaehlt.takeIf { aktionen.isNotEmpty() }

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
        if (tag != null) aufklappen(true)
        findViewById<View>(R.id.ausloeser_entfernen).visibility = if (ausListe) View.VISIBLE else View.GONE
        gewaehlt = tag?.aktion.orEmpty()
        zeigeAktionen()
        automationen.zeige(tag?.takeIf { ausListe && it.aktion.isEmpty() })
        knopfRuht()
    }

    /** The form open in place of the list's last row, or closed to it (owner 2026-11-27: „Neu nimmt zu viel Platz ein“). */
    private fun aufklappen(an: Boolean) {
        findViewById<View>(R.id.ausloeser_formular).isVisible = an
        findViewById<View>(R.id.ausloeser_neu_zeile).isVisible = !an
    }

    /** „+ Neuen Tag beschreiben“, or a blank tag held to the phone: the form open for a new tag, [titel] above it. */
    private fun neuerTag(titel: String) {
        bearbeite(null, false)
        aufklappen(true)
        findViewById<TextView>(R.id.ausloeser_name_titel).text = titel
        stand("")
        findViewById<EditText>(R.id.ausloeser_name).let { name -> name.post { name.requestFocus() } } // also from onCreate: once laid out
    }

    /** Back to the list: nothing changed, the form closed. */
    private fun zuklappen() {
        bearbeite(null, false)
        aufklappen(false)
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
            val a = client(kopplung).benennen(name, aktion = aktion())
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
            val a = client(kopplung).benennen(name, tag.tag, aktion())
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (a is Ausgang.Angelegt) {
                    zuklappen()
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
                    zuklappen()
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
        gehalten?.let { direkt(it, tag) }
    }

    /**
     * The blank tag Android handed over, written at once while it still holds it; once only. Gone (not held
     * any more) the wait goes on: the person holds it to the phone again, as for any new tag.
     */
    private fun direkt(g: Parcelable, tag: Pair<String, Ausgang.Angelegt>) {
        gehalten = null
        stand(getString(R.string.ausloeser_schreibt_direkt, tag.first))
        thread(name = "tag-direkt") {
            val e = schreiber.direkt(g, tag.second.uri)
            runOnUiThread {
                if (isFinishing || !wartet || offen !== tag) return@runOnUiThread
                when (e) {
                    TagSchreiber.Ergebnis.Fertig -> geschrieben(e)
                    is TagSchreiber.Ergebnis.Fehler -> stand(e.satz) // the wait goes on: held again, it is written
                    else -> stand(getString(R.string.ausloeser_halte_nochmal, tag.first))
                }
            }
        }
    }

    /** A tag came, from NFC's thread: written while waiting, else looked at. */
    private fun kam(e: TagSchreiber.Ergebnis) = runOnUiThread {
        if (isFinishing) return@runOnUiThread
        when (e) {
            TagSchreiber.Ergebnis.Leer -> if (!wartet) leerAngeboten()
            TagSchreiber.Ergebnis.Beschrieben -> if (!wartet) kurz(getString(R.string.ausloeser_schon_beschrieben))
            TagSchreiber.Ergebnis.Fertig, is TagSchreiber.Ergebnis.Fehler -> geschrieben(e)
        }
    }

    /** A blank tag while nothing waits: the form opens for it by itself, its name asked for (§2). */
    private fun leerAngeboten() = neuerTag(getString(R.string.ausloeser_neu_erkannt))

    /** A note that goes again after a moment, unless something else was said meanwhile. */
    private fun kurz(text: String) {
        stand(text)
        main.postDelayed({ if (findViewById<TextView>(R.id.ausloeser_stand).text.toString() == text) stand("") }, KURZ_MS)
    }

    private fun geschrieben(e: TagSchreiber.Ergebnis) {
        val tag = offen
        if (!wartet || tag == null) return
        if (e == TagSchreiber.Ergebnis.Fertig) offen = null
        warteAus() // the button says what is next: a new tag, or the same one again
        if (e is TagSchreiber.Ergebnis.Fehler) return stand(e.satz)
        findViewById<EditText>(R.id.ausloeser_name).text.clear()
        gewaehlt = "" // the next new tag starts as HA's
        zeigeAktionen()
        zuklappen()
        stand(getString(R.string.ausloeser_fertig, tag.first))
    }

    private fun warteAus() {
        wartet = false
        main.removeCallbacks(zuSpaet)
        knopfRuht()
    }

    private fun stand(text: String) {
        findViewById<TextView>(R.id.ausloeser_stand).apply {
            this.text = text
            isVisible = text.isNotEmpty() // no empty line under the list
        }
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

        /** A blank tag touched anywhere (LeerTagActivity): Android's Tag, to write it without holding it again. */
        const val EXTRA_LEER = "cloud.dopp.homes.ausloeser.LEER"

        /** The tag the person holds to the phone; replaced in tests. */
        @VisibleForTesting
        @Volatile
        var schreiber: TagSchreiber = NfcSchreiber

        /** How long a passing note stays. */
        private const val KURZ_MS = 4_000L

        /** How long the screen waits for a tag before it says none came. */
        private const val WARTEN_MS = 60_000L
    }
}
