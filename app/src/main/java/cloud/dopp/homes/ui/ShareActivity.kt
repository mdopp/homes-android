package cloud.dopp.homes.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.ApiClient.Merken
import cloud.dopp.homes.data.ApiClient.Upload
import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.ausloeser.Ausloeser
import cloud.dopp.homes.widget.ActionDialog
import cloud.dopp.homes.widget.ActionItem
import cloud.dopp.homes.widget.ActionSheet
import cloud.dopp.homes.widget.ActionTone
import kotlin.concurrent.thread

/**
 * Share → Homes (homes#120): the phone's share sheet sends a link or a text
 * here — a recipe, an album review, a holiday flat from the browser — and
 * Homes keeps the page as a note, as "Aus Link merken" does on its Notizen
 * page and the assistant does in the chat.
 *
 * A photo — one or several, as the camera app shares them — goes to Homes'
 * document inbox instead ([PhotoShare], owner 2026-11-02: „Foto machen und
 * mit der Homes App teilen“), one upload each.
 *
 * Every share answers at once ("Merkt sich …", "Legt ab …") and ends
 * visibly: the line Homes said with a way to the note, what became of the
 * photos, or the sentence why not, or that Homes did not answer in time.
 * Nothing runs in the background after the dialog is gone.
 */
class ShareActivity : Activity() {

    private var busy: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActionDialog.scrim(this) // the browser behind stays dimmed, not read through
        if (PhotoShare.isImageShare(intent)) {
            sharePhotos(PhotoShare.uris(intent))
            return
        }
        val text = sharedText(intent?.getStringExtra(Intent.EXTRA_SUBJECT), intent?.getStringExtra(Intent.EXTRA_TEXT))
        if (intent?.action != Intent.ACTION_SEND || text == null) {
            finish()
            return
        }
        busy = waiting(getString(R.string.share_running))
        val api = ApiClient(applicationContext)
        thread {
            val result = api.merken(text)
            runOnUiThread { if (!isFinishing) answer(result) }
        }
    }

    /** Each photo to Homes, one after the other, the dialog saying which one; then what became of them. */
    private fun sharePhotos(uris: List<Uri>) {
        if (uris.isEmpty()) {
            finish()
            return
        }
        val photos = uris.take(PhotoShare.MAX_PHOTOS)
        busy = waiting(resources.getQuantityString(R.plurals.share_photos_running, photos.size, 1, photos.size))
        val api = ApiClient(applicationContext)
        val resolver = contentResolver
        val at = java.util.Date()
        thread {
            val results = photos.mapIndexed { i, uri ->
                runOnUiThread {
                    busy?.findViewById<android.widget.TextView>(R.id.ad_message)?.text =
                        resources.getQuantityString(R.plurals.share_photos_running, photos.size, i + 1, photos.size)
                }
                val photo = PhotoShare.read(resolver, uri)
                if (photo == null) {
                    Upload.Refused(getString(R.string.share_photo_unreadable))
                } else {
                    api.upload(photo.bytes, PhotoShare.name(at, i, photos.size, photo.ext), photo.mime)
                }
            }
            runOnUiThread { if (!isFinishing) photosAnswer(results, uris.size - photos.size) }
        }
    }

    private fun photosAnswer(results: List<Upload>, left: Int) {
        busy?.dismiss()
        val stored = results.count { it is Upload.Stored }
        val failed = results.firstOrNull { it !is Upload.Stored }
        val why = when (failed) {
            is Upload.Refused -> failed.sentence
            Upload.NotPaired -> getString(R.string.share_not_paired)
            Upload.Unreachable -> getString(R.string.share_photos_unreachable)
            else -> null
        }
        val (title, text) = when {
            failed == null -> R.string.share_photos_kept to resources.getQuantityString(R.plurals.share_photos_kept_text, stored, stored)
            stored == 0 -> R.string.share_photos_not_kept to why.orEmpty()
            else -> R.string.share_photos_partly to getString(R.string.share_photos_partly_text, stored, results.size, why)
        }
        val more = if (left > 0) " " + getString(R.string.share_photos_too_many, PhotoShare.MAX_PHOTOS, left) else ""
        said(getString(title), text + more)
    }

    /** The one dialog of the app ([ActionDialog]) while Homes works: what happens, no buttons. */
    private fun waiting(message: String): AlertDialog =
        ActionDialog.show(
            activity = this, title = getString(R.string.share_title), message = message,
            sheet = ActionSheet(emptyList()), onPick = {}, onCancel = {}, footer = null, cancelable = false,
        )

    /** What became of the share, in the same dialog: [open] adds „Notiz öffnen“; „Fertig“ closes. */
    private fun said(title: String, message: String, open: String? = null, knopf: Int = R.string.share_open) {
        val sheet = ActionSheet(listOfNotNull(open?.let { ActionItem(knopf, ActionTone.PRIMARY) }))
        ActionDialog.show(
            activity = this, title = title, message = message, sheet = sheet,
            onPick = {
                open?.let { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
                finish()
            },
            onCancel = { finish() },
            footer = getString(R.string.share_done),
        )
    }

    private fun answer(result: Merken) {
        busy?.dismiss()
        when (result) {
            is Merken.Kept -> gemerkt(this, result, ServerStore.baseUrl(this)).let { said(it.titel, it.text, it.link, it.knopf) }
            is Merken.Refused -> said(getString(R.string.share_not_kept), result.sentence)
            Merken.NotPaired -> said(getString(R.string.share_not_kept), getString(R.string.share_not_paired))
            Merken.Unreachable -> said(getString(R.string.share_not_kept), getString(R.string.share_unreachable))
        }
    }

    /** How a kept share is said: the dialog's title and line, the note to open (on the paired Homes only), its button. */
    data class Gemerkt(val titel: String, val text: String, val link: String?, val knopf: Int)

    companion object {
        /** A recipe „‚Kürbissuppe‘ als Rezept gemerkt“ with „Ansehen“; anything else as before, „Notiz öffnen“. */
        fun gemerkt(ctx: Context, k: Merken.Kept, base: String?): Gemerkt {
            val link = Ausloeser.aufHomes(k.note, base)
            return if (k.art == Napi.ART_REZEPT && k.titel.isNotEmpty()) {
                Gemerkt(ctx.getString(R.string.share_rezept, k.titel), k.said, link, R.string.share_ansehen)
            } else {
                Gemerkt(ctx.getString(if (k.already) R.string.share_already else R.string.share_kept), k.said, link, R.string.share_open)
            }
        }

        /**
         * What was shared, as one text: a browser shares the page's title as
         * the subject and the link as the text; either alone is fine. Null
         * when nothing came.
         */
        fun sharedText(subject: String?, text: String?): String? =
            listOfNotNull(subject?.trim(), text?.trim())
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(" ")
                .ifBlank { null }
    }
}
