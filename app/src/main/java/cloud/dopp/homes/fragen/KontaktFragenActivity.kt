package cloud.dopp.homes.fragen

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.einrichtung.EinrichtungLeser
import cloud.dopp.homes.fragen.FragenClient.Ausgang
import cloud.dopp.homes.ui.PhotoShare
import cloud.dopp.homes.ui.padForSystemBars
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Share → „Homes: Daten erfragen“ (owner 2026-11-28): a contact shared as a vCard ([GeteilteKarte]) is looked
 * for in the household's address book (`POST /napi/kontakte/fragen`), and the link that asks its person for
 * what is missing goes out by WhatsApp, SMS or any app. One screen, opened directly (owner 2026-11-28: „direkt
 * als Screen … Ein Klick weniger“): initials and name, the state line, and every choice as a button on it —
 * one tap sends and ends the share. Nothing is stored on the phone. On while `kontakte` is (kann/Teilen.kt).
 */
class KontaktFragenActivity : AppCompatActivity() {

    private var client: FragenClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kontakt_fragen)
        padForSystemBars(findViewById(R.id.fragen_root))
        findViewById<View>(R.id.fragen_zu).setOnClickListener { finish() }
        val base = ServerStore.baseUrl(this)
        val token = TokenStore.get(this)
        if (base.isNullOrBlank() || token.isNullOrBlank()) {
            sagen(getString(R.string.share_not_paired))
            return
        }
        client = http?.let { FragenClient(base, token, it) } ?: FragenClient(base, token)
        val geteilt = intent ?: Intent()
        zeige(getString(R.string.fragen_liest), laeuft = true)
        thread {
            val karte = lesen(geteilt)?.let(GeteilteKarte::lies)
            runOnUiThread {
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
        val uri = PhotoShare.uris(i).firstOrNull() ?: return i.getStringExtra(Intent.EXTRA_TEXT)
        return runCatching { contentResolver.openInputStream(uri)?.use { text(it) } }.getOrNull()
    }

    private fun fragen(karte: GeteilteKarte.Karte, anlegen: Boolean = false, uid: String? = null) {
        val c = client ?: return
        val wer = karte.name.ifBlank { karte.telefon.firstOrNull() ?: karte.mail.first() }
        kopf(wer)
        zeige(getString(R.string.fragen_sucht, wer), laeuft = true)
        thread {
            val a = c.fragen(karte, anlegen, uid)
            runOnUiThread { if (!isFinishing) zeigen(karte, a) { fragen(karte, anlegen, uid) } }
        }
    }

    private fun zeigen(karte: GeteilteKarte.Karte, a: Ausgang, nochmal: () -> Unit) {
        when (a) {
            is Ausgang.Gefunden -> gefunden(karte, a)
            is Ausgang.NichtDa -> zeige(a.satz, listOf(getString(R.string.fragen_anlegen) to { fragen(karte, anlegen = true) }))
            is Ausgang.Mehrere -> zeige(a.satz ?: getString(R.string.fragen_welcher), a.kandidaten.map { k -> k.kontakt to { fragen(karte, uid = k.uid) } }, gefuellt = false)
            // a refusal stays one; a box that answered badly may answer next time
            is Ausgang.Abgelehnt -> sagen(a.satz ?: getString(R.string.fragen_unlesbar, a.code), nochmal.takeIf { a.code >= HTTP_500 })
            Ausgang.NichtGekoppelt -> sagen(getString(R.string.share_not_paired))
            Ausgang.Zeit -> sagen(getString(R.string.fragen_zeit), nochmal)
            Ausgang.Offline -> sagen(getString(R.string.fragen_offline), nochmal)
        }
    }

    /** What is missing, and the ways to send the link: WhatsApp only with Homes' number and WhatsApp there. */
    private fun gefunden(karte: GeteilteKarte.Karte, g: Ausgang.Gefunden) {
        kopf(g.kontakt.ifBlank { g.vorname })
        val wege = buildList {
            if (g.telefon != null && EinrichtungLeser.installiert(this@KontaktFragenActivity, Weitergeben.WHATSAPP)) {
                add(getString(R.string.fragen_whatsapp, g.vorname) to Weitergeben.whatsapp(g.telefon, g.text))
            }
            add(getString(R.string.fragen_sms) to Weitergeben.sms(g.telefon ?: karte.telefon.firstOrNull(), g.text))
            add(getString(R.string.fragen_anders) to Weitergeben.anders(g.text, getString(R.string.fragen_titel)))
        }
        val fehlt = g.fehlt.joinToString(", ") { it.replaceFirstChar(Char::uppercaseChar) }
        val satz = if (g.fehlt.isEmpty()) getString(R.string.fragen_alles_da) else getString(R.string.fragen_fehlen, g.vorname, fehlt)
        zeige(satz, wege.map { (wort, weg) -> wort to { senden(weg) } }, neu = g.neu)
    }

    private fun senden(weg: Intent) {
        try {
            startActivity(weg)
            finish()
        } catch (ignored: ActivityNotFoundException) {
            findViewById<TextView>(R.id.fragen_stand).text = getString(R.string.fragen_keine_app)
        }
    }

    /** The header: [name] big, its initials in the circle (none without a letter). */
    private fun kopf(name: String) {
        findViewById<TextView>(R.id.fragen_name).text = name
        val kreis = findViewById<TextView>(R.id.fragen_initialen)
        kreis.text = initialen(name)
        kreis.isVisible = kreis.text.isNotEmpty()
    }

    /** The state line, its progress while [laeuft], „Neu … angelegt.“ when [neu], [knoepfe] full width: the first filled when [gefuellt]. */
    private fun zeige(text: String, knoepfe: List<Pair<String, () -> Unit>> = emptyList(), laeuft: Boolean = false, neu: Boolean = false, gefuellt: Boolean = true) {
        findViewById<TextView>(R.id.fragen_stand).text = text
        findViewById<View>(R.id.fragen_laeuft).isVisible = laeuft
        findViewById<View>(R.id.fragen_neu).isVisible = neu
        val reihe = findViewById<ViewGroup>(R.id.fragen_knoepfe)
        reihe.removeAllViews()
        knoepfe.forEachIndexed { i, (wort, tippen) ->
            val b = Button(this, null, 0, if (i == 0 && gefuellt) R.style.Widget_Homes_Button else R.style.Widget_Homes_Button_Ghost)
            b.text = wort
            b.setOnClickListener { tippen() }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.topMargin = resources.getDimensionPixelSize(R.dimen.space_sm)
            reihe.addView(b, lp)
        }
    }

    /** [text] on the screen; „Noch einmal“ when [nochmal] can help, and „Schließen“ ends it. */
    private fun sagen(text: String, nochmal: (() -> Unit)? = null) = zeige(
        text, listOfNotNull(nochmal?.let { getString(R.string.fragen_nochmal) to it }, getString(R.string.fragen_schliessen) to { finish() }),
    )

    companion object {
        /** A card with a photo is a few hundred KB; more is not a contact. */
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val BLOCK = 8192
        private const val HTTP_500 = 500

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

        /** „Anna Schäfer“ → „AS“: the first and the last word's first letter, a title like „Dr.“ left out. */
        private fun initialen(name: String): String {
            val worte = name.split(' ').filter { w -> w.firstOrNull()?.isLetter() == true && !w.endsWith(".") }
            return listOfNotNull(worte.firstOrNull(), worte.drop(1).lastOrNull()).joinToString("") { it.first().uppercase() }
        }

        /** The tests' client with short timeouts; null: [FragenClient]'s own. */
        var http: OkHttpClient? = null
    }
}
