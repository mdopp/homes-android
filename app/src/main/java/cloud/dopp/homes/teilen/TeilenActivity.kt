package cloud.dopp.homes.teilen

import android.app.AlertDialog
import android.content.Intent
import android.nfc.NdefMessage
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import cloud.dopp.homes.R
import cloud.dopp.homes.ausloeser.TagInhalt
import cloud.dopp.homes.ausloeser.WlanAnbietenActivity
import cloud.dopp.homes.ui.padForSystemBars

/**
 * „Was teilen?“ (owner approved 2026-11-28): opened from the notification a
 * reading phone caused ([Teilen]) or from „Dieses Telefon“. Meine
 * Kontaktdaten, each kept tag by its name, „Nichts“; for an iPhone the card
 * or a WLAN as a QR code. A choice arms one answer for at most two minutes
 * ([WlanAnbietenActivity], one reader), then nothing again.
 */
class TeilenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teilen)
        padForSystemBars(findViewById(R.id.teilen_root))
        Teilen.weg(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val liste = findViewById<ViewGroup>(R.id.teilen_liste)
        liste.removeAllViews()
        val karte = Behalten.visitenkarte(this)
        if (karte != null) {
            liste.addView(reihe(liste, R.drawable.ic_modul_kontakte, getString(R.string.teilen_kontakt), null) { teilen(getString(R.string.teilen_kontakt), Visitenkarte.ndef(karte)) })
            liste.addView(reihe(liste, R.drawable.ic_konto, getString(R.string.teilen_qr, getString(R.string.teilen_kontakt)), null) { qr(karte) })
        } else {
            liste.addView(reihe(liste, R.drawable.ic_konto, getString(R.string.teilen_karte_festlegen), null) {
                startActivity(Intent(this, VisitenkarteActivity::class.java))
            })
        }
        Behalten.alle(this).forEach { t ->
            val inhalt = TagInhalt.lesen(t.nachricht, null)
            liste.addView(reihe(liste, R.drawable.ic_ausloeser, t.name, art(inhalt)) { teilen(t.name, t.nachricht) })
            if (inhalt is TagInhalt.Wlan) {
                liste.addView(reihe(liste, R.drawable.ic_ausloeser, getString(R.string.teilen_qr, t.name), null) { qr(Visitenkarte.wifi(inhalt.netz)) })
            }
        }
        liste.addView(reihe(liste, R.drawable.ic_ctl_stop, getString(R.string.teilen_nichts), null) { finish() })
    }

    private fun art(i: TagInhalt): String = when (i) {
        is TagInhalt.Wlan -> getString(R.string.teilen_art_wlan, i.netz.ssid)
        is TagInhalt.Homes -> getString(R.string.teilen_art_homes)
        is TagInhalt.Fremd -> i.zeilen.first()
    }

    /** One answer armed: the next reader gets [n], then it is over. */
    private fun teilen(name: String, n: NdefMessage) {
        WlanAnbietenActivity.oeffnen(this, name, n, einmal = true)
        finish()
    }

    /** For an iPhone: [text] as a QR code, on the screen only. */
    private fun qr(text: String) {
        val px = resources.displayMetrics.widthPixels * 3 / 4
        AlertDialog.Builder(this)
            .setView(ImageView(this).apply { setImageBitmap(Visitenkarte.qrBild(text, px)) })
            .setPositiveButton(R.string.inhalt_zu, null)
            .show()
    }

    private fun reihe(g: ViewGroup, @DrawableRes bild: Int, titel: String, unter: String?, tap: () -> Unit): View =
        layoutInflater.inflate(R.layout.item_einrichtung, g, false).apply {
            findViewById<ImageView>(R.id.einr_bild).setImageResource(bild)
            findViewById<TextView>(R.id.einr_titel).text = titel
            findViewById<TextView>(R.id.einr_unter).apply {
                text = unter
                isVisible = unter != null
            }
            setOnClickListener { tap() }
        }
}
