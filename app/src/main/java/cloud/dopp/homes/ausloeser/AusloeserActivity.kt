package cloud.dopp.homes.ausloeser

import android.content.Intent
import android.nfc.NdefMessage
import android.os.Build
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
import cloud.dopp.homes.R
import cloud.dopp.homes.ausloeser.AusloeserClient.Ausgang
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.realtime.Live
import cloud.dopp.homes.teilen.Behalten
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
 * does not come within a minute is said too. A written tag held to the
 * phone shows what is on it first ([TagInhalt], [TagInhaltAnsicht]): a Homes
 * tag with „Bearbeiten“ and „Auslösen“, a WLAN with „Neu beschreiben“ and
 * „Ins Telefon übernehmen“, anything else with „Überschreiben“ behind one
 * question; nothing that holds something is written over unasked.
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

    /** „+ WLAN-Tag beschreiben“ (owner 2026-11-27): its own form, nothing of it leaves the phone. */
    private lateinit var wlan: WlanFormular

    /** The tag's automations, while one of HA's tags is changed (§8). */
    private lateinit var automationen: TagAutomationen

    /** What is on a written tag held to the phone. */
    private lateinit var inhalt: TagInhaltAnsicht

    /** HA's tags as the list said them last: a Homes tag held to the phone is looked up here. */
    private var tags: List<AusloeserClient.Tag> = emptyList()

    /** „Ins Telefon übernehmen“: Android's answer, said on the card. */
    private var uebernommen: ((String) -> Unit)? = null
    private var uebernimmt = ""
    private val wlanFrage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val satz = if (r.resultCode == RESULT_OK) getString(R.string.inhalt_uebernommen, uebernimmt) else getString(R.string.inhalt_nicht_uebernommen)
        uebernommen?.invoke(satz)
        uebernommen = null
    }

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
        wlan = WlanFormular(this, { schreiber }, ::leserZurueck, ::zuklappen, ::stand)
        inhalt = TagInhaltAnsicht(this)
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
        if (wlan.wartet) wlan.wiederAn() else leserZurueck()
    }

    override fun onPause() {
        schreiber.aus(this)
        automationen.aufhoeren() // a waiting wish is asked after only while the screen is open
        super.onPause()
    }

    /** The phone's NFC for this screen: written while a new tag waits, else a blank tag is offered (§2). */
    private fun leserZurueck() = schreiber.an(this, { offen?.takeIf { wartet }?.second?.uri }, ::kam)

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
                    tags = a.tags
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
            val zeile = unter(t, wartend).fold(name) { z, u -> getString(R.string.ausloeser_tag_mit_aktion, z, u) }
            liste.addView(text(zeile) { bearbeite(t, true) })
        }
    }

    /** What is said under a tag's name: its action, its automations, a waiting wish, its last run. */
    private fun unter(t: AusloeserClient.Tag, wartend: Set<String>): List<String> = listOfNotNull(
        // what it does, when it is not HA's report (§4); the id is never shown (§2)
        aktionen.firstOrNull { it.id == t.aktion && t.aktion.isNotEmpty() }?.titel,
        // its automations (§8), and a wish of this phone's that waits for an admin
        t.automationen.takeIf { it.isNotEmpty() }?.let { a -> getString(R.string.ausloeser_tag_startet, a.joinToString(", ") { it.name }) },
        getString(R.string.ausloeser_tag_wartet).takeIf { t.tag in wartend },
        t.zuletzt?.let(::zuletzt),
    )

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
        if (an) wlan.auf(false) // one form at a time
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
            is TagSchreiber.Ergebnis.EigeneDaten -> karte(e.karte, null) // never written: no button for it
            is TagSchreiber.Ergebnis.Unklar -> karte(e.karte, e.id)
            TagSchreiber.Ergebnis.Unlesbar -> stand(getString(R.string.ausloeser_unlesbar))
            is TagSchreiber.Ergebnis.Gelesen -> gelesen(e) // also while waiting: it is not written over unasked
            TagSchreiber.Ergebnis.Fertig, is TagSchreiber.Ergebnis.Fehler -> geschrieben(e)
        }
    }

    // --- what is on a written tag ---------------------------------------------------

    private fun gelesen(e: TagSchreiber.Ergebnis.Gelesen) {
        val i = TagInhalt.lesen(e.nachricht, ServerStore.baseUrl(this)?.toUri()?.host)
        val vorschlag = when (i) {
            is TagInhalt.Wlan -> i.netz.ssid
            is TagInhalt.Homes -> tags.firstOrNull { it.tag == i.id }?.name?.ifEmpty { null } ?: getString(R.string.teilen_art_homes)
            is TagInhalt.Fremd -> getString(R.string.inhalt_behalten_vorschlag)
        }
        when (i) {
            is TagInhalt.Homes -> homesTag(i.id)
            is TagInhalt.Wlan -> wlanTag(i.netz, e.id, e.nachricht)
            is TagInhalt.Fremd -> inhalt.zeige(
                getString(R.string.inhalt_fremd), i.zeilen, null,
                listOf(TagInhaltAnsicht.Knopf(getString(R.string.inhalt_ueberschreiben)) { fragUeberschreiben(e.id) { neuerTag(getString(R.string.inhalt_neu_titel)) } }),
            )
        }
        inhalt.dazu(TagInhaltAnsicht.Knopf(getString(R.string.inhalt_behalten)) { fertig -> behalten(e.nachricht, vorschlag, fertig) })
    }

    /** „Im Telefon behalten“ (owner 2026-11-28): a name asked, the tag's message kept encrypted on this phone to share again. */
    private fun behalten(n: NdefMessage, vorschlag: String, fertig: (String) -> Unit) {
        val feld = EditText(this).apply {
            setText(vorschlag)
            setSelection(text.length)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.inhalt_behalten_titel)
            .setView(feld)
            .setPositiveButton(R.string.inhalt_behalten_ok) { _, _ ->
                val name = feld.text.toString().trim().ifEmpty { vorschlag }
                Behalten.dazu(this, name, n)
                fertig(getString(R.string.inhalt_behalten_fertig, name))
            }
            .setNegativeButton(R.string.ausloeser_abbrechen) { _, _ -> fertig("") }
            .setOnCancelListener { fertig("") }
            .show()
    }

    /** One of Homes' tags: its name, action and automations from HA's list, „Bearbeiten“ and „Auslösen“. */
    private fun homesTag(id: String) {
        val t = tags.firstOrNull { it.tag == id }
        val titel = when {
            t == null -> getString(R.string.inhalt_homes_unbekannt)
            t.name.isEmpty() -> getString(R.string.inhalt_homes_ohne)
            else -> getString(R.string.inhalt_homes_titel, t.name)
        }
        val bearbeiten = TagInhaltAnsicht.Knopf(getString(R.string.inhalt_bearbeiten)) {
            inhalt.schliessen()
            bearbeite(t ?: AusloeserClient.Tag(id, ""), t != null)
        }
        val ausloesen = TagInhaltAnsicht.Knopf(getString(R.string.inhalt_ausloesen), getString(R.string.inhalt_loest_aus)) { fertig -> ausloesen(id, fertig) }
        inhalt.zeige(titel, t?.let { unter(it, Ausloeser.wartende(this).values.toSet()) }.orEmpty(), null, listOf(bearbeiten, ausloesen))
    }

    /** „Auslösen“: the tag reported as if touched outside the screen (§1), its answer on the card. */
    private fun ausloesen(id: String, fertig: (String) -> Unit) {
        val k = gekoppelt() ?: return fertig(Ausloeser.NICHT_GEKOPPELT)
        thread(name = "tag-ausloesen") {
            val a = client(k).nfc(id)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                fertig(Ausloeser.tagZeile(a))
                ladeTags() // its last run
            }
        }
    }

    /**
     * A WLAN tag: its network, the password behind „zeigen“, „Neu beschreiben“ and „Ins Telefon übernehmen“,
     * which then becomes „WLAN anbieten“ (the record as read, offered from memory for two minutes).
     */
    private fun wlanTag(n: WlanTag.Netz, id: ByteArray?, gelesen: NdefMessage) {
        val neu = TagInhaltAnsicht.Knopf(getString(R.string.inhalt_neu_beschreiben)) {
            fragUeberschreiben(id) {
                inhalt.schliessen()
                wlan.vorbelegen(n)
            }
        }
        val anbieten = TagInhaltAnsicht.Knopf(getString(R.string.inhalt_anbieten)) {
            inhalt.schliessen()
            WlanAnbietenActivity.oeffnen(this, n.ssid, gelesen)
        }
        val uebernehmen = TagInhaltAnsicht.Knopf(getString(R.string.inhalt_uebernehmen), getString(R.string.inhalt_uebernimmt), anbieten) { fertig ->
            uebernehmen(n, fertig)
        }
        val zeilen = listOf(getString(R.string.inhalt_wlan_name, n.ssid), getString(R.string.inhalt_wlan_schutz, n.art))
        inhalt.zeige(getString(R.string.inhalt_wlan_titel), zeilen, n.passwort.ifEmpty { null }, listOf(neu, uebernehmen))
    }

    private fun uebernehmen(n: WlanTag.Netz, fertig: (String) -> Unit) {
        WlanUebernahme.warumNicht(n)?.let { return fertig(it) }
        val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) WlanUebernahme.absicht(n) else null
        if (i == null) return fertig(getString(R.string.inhalt_passwort_nimmt_nicht))
        uebernimmt = n.ssid
        uebernommen = fertig
        runCatching { wlanFrage.launch(i) }.onFailure {
            uebernommen = null
            fertig(getString(R.string.inhalt_passwort_nimmt_nicht))
        }
    }

    /** „Der Inhalt geht verloren.“ — on yes the tag [id] may be written once, and [dann] goes on. */
    private fun fragUeberschreiben(id: ByteArray?, dann: () -> Unit) =
        frage(id, R.string.inhalt_frage_titel, R.string.inhalt_frage, R.string.inhalt_ueberschreiben, dann)

    /**
     * A card with data of its own (owner 2026-11-28): what reads without keys, on the screen only, with
     * „Kopieren“ — and no button that writes. [unklar]: its memory did not read; „Ist ein leerer Aufkleber …“
     * asks first, and only on „Ja, leerer Aufkleber“ may that card be formatted.
     */
    private fun karte(k: Karte, unklar: ByteArray?) {
        val kopieren = TagInhaltAnsicht.Knopf(getString(R.string.karte_kopieren)) { fertig ->
            inhalt.kopieren(TagBefund.hexAlles(k))
            fertig(getString(R.string.karte_kopiert))
        }.takeIf { k.bloecke.any { it.daten != null } }
        val leer = unklar?.let { id ->
            TagInhaltAnsicht.Knopf(getString(R.string.karte_leer_knopf)) {
                frage(id, R.string.karte_leer_titel, R.string.karte_leer_frage, R.string.karte_leer_ja) { leerAngeboten() }
            }
        }
        val satz = if (unklar == null) getString(R.string.karte_eigene_daten, k.typ) else getString(R.string.karte_unklar, k.typ)
        val titel = getString(if (unklar == null) R.string.karte_titel else R.string.karte_titel_unklar)
        inhalt.zeige(titel, listOf(satz, getString(R.string.karte_nachspielen)), null, listOfNotNull(leer, kopieren), TagBefund.text(k))
    }

    /** One question before a tag that may hold something is written: on [ja], [id] may be written once. */
    private fun frage(id: ByteArray?, titel: Int, nachricht: Int, ja: Int, dann: () -> Unit) {
        ActionDialog.show(
            activity = this,
            title = getString(titel),
            message = getString(nachricht),
            sheet = ActionSheet(listOf(ActionItem(ja, ActionTone.DANGEROUS)), R.string.inhalt_frage_hinweis),
            onPick = {
                schreiber.erlauben(id)
                inhalt.schliessen()
                if (wartet || wlan.wartet) stand(getString(R.string.inhalt_halte_nochmal)) else dann()
            },
            onCancel = {},
            paintScrim = false,
        )
    }

    /** A blank tag while nothing waits: the form opens for it by itself, its name asked for (§2). */
    private fun leerAngeboten() = neuerTag(getString(R.string.ausloeser_neu_erkannt))

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


        /** How long the screen waits for a tag before it says none came. */
        private const val WARTEN_MS = 60_000L
    }
}
