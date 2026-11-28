package cloud.dopp.homes.ausloeser

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import cloud.dopp.homes.R
import cloud.dopp.homes.ui.padForSystemBars
import java.util.Locale

/**
 * „WLAN anbieten“ (owner 2026-11-27: „Bau alles“): while this screen is in
 * front, the phone answers another phone held to it as a tag with the WLAN
 * ([WlanAngebot], [WlanAnbietenDienst]) — „Halte das andere Telefon an
 * meins“, how long still, „Beenden“. At most two minutes from the first
 * start; then it stops by itself, forgets the credential and says so. Gone
 * from the front, nothing is answered; closed, the credential is forgotten.
 */
class WlanAnbietenActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var stand: TextView
    private lateinit var knopf: Button
    private var vorbei = false

    /** „Teilen per Berührung“: one reader, then „Geteilt.“ (owner 2026-11-28). */
    private var einmal = false

    private val tick = object : Runnable {
        override fun run() {
            if (WlanAngebot.geteilt) return ende(getString(R.string.teilen_geteilt))
            val rest = WlanAngebot.bis - SystemClock.elapsedRealtime()
            if (rest <= 0) return ende(getString(if (einmal) R.string.teilen_abgelaufen else R.string.anbieten_vorbei))
            stand.text = getString(R.string.anbieten_noch, dauer(rest))
            main.postDelayed(this, minOf(SEKUNDE, rest))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wlan_anbieten)
        padForSystemBars(findViewById(R.id.anbieten_root))
        stand = findViewById(R.id.anbieten_stand)
        knopf = findViewById(R.id.anbieten_beenden)
        knopf.setOnClickListener {
            WlanAngebot.vergessen()
            finish()
        }
        val ssid = WlanAngebot.ssid
        einmal = WlanAngebot.einmal
        if (einmal) findViewById<TextView>(R.id.anbieten_titel).setText(R.string.teilen_titel)
        findViewById<TextView>(R.id.anbieten_netz).text = ssid?.let { if (einmal) it else getString(R.string.inhalt_wlan_name, it) }.orEmpty()
        if (ssid == null) ende(getString(R.string.anbieten_nichts)) // the process was gone: the credential with it
    }

    override fun onResume() {
        super.onResume()
        if (vorbei) return
        warum(this)?.let { return ende(it) }
        if (!WlanAngebot.anfangen()) return ende(getString(if (einmal) R.string.teilen_abgelaufen else R.string.anbieten_vorbei))
        bevorzugt(true)
        main.post(tick)
    }

    override fun onPause() {
        WlanAngebot.pause() // nothing answered while the screen is not in front
        main.removeCallbacks(tick)
        bevorzugt(false)
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) WlanAngebot.vergessen()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Over: nothing answered any more, the credential forgotten, [satz] said, the button closes. */
    private fun ende(satz: String) {
        vorbei = true
        WlanAngebot.vergessen()
        main.removeCallbacks(tick)
        stand.text = satz
        knopf.text = getString(R.string.inhalt_zu)
    }

    /** While in front, this app's service answers the NDEF AID before any other's. */
    private fun bevorzugt(an: Boolean) {
        val nfc = NfcAdapter.getDefaultAdapter(this) ?: return
        runCatching {
            val ce = CardEmulation.getInstance(nfc)
            if (an) ce.setPreferredService(this, ComponentName(this, WlanAnbietenDienst::class.java)) else ce.unsetPreferredService(this)
        }
    }

    private fun dauer(ms: Long): String {
        val s = (ms + SEKUNDE - 1) / SEKUNDE
        return String.format(Locale.GERMANY, "%d:%02d", s / MINUTE, s % MINUTE)
    }

    companion object {
        private const val SEKUNDE = 1_000L
        private const val MINUTE = 60

        /** Why this phone cannot offer now, one German sentence; null when it can. Replaced in tests. */
        @VisibleForTesting
        var warum: (Context) -> String? = { ctx ->
            when {
                !ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION) -> ctx.getString(R.string.anbieten_kann_nicht)
                NfcAdapter.getDefaultAdapter(ctx)?.isEnabled != true -> ctx.getString(R.string.ausloeser_nfc_aus)
                else -> null
            }
        }

        /** Offer [n] ([name]): held in memory, the screen opened; [einmal]: to one reader only („Teilen“). */
        fun oeffnen(ctx: Context, name: String, n: NdefMessage, einmal: Boolean = false) {
            WlanAngebot.vorbereiten(name, n, einmal)
            ctx.startActivity(Intent(ctx, WlanAnbietenActivity::class.java))
        }
    }
}
