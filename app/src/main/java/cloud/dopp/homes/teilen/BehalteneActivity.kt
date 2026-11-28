package cloud.dopp.homes.teilen

import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import cloud.dopp.homes.R
import cloud.dopp.homes.ausloeser.TagInhalt
import cloud.dopp.homes.ui.padForSystemBars
import cloud.dopp.homes.widget.ActionDialog
import cloud.dopp.homes.widget.ActionItem
import cloud.dopp.homes.widget.ActionSheet
import cloud.dopp.homes.widget.ActionTone

/**
 * „Behaltene Tags“ under „Dieses Telefon“ (owner 2026-11-28): the tags kept
 * on this phone to share again, each by its name and what it carries in one
 * line; a tap asks once, then removes it.
 */
class BehalteneActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teilen)
        padForSystemBars(findViewById(R.id.teilen_root))
        findViewById<TextView>(R.id.teilen_ueberschrift).setText(R.string.behalten_titel)
        render()
    }

    private fun render() {
        val liste = findViewById<ViewGroup>(R.id.teilen_liste)
        liste.removeAllViews()
        val tags = Behalten.alle(this)
        findViewById<TextView>(R.id.teilen_hinweis).apply {
            setText(if (tags.isEmpty()) R.string.behalten_keine else R.string.behalten_hinweis)
        }
        tags.forEachIndexed { i, t ->
            liste.addView(
                layoutInflater.inflate(R.layout.item_einrichtung, liste, false).apply {
                    findViewById<ImageView>(R.id.einr_bild).setImageResource(R.drawable.ic_ausloeser)
                    findViewById<TextView>(R.id.einr_titel).text = t.name
                    findViewById<TextView>(R.id.einr_unter).apply {
                        text = when (val n = TagInhalt.lesen(t.nachricht, null)) {
                            is TagInhalt.Wlan -> getString(R.string.teilen_art_wlan, n.netz.ssid)
                            is TagInhalt.Homes -> getString(R.string.teilen_art_homes)
                            is TagInhalt.Fremd -> n.zeilen.first()
                        }
                        isVisible = true
                    }
                    setOnClickListener { frage(i, t.name) }
                },
            )
        }
    }

    private fun frage(i: Int, name: String) {
        ActionDialog.show(
            activity = this,
            title = getString(R.string.behalten_weg_titel, name),
            message = null,
            sheet = ActionSheet(listOf(ActionItem(R.string.ausloeser_entfernen, ActionTone.DANGEROUS)), R.string.behalten_weg_hinweis),
            onPick = {
                Behalten.weg(this, i)
                render()
            },
            onCancel = {},
            paintScrim = false,
        )
    }
}
