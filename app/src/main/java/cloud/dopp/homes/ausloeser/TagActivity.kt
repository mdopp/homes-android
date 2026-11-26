package cloud.dopp.homes.ausloeser

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.annotation.VisibleForTesting
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.einrichtung.Abschnitte
import kotlin.concurrent.thread

/**
 * A touched NFC tag (homes ADR 0004 §1): Android opens this for a tag Homes
 * wrote (`cloud.dopp.homes://tag/<id>`) or HA's app wrote
 * (`https://www.home-assistant.io/tag/<id>`). It says „Meldet …“ at once,
 * posts the tag once, shows the outcome in one short line — „Bürotür –
 * gemeldet“, the unknown tag's sentence with „Benennen“ (§2: names that id on
 * the Auslöser screen, the tag is not written again), or why not — and closes. A tag
 * asking for whom to scan (§8, `scanner-fragen`) opens Homes' Scannen with its line.
 * The tag's own address `https://<homes>/tag/<id>` (§5, an App Link) comes two
 * ways: touched (NDEF_DISCOVERED), reported at once as every tag; opened as a link
 * (VIEW, from a chat or a page), reported only after „Auslösen“ — a link never
 * acts by itself, as Homes' page acts only on its button. Translucent,
 * not in the recents, its own empty task: nothing is left behind.
 */
class TagActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val schliessen = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tag)
        findViewById<TextView>(R.id.tag_zeile).setOnClickListener { finish() }
        melden(intent)
    }

    /** „Benennen“: the Auslöser screen names [tag], in the app's own task. */
    private fun benennen(tag: String) {
        runCatching {
            startActivity(
                Intent(this, AusloeserActivity::class.java).putExtra(AusloeserActivity.EXTRA_TAG, tag)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }

    /** A second tag while the line still shows: this one is reported too. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        melden(intent)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun melden(intent: Intent?) {
        main.removeCallbacks(schliessen)
        findViewById<Button>(R.id.tag_benennen).visibility = View.GONE
        findViewById<Button>(R.id.tag_ausloesen).visibility = View.GONE
        val link = intent?.action != NfcAdapter.ACTION_NDEF_DISCOVERED
        val tag = Ausloeser.tagAus(intent?.dataString, ServerStore.host(this))
        val base = ServerStore.baseUrl(this)
        val token = TokenStore.get(this)
        when {
            tag == null -> ende(Ausloeser.KEIN_TAG)
            base == null || token.isNullOrBlank() -> ende(Ausloeser.NICHT_GEKOPPELT)
            link -> fragen(tag, base, token)
            else -> los(tag, base, token)
        }
    }

    /** A tag's link: it acts on „Auslösen“ alone. */
    private fun fragen(tag: String, base: String, token: String) {
        zeile(getString(R.string.tag_link_frage))
        findViewById<Button>(R.id.tag_ausloesen).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                visibility = View.GONE
                los(tag, base, token)
            }
        }
        main.postDelayed(schliessen, BENENNEN_MS)
    }

    /** [url] on Homes in a Custom Tab, [text] said over it; this line goes. */
    private fun weiter(url: String, text: String) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
        Abschnitte.oeffnen(this, url.toUri())
        finish()
    }

    /** The tag reported once; its line, „Benennen“ for an unknown one, or Homes' page for a scanner's question. */
    private fun los(tag: String, base: String, token: String) {
        main.removeCallbacks(schliessen)
        zeile(getString(R.string.tag_meldet))
        thread(name = "tag") {
            val a = AusloeserClient(base, token).nfc(tag)
            runOnUiThread { if (!isFinishing) gemeldet(a, tag, base) }
        }
    }

    private fun gemeldet(a: AusloeserClient.Ausgang, tag: String, base: String) {
        // §8 scanner-fragen: Homes' Scannen, für mich / für den Haushalt, with its line
        val oeffnen = (a as? AusloeserClient.Ausgang.Gemeldet)?.oeffnen?.let { Ausloeser.aufHomes(it, base) }
        if (oeffnen != null) return weiter(oeffnen, Ausloeser.tagZeile(a))
        val unbekannt = a is AusloeserClient.Ausgang.Gemeldet && a.name.isEmpty() && a.text == null
        if (unbekannt) {
            findViewById<Button>(R.id.tag_benennen).apply {
                visibility = View.VISIBLE
                setOnClickListener { benennen(tag) }
            }
        }
        ende(Ausloeser.tagZeile(a), if (unbekannt) BENENNEN_MS else ZEIGEN_MS)
    }

    private fun zeile(text: String) {
        findViewById<TextView>(R.id.tag_zeile).text = text
    }

    /** The outcome, read for a moment ([ms]), then gone. */
    private fun ende(text: String, ms: Long = ZEIGEN_MS) {
        zeile(text)
        main.removeCallbacks(schliessen)
        main.postDelayed(schliessen, ms)
    }

    companion object {
        /** Long enough to read one line. */
        @VisibleForTesting
        const val ZEIGEN_MS = 2_500L

        /** With „Benennen“: long enough to decide. */
        @VisibleForTesting
        const val BENENNEN_MS = 8_000L
    }
}
