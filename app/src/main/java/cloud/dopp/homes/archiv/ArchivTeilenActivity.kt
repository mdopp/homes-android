package cloud.dopp.homes.archiv

import android.app.Activity
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.SicherungClient
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.hochladen.Uebergabe
import cloud.dopp.homes.ui.PhotoShare
import cloud.dopp.homes.widget.ActionDialog
import cloud.dopp.homes.widget.ActionSheet
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Share → „Ins Archiv“ (owner, 2026-11-11): the Facebook or LinkedIn export
 * the person downloaded goes, encrypted on the phone, into their archive
 * ([InsArchiv]); one file or several (Facebook splits a big export). The
 * shared file is copied into the app's cache while the share is open — it
 * is readable only then — and deleted after. The one dialog says it runs,
 * then what came of it. The file is small (only the friends list or
 * Connections.csv leaves the phone), so it goes here, not in a job.
 */
class ArchivTeilenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActionDialog.scrim(this)
        val uris = intent?.let(PhotoShare::uris).orEmpty()
        val base = ServerStore.baseUrl(this)
        val token = TokenStore.get(this)
        when {
            uris.isEmpty() -> finish()
            base.isNullOrBlank() || token.isNullOrBlank() -> sagen(getString(R.string.share_not_paired))
            else -> senden(uris, SicherungClient(base, token))
        }
    }

    private fun senden(uris: List<Uri>, client: SicherungClient) {
        val dialog = ActionDialog.show(
            activity = this, title = getString(R.string.archiv_titel), message = getString(R.string.archiv_laeuft),
            sheet = ActionSheet(emptyList()), onPick = {}, onCancel = {}, footer = null, cancelable = false,
        )
        val resolver = contentResolver
        val ordner = File(cacheDir, "archiv")
        thread {
            val ausgaenge = uris.map { uri ->
                val kopie = kopie(resolver, uri, ordner) ?: return@map InsArchiv.Ausgang.Fremd
                try {
                    InsArchiv(client, ordner).senden(kopie)
                } finally {
                    kopie.delete()
                }
            }
            runOnUiThread {
                dialog.dismiss()
                if (!isFinishing) sagen(satz(ausgaenge))
            }
        }
    }

    /** What came of the files, in one sentence: what went, else the first reason why not. */
    private fun satz(ausgaenge: List<InsArchiv.Ausgang>): String {
        val arten = ausgaenge.filterIsInstance<InsArchiv.Ausgang.Fertig>().map { it.art }.distinct()
        if (arten.isNotEmpty()) {
            val was = arten.map { getString(if (it == Export.Art.FACEBOOK) R.string.archiv_facebook else R.string.archiv_linkedin) }
            return getString(R.string.archiv_fertig, was.joinToString(getString(R.string.archiv_und)))
        }
        return when (val a = ausgaenge.firstOrNull { it != InsArchiv.Ausgang.Fremd } ?: InsArchiv.Ausgang.Fremd) {
            is InsArchiv.Ausgang.Nein -> a.satz ?: getString(R.string.archiv_nein)
            InsArchiv.Ausgang.FacebookHtml -> getString(R.string.archiv_html)
            InsArchiv.Ausgang.NichtGekoppelt -> getString(R.string.share_not_paired)
            InsArchiv.Ausgang.Unerreichbar -> getString(R.string.archiv_unerreichbar)
            else -> getString(R.string.archiv_fremd)
        }
    }

    private fun sagen(text: String) = Uebergabe.sagen(this, getString(R.string.archiv_titel), text)

    /** [uri] copied into [ordner], streamed; null when it could not be read. */
    private fun kopie(resolver: ContentResolver, uri: Uri, ordner: File): File? {
        val datei = File(ordner.apply { mkdirs() }, UUID.randomUUID().toString())
        val ok = runCatching { resolver.openInputStream(uri)?.use { ein -> datei.outputStream().use { ein.copyTo(it) } } != null }.getOrDefault(false)
        if (ok && datei.length() > 0L) return datei
        datei.delete()
        return null
    }
}
