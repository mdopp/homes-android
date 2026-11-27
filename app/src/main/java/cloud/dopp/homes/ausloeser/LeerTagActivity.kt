package cloud.dopp.homes.ausloeser

import android.app.Activity
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.content.IntentCompat
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
 * is not ours: nothing shows. No UI of its own: it decides and goes.
 */
class LeerTagActivity : Activity() {

    /** What a touched tag is, as this activity needs it: the tag itself, whether it can be formatted or is Ndef, and its message. */
    @VisibleForTesting
    class Gelesen(val tag: Parcelable, val formatierbar: Boolean, val ndef: Boolean, val nachricht: NdefMessage?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val g = lesen(intent)
        when {
            g == null || !TagSchreiber.leer(g.formatierbar, g.ndef, g.nachricht) -> Log.i(LOG, "tech: not blank") // not ours
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

    companion object {
        private const val LOG = "HomesTag"

        /** The touched tag from Android's intent; replaced in tests (a Tag cannot be made outside Android's NFC). */
        @VisibleForTesting
        @Volatile
        var lesen: (Intent?) -> Gelesen? = { i ->
            i?.let { IntentCompat.getParcelableExtra(it, NfcAdapter.EXTRA_TAG, Tag::class.java) }?.let { tag ->
                val ndef = Ndef.get(tag)
                Gelesen(tag, NdefFormatable.get(tag) != null, ndef != null, ndef?.cachedNdefMessage)
            }
        }
    }
}
