package cloud.dopp.homes.teilen

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import cloud.dopp.homes.R
import cloud.dopp.homes.ui.padForSystemBars

/**
 * „Meine Visitenkarte“ under „Dieses Telefon“ (owner 2026-11-28): the
 * person's own contact picked once from the phone's contacts, the fields to
 * share ticked (Name, Mobil, Telefon, Mail, Adresse, Geburtstag, Firma), kept as a vCard in the
 * [Tresor] — on this phone only, shared only when chosen in [TeilenActivity].
 */
class VisitenkarteActivity : AppCompatActivity() {

    private var kontakt: Visitenkarte.Kontakt? = null
    private var gewaehlt: Uri? = null

    private val felder by lazy {
        mapOf(
            Visitenkarte.Feld.NAME to R.id.karte_name, Visitenkarte.Feld.MOBIL to R.id.karte_mobil,
            Visitenkarte.Feld.TELEFON to R.id.karte_telefon, Visitenkarte.Feld.MAIL to R.id.karte_mail,
            Visitenkarte.Feld.ADRESSE to R.id.karte_adresse, Visitenkarte.Feld.GEBURTSTAG to R.id.karte_geburtstag,
            Visitenkarte.Feld.FIRMA to R.id.karte_firma,
        ).mapValues { findViewById<CheckBox>(it.value) }
    }

    private val waehlen = registerForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        gewaehlt = uri ?: return@registerForActivityResult
        if (darf()) lies() else erlauben.launch(Manifest.permission.READ_CONTACTS)
    }

    private val erlauben = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ja ->
        if (ja) lies() else sag(getString(R.string.karte_ohne_recht))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_visitenkarte)
        padForSystemBars(findViewById(R.id.karte_root))
        findViewById<Button>(R.id.karte_waehlen).setOnClickListener { runCatching { waehlen.launch(null) } }
        findViewById<Button>(R.id.karte_sichern).setOnClickListener { sichern() }
        findViewById<Button>(R.id.karte_entfernen).setOnClickListener {
            Behalten.setzeVisitenkarte(this, null)
            sag(getString(R.string.karte_entfernt))
        }
        sag(if (Behalten.visitenkarte(this) != null) getString(R.string.karte_gesetzt) else "")
    }

    private fun darf() = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun lies() {
        val uri = gewaehlt ?: return
        val k = runCatching { kontakt(uri) }.getOrNull() ?: return sag(getString(R.string.karte_nicht_lesbar))
        kontakt = k
        findViewById<TextView>(R.id.karte_wer).text = k.name ?: getString(R.string.karte_ohne_namen)
        Visitenkarte.Feld.entries.forEach { f ->
            val w = k.wert(f)
            felder.getValue(f).apply {
                isEnabled = w != null
                isChecked = w != null
                text = getString(TITEL.getValue(f), w ?: "–")
            }
        }
        sag("")
    }

    /** The picked contact's fields, the first of each kind. */
    private fun kontakt(uri: Uri): Visitenkarte.Kontakt? {
        val id = contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        } ?: return null
        val zeilen = mutableListOf<Visitenkarte.Zeile>()
        contentResolver.query(
            ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, ContactsContract.Data.DATA2),
            "${ContactsContract.Data.CONTACT_ID} = ?", arrayOf(id.toString()), null,
        )?.use { c -> while (c.moveToNext()) c.getString(1)?.let { zeilen += Visitenkarte.Zeile(c.getString(0), it, c.getInt(2)) } }
        return Visitenkarte.kontakt(zeilen)
    }

    private fun sichern() {
        val k = kontakt ?: return sag(getString(R.string.karte_erst_waehlen))
        val welche = felder.filterValues { it.isChecked }.keys
        if (welche.isEmpty()) return sag(getString(R.string.karte_nichts_gewaehlt))
        Behalten.setzeVisitenkarte(this, Visitenkarte.vcard(k, welche))
        sag(getString(R.string.karte_gesichert))
    }

    private fun sag(satz: String) {
        findViewById<TextView>(R.id.karte_stand).text = satz
    }

    private companion object {
        val TITEL = mapOf(
            Visitenkarte.Feld.NAME to R.string.karte_feld_name, Visitenkarte.Feld.MOBIL to R.string.karte_feld_mobil,
            Visitenkarte.Feld.TELEFON to R.string.karte_feld_telefon, Visitenkarte.Feld.MAIL to R.string.karte_feld_mail,
            Visitenkarte.Feld.ADRESSE to R.string.karte_feld_adresse, Visitenkarte.Feld.GEBURTSTAG to R.string.karte_feld_geburtstag,
            Visitenkarte.Feld.FIRMA to R.string.karte_feld_firma,
        )
    }
}
