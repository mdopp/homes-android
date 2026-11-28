package cloud.dopp.homes.ausloeser

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.content.IntentCompat
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore

/**
 * A blank NFC tag touched anywhere (the owner, 2026-11-27: „kann sich Homes
 * für neue Tags registrieren?“): Android offers the app every tag it can
 * format or an Ndef tag that carries nothing (TECH_DISCOVERED; a tag with
 * records goes to its own app or to [TagActivity] first). A blank one opens
 * the Auslöser screen's form for a new tag, „Neuer Tag erkannt – wie soll er
 * heißen?“, with the touched tag handed along so „Tag beschreiben“ can write
 * it without holding it again while Android still has it. A tag with records
 * is not ours: nothing shows. Blank is decided as on the Auslöser screen
 * ([TagBefund], owner 2026-11-28): read first; a card with data of its own
 * (an access, bank or transit card) never opens the form — „Das ist keine
 * leere NFC-Karte.“ — and a read that failed says so. No UI of its own: it
 * decides and goes.
 */
class LeerTagActivity : Activity() {

    /** A touched tag: the tag itself, and what was found on it ([TagLeser]). */
    @VisibleForTesting
    class Gelesen(val tag: Parcelable, val befund: Befund)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val g = lesen(intent)
        val e = g?.let { TagBefund.einordnen(it.befund, null) }
        when {
            e is TagSchreiber.Ergebnis.EigeneDaten || e is TagSchreiber.Ergebnis.Unklar -> sag(getString(R.string.leer_keine))
            e == TagSchreiber.Ergebnis.Unlesbar -> sag(getString(R.string.ausloeser_unlesbar))
            g == null || e != TagSchreiber.Ergebnis.Leer -> Log.i(LOG, "tech: not blank") // not ours
            ServerStore.baseUrl(this) == null || TokenStore.get(this).isNullOrBlank() -> {
                Log.i(LOG, "tech: blank, unpaired")
                Toast.makeText(applicationContext, Ausloeser.NICHT_GEKOPPELT, Toast.LENGTH_LONG).show()
            }
            else -> {
                Log.i(LOG, "tech: blank")
                startActivity(
                    Intent(this, AusloeserActivity::class.java).putExtra(AusloeserActivity.EXTRA_LEER, g.tag)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            }
        }
        finish()
    }

    private fun sag(satz: String) {
        Log.i(LOG, "tech: not blank") // never the card's UID or data
        Toast.makeText(applicationContext, satz, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val LOG = "HomesTag"

        /** The touched tag from Android's intent; replaced in tests (a Tag cannot be made outside Android's NFC). */
        @VisibleForTesting
        @Volatile
        var lesen: (Intent?) -> Gelesen? = { i ->
            i?.let { IntentCompat.getParcelableExtra(it, NfcAdapter.EXTRA_TAG, Tag::class.java) }?.let { tag ->
                Gelesen(tag, TagLeser.befund(tag))
            }
        }
    }
}
