package cloud.dopp.homes.widget

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.drawable.IconCompat
import cloud.dopp.homes.R
import cloud.dopp.homes.kann.KannStore

/**
 * „Dokumente-Adresse kopieren“ (owner 2026-11-10): the person's own address
 * for documents, as Homes' `/napi/kann` says it to this device's person
 * (`dokumente_adresse`, `<login>+doc+@<domain>`, homes#338). The app copies
 * `<login>+doc+`; the person types the sender after the `+`, and the box
 * files the mail as theirs and maps the rest to a contact or a tag (laib
 * templates/paperless). From Mehr's row and the app icon's menu
 * ([AppShortcuts]); neither is there while Homes gives no address.
 */
object DokumenteAdresse {

    /** The shortcut's id, under the launcher menu's prefix. */
    const val ID = "kurz:dokumente-adresse"

    /** What is copied, `<login>+doc+`; null for anything but `<login>+doc+@<domain>`. */
    fun kopie(adresse: String?): String? {
        val teile = adresse?.trim()?.split('@')?.takeIf { it.size == 2 && it[1].isNotBlank() && ' ' !in adresse } ?: return null
        return teile[0].takeIf { it.endsWith(PLUS) && it.length > PLUS.length }
    }

    /** The row's hint: `mdopp+doc+…@dopp.cloud`. */
    fun muster(adresse: String): String? = kopie(adresse)?.let { it + "…@" + adresse.trim().substringAfter('@') }

    /** Puts [kopie] on the clipboard; Android 13 and later show their own note of it, older ones a toast. */
    fun kopieren(ctx: Context, adresse: String?) {
        val text = kopie(adresse) ?: return
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText(ctx.getString(R.string.dokumente_adresse_kurz), text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(ctx.applicationContext, R.string.dokumente_adresse_kopiert, Toast.LENGTH_SHORT).show()
        }
    }

    /** The app icon's entry at [rank]; null without an address. */
    fun shortcut(ctx: Context, adresse: String?, rank: Int): ShortcutInfoCompat? {
        kopie(adresse) ?: return null
        return ShortcutInfoCompat.Builder(ctx, ID)
            .setShortLabel(ctx.getString(R.string.dokumente_adresse_kurz))
            .setLongLabel(ctx.getString(R.string.dokumente_adresse))
            .setIcon(IconCompat.createWithResource(ctx, R.drawable.ic_shortcut_dokumente_adresse))
            .setRank(rank)
            .setIntent(Intent(ctx, DokumenteAdresseActivity::class.java).setAction(Intent.ACTION_VIEW))
            .build()
    }

    private const val PLUS = "+doc+"
}

/**
 * Where the app icon's „Dokumente-Adresse kopieren“ lands: a shortcut can only
 * start an activity, so this one, without a screen, copies the address Homes
 * gave last ([KannStore], so also offline) and is gone. Not exported.
 */
class DokumenteAdresseActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DokumenteAdresse.kopieren(this, KannStore.kann(this)?.dokumenteAdresse)
        finish()
    }
}
