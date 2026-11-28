package cloud.dopp.homes.teilen

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.nfc.NdefMessage
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import cloud.dopp.homes.R
import cloud.dopp.homes.ausloeser.WlanAnbietenActivity
import cloud.dopp.homes.ausloeser.WlanAngebot
import java.util.Locale

/**
 * One answer for the next reader, armed from a screen of „Teilen“ (owner 2026-11-28): two minutes at most, one
 * read, answered only while that screen is in front ([vorn], [weg]); how long still, then „Geteilt.“ or
 * „Abgelaufen.“, said through [sagen] on the screen itself.
 */
internal class Anbieten(private val a: Activity, private val sagen: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())

    /** Armed by this screen now. */
    var an = false
        private set

    private var fertig: (Boolean) -> Unit = {}

    private val tick = object : Runnable {
        override fun run() {
            if (!an) return
            if (WlanAngebot.geteilt) return ende(true)
            val rest = WlanAngebot.bis - SystemClock.elapsedRealtime()
            if (rest <= 0 || WlanAngebot.ssid == null) return ende(false)
            sagen(a.getString(R.string.teilen_halte, dauer(rest)))
            main.postDelayed(this, minOf(SEKUNDE, rest))
        }
    }

    /** [n] for the next reader, as [name]; false (and why, said) when this phone cannot. [danach]: shared or not. */
    fun starten(name: String, n: NdefMessage, danach: (Boolean) -> Unit = {}): Boolean {
        WlanAnbietenActivity.warum(a)?.let {
            sagen(it)
            return false
        }
        an = true
        fertig = danach
        WlanAngebot.vorbereiten(name, n, nurEinmal = true)
        WlanAngebot.anfangen()
        weiter()
        return true
    }

    /** An answer armed elsewhere (a touch, [Teilen]) is this screen's from now: its time and end said here. */
    fun uebernehmen(danach: (Boolean) -> Unit = {}) {
        if (WlanAngebot.ssid == null || !WlanAngebot.einmal) return
        an = true
        fertig = danach
        weiter()
    }

    /** The screen is in front again. */
    fun vorn() {
        if (!an) return
        if (WlanAngebot.geteilt || WlanAngebot.anfangen()) weiter() else ende(false)
    }

    /** The screen went: nothing is answered until it is in front again. */
    fun weg() {
        if (!an) return
        WlanAngebot.pause()
        main.removeCallbacks(tick)
        WlanAnbietenActivity.bevorzugt(a, false)
    }

    /** The screen closes: a [finishing] one forgets what it armed. */
    fun zu(finishing: Boolean) {
        if (finishing && an) WlanAngebot.vergessen()
        main.removeCallbacksAndMessages(null)
    }

    /** Another answer is armed instead: this one is over, quietly. */
    fun abbrechen() {
        if (!an) return
        an = false
        WlanAngebot.vergessen()
        main.removeCallbacks(tick)
        WlanAnbietenActivity.bevorzugt(a, false)
    }

    private fun weiter() {
        WlanAnbietenActivity.bevorzugt(a, true)
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun ende(geteilt: Boolean) {
        abbrechen()
        sagen(a.getString(if (geteilt) R.string.teilen_geteilt else R.string.teilen_abgelaufen))
        fertig(geteilt)
    }

    private fun dauer(ms: Long): String {
        val s = (ms + SEKUNDE - 1) / SEKUNDE
        return String.format(Locale.GERMANY, "%d:%02d", s / MINUTE, s % MINUTE)
    }

    companion object {
        private const val SEKUNDE = 1_000L
        private const val MINUTE = 60

        /** [text] as a QR code over the whole screen, at full brightness, until „Schließen“; [zu] once closed. */
        fun qr(a: Activity, titel: String, text: String, zu: () -> Unit = {}) {
            val d = Dialog(a, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
            d.setContentView(R.layout.dialog_qr)
            val m = a.resources.displayMetrics
            d.findViewById<ImageView>(R.id.qr_bild).setImageBitmap(Visitenkarte.qrBild(text, minOf(m.widthPixels, m.heightPixels) * 9 / 10))
            d.findViewById<TextView>(R.id.qr_titel).text = titel
            d.findViewById<Button>(R.id.qr_zu).setOnClickListener { d.dismiss() }
            d.setOnDismissListener { zu() }
            d.window?.let { w ->
                w.setBackgroundDrawable(Color.WHITE.toDrawable())
                w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
            }
            d.show()
        }
    }
}
