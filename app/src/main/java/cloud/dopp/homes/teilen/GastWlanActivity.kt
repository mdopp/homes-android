package cloud.dopp.homes.teilen

import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import cloud.dopp.homes.R
import cloud.dopp.homes.ausloeser.WlanTag
import cloud.dopp.homes.ui.padForSystemBars

/**
 * „Gast-WLAN festlegen“ (owner 2026-11-28: „WLAN Kennwort anbieten neben den
 * Kontakten“): the guest network's name, its password (with „zeigen“), with
 * a password or open — kept encrypted in the [Tresor] on this phone only,
 * changed or removed here. Opened from „Teilen“ (its „Festlegen“) it closes
 * once saved, and „Teilen“ offers it at once; from „Dieses Telefon“ it stays.
 */
class GastWlanActivity : AppCompatActivity() {

    private lateinit var ssid: EditText
    private lateinit var passwort: EditText
    private lateinit var schutz: RadioGroup
    private lateinit var stand: TextView
    private lateinit var entfernen: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gast_wlan)
        padForSystemBars(findViewById(R.id.gast_root))
        ssid = findViewById(R.id.gast_ssid)
        passwort = findViewById(R.id.gast_passwort)
        schutz = findViewById(R.id.gast_schutz)
        stand = findViewById(R.id.gast_stand)
        entfernen = findViewById(R.id.gast_entfernen)
        val zeigen = findViewById<CheckBox>(R.id.gast_zeigen)
        zeigen.setOnCheckedChangeListener { _, an ->
            passwort.transformationMethod = if (an) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
            passwort.setSelection(passwort.text.length)
        }
        schutz.setOnCheckedChangeListener { _, id ->
            passwort.isVisible = id != R.id.gast_offen
            zeigen.isVisible = id != R.id.gast_offen
        }
        Behalten.gastWlan(this)?.let { n ->
            ssid.setText(n.ssid)
            passwort.setText(n.passwort)
            schutz.check(if (n.schutz == WlanTag.Schutz.OFFEN) R.id.gast_offen else R.id.gast_wpa2)
        }
        entfernen.isVisible = Behalten.gastWlan(this) != null
        findViewById<Button>(R.id.gast_sichern).setOnClickListener { sichern() }
        entfernen.setOnClickListener {
            Behalten.setzeGastWlan(this, null)
            ssid.text.clear()
            passwort.text.clear()
            entfernen.isVisible = false
            stand.setText(R.string.gast_entfernt)
        }
    }

    private fun sichern() {
        val name = ssid.text.toString().trim()
        val art = if (schutz.checkedRadioButtonId == R.id.gast_offen) WlanTag.Schutz.OFFEN else WlanTag.Schutz.WPA2
        val geheim = if (art == WlanTag.Schutz.OFFEN) "" else passwort.text.toString()
        val fehler = WlanTag.pruefen(name, geheim, art)
        if (fehler != null) {
            stand.text = fehler
            return
        }
        Behalten.setzeGastWlan(this, WlanTag.Netz(name, art.authentifizierung, geheim))
        if (intent.getBooleanExtra(AUS_TEILEN, false)) {
            setResult(RESULT_OK)
            finish()
        } else {
            entfernen.isVisible = true
            stand.text = getString(R.string.gast_gesichert, name)
        }
    }

    override fun onDestroy() {
        if (isFinishing) passwort.text.clear() // the typed password stays nowhere but the Tresor
        super.onDestroy()
    }

    companion object {
        /** Opened from „Teilen“: closes once saved, with RESULT_OK. */
        const val AUS_TEILEN = "aus_teilen"
    }
}
