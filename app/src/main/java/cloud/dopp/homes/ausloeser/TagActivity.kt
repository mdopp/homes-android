package cloud.dopp.homes.ausloeser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import kotlin.concurrent.thread

/**
 * A touched NFC tag (homes ADR 0004 §1): Android opens this for a tag Homes
 * wrote (`cloud.dopp.homes://tag/<id>`) or HA's app wrote
 * (`https://www.home-assistant.io/tag/<id>`). It says „Meldet …“ at once,
 * posts the tag once, shows the outcome in one short line — „Bürotür –
 * gemeldet“, the unknown tag's sentence with „Benennen“ (§2: names that id on
 * the Auslöser screen, the tag is not written again), or why not — and closes. Translucent,
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
        val tag = Ausloeser.tagAus(intent?.dataString)
        val base = ServerStore.baseUrl(this)
        val token = TokenStore.get(this)
        when {
            tag == null -> ende(Ausloeser.KEIN_TAG)
            base == null || token.isNullOrBlank() -> ende(Ausloeser.NICHT_GEKOPPELT)
            else -> {
                zeile(getString(R.string.tag_meldet))
                thread(name = "tag") {
                    val a = AusloeserClient(base, token).nfc(tag)
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        val unbekannt = a is AusloeserClient.Ausgang.Gemeldet && a.name.isEmpty()
                        if (unbekannt) {
                            findViewById<Button>(R.id.tag_benennen).apply {
                                visibility = View.VISIBLE
                                setOnClickListener { benennen(tag) }
                            }
                        }
                        ende(Ausloeser.tagZeile(a), if (unbekannt) BENENNEN_MS else ZEIGEN_MS)
                    }
                }
            }
        }
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
