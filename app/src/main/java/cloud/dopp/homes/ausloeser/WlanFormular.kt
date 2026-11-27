package cloud.dopp.homes.ausloeser

import android.app.Activity
import android.nfc.NdefMessage
import android.os.Handler
import android.os.Looper
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.view.isVisible
import cloud.dopp.homes.R

/**
 * „+ WLAN-Tag beschreiben“ on the Auslöser screen (the owner, 2026-11-27: „Ja
 * WLAN schreiben“): the network's name and password, open or not, then the
 * next tag held to the phone gets one WLAN record ([WlanTag]) — and nothing
 * else happens: no call to Homes, nothing stored, nothing logged. The
 * password field is not part of the screen's saved state and is cleared when
 * the form closes. Touched later, the tag is Android's own (it offers to join
 * the network); Homes' activities do not take it.
 *
 * Its views are looked up once, on [a]. While it waits the phone's NFC is
 * this form's; [zurueck] gives it back to the screen, [sagen] says the end on
 * the screen's line once the form has closed.
 */
internal class WlanFormular(
    private val a: Activity,
    private val schreiber: () -> TagSchreiber,
    private val zurueck: () -> Unit,
    /** The other form closes while this one is open. */
    private val aufgeklappt: () -> Unit,
    private val sagen: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val zeile = a.findViewById<TextView>(R.id.ausloeser_wlan_zeile)
    private val formular = a.findViewById<View>(R.id.wlan_formular)
    private val ssid = a.findViewById<EditText>(R.id.wlan_ssid)
    private val passwort = a.findViewById<EditText>(R.id.wlan_passwort)
    private val zeigen = a.findViewById<CheckBox>(R.id.wlan_zeigen)
    private val schutz = a.findViewById<RadioGroup>(R.id.wlan_schutz)
    private val knopf = a.findViewById<Button>(R.id.wlan_schreiben)
    private val abbrechen = a.findViewById<Button>(R.id.wlan_abbrechen)
    private val anbieten = a.findViewById<Button>(R.id.wlan_anbieten)
    private val stand = a.findViewById<TextView>(R.id.wlan_stand)

    /** Waiting for a tag: the screen's NFC is this form's. */
    var wartet = false
        private set

    private var nachricht: Pair<String, NdefMessage>? = null

    private val zuSpaet = Runnable {
        warteAus()
        stand.text = a.getString(R.string.wlan_kein_tag)
    }

    init {
        zeile.setOnClickListener { auf(true) }
        abbrechen.setOnClickListener {
            val warteteGerade = wartet
            auf(false)
            if (warteteGerade) sagen(a.getString(R.string.wlan_abgebrochen))
        }
        zeigen.setOnCheckedChangeListener { _, an ->
            passwort.transformationMethod = if (an) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
            passwort.setSelection(passwort.text.length)
        }
        schutz.setOnCheckedChangeListener { _, id -> passwortSichtbar(id != R.id.wlan_offen) }
        knopf.setOnClickListener { tippe() }
        anbieten.setOnClickListener { anbieten() }
    }

    /** Open, or closed with everything typed forgotten. */
    fun auf(an: Boolean) {
        if (!an && wartet) warteAus()
        formular.isVisible = an
        zeile.isVisible = !an
        if (an) {
            aufgeklappt()
            stand.text = ""
            ssid.requestFocus()
        } else {
            ssid.text.clear()
            passwort.text.clear() // the password stays nowhere once the form is closed
            zeigen.isChecked = false
            schutz.check(R.id.wlan_wpa2)
        }
    }

    /** „Neu beschreiben“ on a WLAN tag read: the form open with what was on it. */
    fun vorbelegen(n: WlanTag.Netz) {
        auf(true)
        ssid.setText(n.ssid)
        schutz.check(if (n.schutz == WlanTag.Schutz.OFFEN) R.id.wlan_offen else R.id.wlan_wpa2)
        passwort.setText(n.passwort)
    }

    /** The screen is in front again while waiting: this form's record goes on. */
    fun wiederAn() {
        nachricht?.let { (name, n) -> lesen(name, n) }
    }

    private fun passwortSichtbar(an: Boolean) {
        passwort.isVisible = an
        zeigen.isVisible = an
    }

    private fun zeigeStand(text: String) {
        stand.text = text
    }

    private fun tippe() {
        if (wartet) {
            warteAus()
            zeigeStand(a.getString(R.string.wlan_abgebrochen))
            return
        }
        val (name, n) = eingabe() ?: return
        val fehlt = when (schreiber().bereit(a)) {
            TagSchreiber.Bereit.KEIN_NFC -> a.getString(R.string.ausloeser_kein_nfc)
            TagSchreiber.Bereit.AUS -> a.getString(R.string.ausloeser_nfc_aus)
            TagSchreiber.Bereit.JA -> null
        }
        if (fehlt != null) return zeigeStand(fehlt)
        nachricht = name to n
        wartet = true
        knopf.text = a.getString(R.string.ausloeser_abbrechen)
        zeigeStand(a.getString(R.string.wlan_halte, name))
        lesen(name, n)
        main.removeCallbacks(zuSpaet)
        main.postDelayed(zuSpaet, WARTEN_MS)
    }

    /** The form as a record, or null with what is wrong said. */
    private fun eingabe(): Pair<String, NdefMessage>? {
        val name = ssid.text.toString().trim()
        val offen = schutz.checkedRadioButtonId == R.id.wlan_offen
        val art = if (offen) WlanTag.Schutz.OFFEN else WlanTag.Schutz.WPA2
        val geheim = if (offen) "" else passwort.text.toString()
        WlanTag.pruefen(name, geheim, art)?.let {
            zeigeStand(it)
            return null
        }
        return name to WlanTag.nachricht(name, geheim, art)
    }

    /** „Anbieten statt schreiben“: the phone itself answers as the tag, for two minutes (WlanAnbietenActivity). */
    private fun anbieten() {
        if (wartet) warteAus()
        val (name, n) = eingabe() ?: return
        auf(false) // the form forgets what was typed; the offer holds it in memory only
        WlanAnbietenActivity.oeffnen(a, name, n)
    }

    private fun lesen(name: String, n: NdefMessage) {
        schreiber().anNachricht(a, n, { braucht, hat -> a.getString(R.string.wlan_zu_klein, braucht, hat) }) { e ->
            a.runOnUiThread { geschrieben(name, e) }
        }
    }

    private fun geschrieben(name: String, e: TagSchreiber.Ergebnis) {
        if (a.isFinishing || !wartet) return
        when (e) {
            TagSchreiber.Ergebnis.Fertig -> {
                auf(false)
                sagen(a.getString(R.string.wlan_fertig, name))
            }
            is TagSchreiber.Ergebnis.Fehler -> {
                warteAus()
                zeigeStand(e.satz)
            }
            is TagSchreiber.Ergebnis.Gelesen -> zeigeStand(a.getString(R.string.wlan_schon_beschrieben)) // never written over unasked
            is TagSchreiber.Ergebnis.EigeneDaten -> zeigeStand(a.getString(R.string.karte_eigene_daten, e.karte.typ))
            is TagSchreiber.Ergebnis.Unklar -> zeigeStand(a.getString(R.string.karte_unklar, e.karte.typ))
            TagSchreiber.Ergebnis.Unlesbar -> zeigeStand(a.getString(R.string.ausloeser_unlesbar))
            else -> Unit // only looked at: not this form's
        }
    }

    private fun warteAus() {
        wartet = false
        nachricht = null
        main.removeCallbacks(zuSpaet)
        knopf.text = a.getString(R.string.ausloeser_schreiben)
        zurueck()
    }

    companion object {
        /** How long the form waits for a tag before it says none came. */
        const val WARTEN_MS = 60_000L
    }
}
