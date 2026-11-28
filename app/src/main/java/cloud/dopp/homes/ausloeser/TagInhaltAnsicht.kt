package cloud.dopp.homes.ausloeser

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.PersistableBundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.view.isVisible
import cloud.dopp.homes.R

/**
 * The card on the Auslöser screen that says what is on a written tag held to
 * the phone ([TagInhalt], owner 2026-11-27): a title, its lines, a WLAN's
 * password hidden behind „zeigen“, and up to two buttons that answer on
 * themselves at once and end in the card's own line. The password is held
 * only while the card is open.
 */
internal class TagInhaltAnsicht(private val a: Activity) {

    /**
     * A button: its [text], what it says while it runs ([laeuft], null: it ends at once), the button it becomes
     * once done ([danach], null: itself again), and what it does.
     */
    class Knopf(val text: String, val laeuft: String? = null, val danach: Knopf? = null, val tun: (fertig: (String) -> Unit) -> Unit)

    private val karte = a.findViewById<View>(R.id.ausloeser_inhalt)
    private val titel = a.findViewById<TextView>(R.id.inhalt_titel)
    private val text = a.findViewById<TextView>(R.id.inhalt_text)
    private val pwZeile = a.findViewById<View>(R.id.inhalt_passwort_zeile)
    private val pw = a.findViewById<TextView>(R.id.inhalt_passwort)
    private val zeigen = a.findViewById<Button>(R.id.inhalt_zeigen)
    private val knoepfe = listOf(
        a.findViewById<Button>(R.id.inhalt_eins), a.findViewById<Button>(R.id.inhalt_zwei), a.findViewById<Button>(R.id.inhalt_drei),
    )
    private val stand = a.findViewById<TextView>(R.id.inhalt_stand)
    private val daten = a.findViewById<TextView>(R.id.inhalt_daten)
    private val datenRahmen = a.findViewById<View>(R.id.inhalt_daten_rahmen)

    private var passwort: String? = null
    private var klar = false

    init {
        a.findViewById<Button>(R.id.inhalt_zu).setOnClickListener { schliessen() }
        zeigen.setOnClickListener { passwortZeigen(!klar) }
    }

    /** Open with [ueber] and [zeilen]; [geheim] a password shown only on „zeigen“. */
    fun zeige(ueber: String, zeilen: List<String>, geheim: String?, mit: List<Knopf>, roh: String? = null) {
        daten.text = roh.orEmpty()
        datenRahmen.isVisible = roh != null
        titel.text = ueber
        text.text = zeilen.joinToString("\n")
        text.isVisible = zeilen.isNotEmpty()
        passwort = geheim
        pwZeile.isVisible = geheim != null
        passwortZeigen(false)
        knoepfe.forEachIndexed { i, b ->
            val k = mit.getOrNull(i)
            b.isVisible = k != null
            b.isEnabled = true
            b.text = k?.text
            b.setOnClickListener { k?.let { druecke(b, it) } }
        }
        sage("")
        karte.isVisible = true
        karte.post { karte.requestRectangleOnScreen(Rect(0, 0, karte.width, karte.height)) } // under the list: into view
    }

    /** One more button after what [zeige] showed: the last slot („Im Telefon behalten“). */
    fun dazu(k: Knopf) {
        val b = knoepfe.last()
        b.isVisible = true
        b.isEnabled = true
        b.text = k.text
        b.setOnClickListener { druecke(b, k) }
    }

    /** Closed, its password forgotten. */
    fun schliessen() {
        karte.isVisible = false
        passwort = null
        pw.text = ""
        daten.text = "" // a card's data: on the screen while open, nowhere else
    }

    /** „Kopieren“: [text] to the clipboard, marked sensitive (Android 13+ shows it masked). */
    fun kopieren(text: String) {
        val clip = ClipData.newPlainText(a.getString(R.string.karte_kopieren), text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        (a.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(clip)
    }

    /** Its own line: how a button's work ended. */
    fun sage(satz: String) {
        stand.text = satz
        stand.isVisible = satz.isNotEmpty()
    }

    private fun druecke(b: Button, k: Knopf) {
        if (k.laeuft != null) {
            b.isEnabled = false
            b.text = k.laeuft
        }
        k.tun { satz ->
            val weiter = k.danach ?: k
            b.isEnabled = true
            b.text = weiter.text
            b.setOnClickListener { druecke(b, weiter) }
            sage(satz)
        }
    }

    private fun passwortZeigen(an: Boolean) {
        klar = an
        val p = passwort.orEmpty()
        pw.text = a.getString(R.string.inhalt_passwort, if (an) p else PUNKT.repeat(p.length.coerceIn(MIN_PUNKTE, MAX_PUNKTE)))
        zeigen.text = a.getString(if (an) R.string.inhalt_verbergen else R.string.inhalt_zeigen)
    }

    private companion object {
        const val PUNKT = "•"
        const val MIN_PUNKTE = 8
        const val MAX_PUNKTE = 12
    }
}
