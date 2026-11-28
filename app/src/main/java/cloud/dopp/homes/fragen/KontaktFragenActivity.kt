package cloud.dopp.homes.fragen

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.einrichtung.EinrichtungLeser
import cloud.dopp.homes.fragen.FragenClient.Ausgang
import cloud.dopp.homes.ui.PhotoShare
import cloud.dopp.homes.widget.ActionDialog
import cloud.dopp.homes.widget.ActionItem
import cloud.dopp.homes.widget.ActionSheet
import cloud.dopp.homes.widget.ActionTone
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Share → „Homes: Daten erfragen“ (owner 2026-11-28): a contact sent from the
 * phone's contacts app as a vCard ([GeteilteKarte]) is looked for in the
 * household's address book (`POST /napi/kontakte/fragen`), and the link that
 * asks its person for what is missing goes out by WhatsApp, SMS or any app.
 * The one dialog says each step: reading the card, „Sucht … “, then what is
 * missing with the ways to send — or not in the address book („aufnehmen und
 * fragen“), more than one fits (pick one), or why not. Every way ends the
 * share; nothing is stored on the phone. Switched by the box's `teilen`
 * `kontakt-fragen` (kann/Teilen.kt).
 */
class KontaktFragenActivity : Activity() {

    private var client: FragenClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActionDialog.scrim(this)
        val base = ServerStore.baseUrl(this)
        val token = TokenStore.get(this)
        if (base.isNullOrBlank() || token.isNullOrBlank()) {
            sagen(getString(R.string.share_not_paired))
            return
        }
        client = http?.let { FragenClient(base, token, it) } ?: FragenClient(base, token)
        val geteilt = intent ?: Intent()
        val dialog = laeuft(getString(R.string.fragen_liest))
        thread {
            val karte = lesen(geteilt)?.let(GeteilteKarte::lies)
            runOnUiThread {
                dialog.dismiss()
                when {
                    isFinishing -> Unit
                    karte == null -> sagen(getString(R.string.fragen_keine_karte))
                    karte.leer -> sagen(getString(R.string.fragen_leer))
                    else -> fragen(karte)
                }
            }
        }
    }

    /** The shared text: the file (a VCF) when one came, else the text itself; at most [MAX_BYTES]. */
    private fun lesen(i: Intent): String? {
        val uri = PhotoShare.uris(i).firstOrNull()
        if (uri != null) {
            return runCatching {
                contentResolver.openInputStream(uri)?.use { text(it) }
            }.getOrNull()
        }
        return i.getStringExtra(Intent.EXTRA_TEXT)
    }

    private fun fragen(karte: GeteilteKarte.Karte, anlegen: Boolean = false, uid: String? = null) {
        val c = client ?: return
        val wer = karte.name.ifBlank { karte.telefon.firstOrNull() ?: karte.mail.first() }
        val dialog = laeuft(getString(R.string.fragen_sucht, wer))
        thread {
            val a = c.fragen(karte, anlegen, uid)
            runOnUiThread {
                dialog.dismiss()
                if (!isFinishing) zeigen(karte, a)
            }
        }
    }

    private fun zeigen(karte: GeteilteKarte.Karte, a: Ausgang) {
        when (a) {
            is Ausgang.Gefunden -> gefunden(karte, a)
            is Ausgang.NichtDa -> waehlen(a.satz, listOf(getString(R.string.fragen_anlegen))) { fragen(karte, anlegen = true) }
            is Ausgang.Mehrere -> waehlen(a.satz ?: getString(R.string.fragen_welcher), a.kandidaten.map { it.kontakt }) { i ->
                fragen(karte, uid = a.kandidaten[i].uid)
            }
            is Ausgang.Abgelehnt -> sagen(a.satz ?: getString(R.string.fragen_unlesbar, a.code))
            Ausgang.NichtGekoppelt -> sagen(getString(R.string.share_not_paired))
            Ausgang.Zeit -> sagen(getString(R.string.fragen_zeit))
            Ausgang.Offline -> sagen(getString(R.string.fragen_offline))
        }
    }

    /** What is missing, and the ways to send the link: WhatsApp only with Homes' number and WhatsApp there. */
    private fun gefunden(karte: GeteilteKarte.Karte, g: Ausgang.Gefunden) {
        val wege = buildList {
            if (g.telefon != null && EinrichtungLeser.installiert(this@KontaktFragenActivity, Weitergeben.WHATSAPP)) {
                add(getString(R.string.fragen_whatsapp, g.vorname) to Weitergeben.whatsapp(g.telefon, g.text))
            }
            add(getString(R.string.fragen_sms) to Weitergeben.sms(g.telefon ?: karte.telefon.firstOrNull(), g.text))
            add(getString(R.string.fragen_anders) to Weitergeben.anders(g.text, getString(R.string.fragen_titel)))
        }
        waehlen(satz(g), wege.map { it.first }) { i ->
            try {
                startActivity(wege[i].second)
                finish()
            } catch (ignored: ActivityNotFoundException) {
                sagen(getString(R.string.fragen_keine_app))
            }
        }
    }

    /** „Bei Anna fehlen: Adresse, Geburtstag“, or „Alles da – trotzdem fragen?“; a contact made just now said first. */
    private fun satz(g: Ausgang.Gefunden): String {
        val fehlt = if (g.fehlt.isEmpty()) {
            getString(R.string.fragen_alles_da)
        } else {
            getString(R.string.fragen_fehlen, g.vorname, g.fehlt.joinToString(", ") { it.replaceFirstChar(Char::uppercaseChar) })
        }
        return if (g.neu) getString(R.string.fragen_neu, g.kontakt.ifBlank { g.vorname }) + " " + fehlt else fehlt
    }

    /** The one dialog while something runs: its step, no button, not to be dismissed. */
    private fun laeuft(text: String): AlertDialog = ActionDialog.show(
        activity = this, title = getString(R.string.fragen_titel), message = text,
        sheet = ActionSheet(emptyList()), onPick = {}, onCancel = {}, footer = null, cancelable = false,
    )

    /** The one dialog with [text] and [wahl] to pick from, the first filled; Abbrechen ends the share. */
    private fun waehlen(text: String, wahl: List<String>, gewaehlt: (Int) -> Unit) {
        val items = wahl.mapIndexed { i, w -> ActionItem(R.string.fragen_titel, if (i == 0) ActionTone.PRIMARY else ActionTone.NORMAL, label = w) }
        ActionDialog.show(
            activity = this, title = getString(R.string.fragen_titel), message = text,
            sheet = ActionSheet(items), onPick = gewaehlt, onCancel = { finish() },
        )
    }

    /** The one dialog with [text]; „Fertig“ ends the share. */
    private fun sagen(text: String) {
        ActionDialog.show(
            activity = this, title = getString(R.string.fragen_titel), message = text, sheet = ActionSheet(emptyList()),
            onPick = { finish() }, onCancel = { finish() }, footer = getString(R.string.share_done),
        )
    }

    companion object {
        /** A card with a photo is a few hundred KB; more is not a contact. */
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val BLOCK = 8192

        /** [ein] as UTF-8, its first [MAX_BYTES] at most. */
        private fun text(ein: InputStream): String {
            val out = ByteArrayOutputStream()
            val puffer = ByteArray(BLOCK)
            while (out.size() < MAX_BYTES) {
                val r = ein.read(puffer)
                if (r < 0) break
                out.write(puffer, 0, r)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        /** The tests' client with short timeouts; null: [FragenClient]'s own. */
        var http: OkHttpClient? = null
    }
}
