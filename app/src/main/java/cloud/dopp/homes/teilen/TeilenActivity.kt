package cloud.dopp.homes.teilen

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import cloud.dopp.homes.R
import cloud.dopp.homes.ausloeser.TagInhalt
import cloud.dopp.homes.ausloeser.WlanAnbietenActivity
import cloud.dopp.homes.ausloeser.WlanAngebot
import cloud.dopp.homes.ausloeser.WlanTag
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.kann.KannStore
import cloud.dopp.homes.ui.padForSystemBars
import okhttp3.OkHttpClient
import java.util.Locale
import kotlin.concurrent.thread

/**
 * „Teilen“ (owner 2026-11-28: „einfach nur eine Zeile pro Thema und dann
 * rechts einen Button für NFC und einen für das Anzeigen eines QR-Codes“):
 * opened from „Dieses Telefon“, the start screen's tile or the notification a
 * reading phone caused ([Teilen]). One row each: Meine Kontaktdaten,
 * Gast-WLAN, Kontaktdaten eintragen lassen (while the box has its contacts),
 * each kept tag by its name. [NFC] answers one reader within two minutes, in
 * the row („Halte das andere Telefon an deins“, how long still, „Geteilt.“ /
 * „Abgelaufen.“) and only while this screen is in front; [QR] shows the
 * code full-screen and bright. A row still to be set up says „Festlegen“.
 */
class TeilenActivity : AppCompatActivity() {

    /** What a row shares: the record for a reader and its QR code's text (null: none). */
    private class Inhalt(val nachricht: NdefMessage, val qr: String?)

    /**
     * One row: [festlegen] set, it only says „Festlegen“; [keinQr] set, its QR button is off and this says why;
     * [iphone]: „iPhone: QR-Code“ under it; [holen] gets what it shares, then goes on with it.
     */
    private class Thema(
        val titel: String,
        @DrawableRes val bild: Int,
        val unter: String?,
        val iphone: Boolean = false,
        val festlegen: (() -> Unit)? = null,
        val keinQr: String? = null,
        val holen: (View, (Inhalt) -> Unit) -> Unit = { _, _ -> },
    )

    private val main = Handler(Looper.getMainLooper())

    /** The row whose answer is armed now; one at a time. */
    private var aktiv: View? = null

    private val gastFestgelegt = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != RESULT_OK) return@registerForActivityResult
        render()
        val n = Behalten.gastWlan(this) ?: return@registerForActivityResult
        liste().getChildAt(GAST_REIHE)?.let { r -> gast(n).holen(r) { anbieten(r, it) } } // set: armed at once
    }

    private val tick = object : Runnable {
        override fun run() {
            val r = aktiv ?: return
            if (WlanAngebot.geteilt) return ende(r, getString(R.string.teilen_geteilt))
            val rest = WlanAngebot.bis - SystemClock.elapsedRealtime()
            if (rest <= 0 || WlanAngebot.ssid == null) return ende(r, getString(R.string.teilen_abgelaufen))
            stand(r, getString(R.string.teilen_halte, dauer(rest)))
            main.postDelayed(this, minOf(SEKUNDE, rest))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teilen)
        padForSystemBars(findViewById(R.id.teilen_root))
        Teilen.weg(this)
    }

    override fun onResume() {
        super.onResume()
        if (aktiv == null) return render()
        if (WlanAngebot.geteilt || WlanAngebot.anfangen()) {
            WlanAnbietenActivity.bevorzugt(this, true)
            main.post(tick)
        } else {
            aktiv?.let { ende(it, getString(R.string.teilen_abgelaufen)) }
        }
    }

    override fun onPause() {
        WlanAngebot.pause() // nothing answered while the screen is not in front
        main.removeCallbacks(tick)
        WlanAnbietenActivity.bevorzugt(this, false)
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing && aktiv != null) WlanAngebot.vergessen()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun liste() = findViewById<ViewGroup>(R.id.teilen_liste)

    private fun render() {
        val g = liste()
        g.removeAllViews()
        themen().forEach { g.addView(reihe(g, it)) }
    }

    private fun themen(): List<Thema> = buildList {
        val karte = Behalten.visitenkarte(this@TeilenActivity)
        add(
            if (karte == null) {
                Thema(getString(R.string.teilen_kontakt), R.drawable.ic_modul_kontakte, getString(R.string.teilen_karte_fehlt), festlegen = {
                    startActivity(Intent(this@TeilenActivity, VisitenkarteActivity::class.java))
                })
            } else {
                Thema(getString(R.string.teilen_kontakt), R.drawable.ic_modul_kontakte, null, iphone = true) { _, weiter ->
                    weiter(Inhalt(Visitenkarte.ndef(karte), karte))
                }
            },
        )
        add(Behalten.gastWlan(this@TeilenActivity)?.let { gast(it) } ?: Thema(getString(R.string.gast_titel), R.drawable.ic_ausloeser, getString(R.string.gast_fehlt), festlegen = {
            gastFestgelegt.launch(Intent(this@TeilenActivity, GastWlanActivity::class.java).putExtra(GastWlanActivity.AUS_TEILEN, true))
        }))
        if (einladenAngeboten()) {
            add(Thema(getString(R.string.einladen_titel), R.drawable.ic_konto, getString(R.string.einladen_unter)) { r, weiter -> einladen(r, weiter) })
        }
        Behalten.alle(this@TeilenActivity).forEach { t ->
            val inhalt = TagInhalt.lesen(t.nachricht, null)
            val qr = Visitenkarte.qrText(t.nachricht)
            val iphone = inhalt is TagInhalt.Wlan || t.nachricht.records.firstOrNull()?.toMimeType() == Visitenkarte.MIME
            add(Thema(t.name, R.drawable.ic_ausloeser, art(inhalt), iphone, keinQr = getString(R.string.teilen_kein_qr).takeIf { qr == null }) { _, weiter ->
                weiter(Inhalt(t.nachricht, qr))
            })
        }
    }

    private fun gast(n: WlanTag.Netz) = Thema(getString(R.string.gast_titel), R.drawable.ic_ausloeser, getString(R.string.teilen_art_wlan, n.ssid), iphone = true) { _, weiter ->
        weiter(Inhalt(WlanTag.nachricht(n.ssid, n.passwort, n.schutz), Visitenkarte.wifi(n)))
    }

    /** Paired, and the box's `kontakte` module on (or not known yet); whether the person may, Homes says on the tap. */
    private fun einladenAngeboten(): Boolean {
        if (ServerStore.baseUrl(this).isNullOrBlank() || TokenStore.get(this).isNullOrBlank()) return false
        val kann = KannStore.kann(this) ?: return true
        return kann.module.any { it.name == "kontakte" && it.an }
    }

    private fun art(i: TagInhalt): String = when (i) {
        is TagInhalt.Wlan -> getString(R.string.teilen_art_wlan, i.netz.ssid)
        is TagInhalt.Homes -> getString(R.string.teilen_art_homes)
        is TagInhalt.Fremd -> i.zeilen.first()
    }

    /** A one-time link from Homes, fetched on the tap: „Homes macht einen Link …“ in the row, then [weiter] or why not. */
    private fun einladen(r: View, weiter: (Inhalt) -> Unit) {
        val base = ServerStore.baseUrl(this)
        val token = TokenStore.get(this)
        if (base.isNullOrBlank() || token.isNullOrBlank()) return stand(r, getString(R.string.share_not_paired))
        knoepfe(r, false)
        stand(r, getString(R.string.einladen_laeuft))
        val c = http?.let { EinladenClient(base, token, it) } ?: EinladenClient(base, token)
        thread(name = "einladen") {
            val a = c.einladen()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                knoepfe(r, true)
                when (a) {
                    is EinladenClient.Ausgang.Link -> {
                        stand(r, "")
                        weiter(Inhalt(NdefMessage(arrayOf(NdefRecord.createUri(a.link))), a.link))
                    }
                    is EinladenClient.Ausgang.Abgelehnt -> stand(r, a.satz ?: getString(R.string.einladen_unlesbar, a.code))
                    EinladenClient.Ausgang.NichtGekoppelt -> stand(r, getString(R.string.share_not_paired))
                    EinladenClient.Ausgang.Zeit -> stand(r, getString(R.string.einladen_zeit))
                    EinladenClient.Ausgang.Offline -> stand(r, getString(R.string.einladen_offline))
                }
            }
        }
    }

    /** [NFC]: [inhalt] answers the next reader, once, within two minutes, while this screen is in front. */
    private fun anbieten(r: View, inhalt: Inhalt) {
        WlanAnbietenActivity.warum(this)?.let { return stand(r, it) }
        aktiv?.takeIf { it !== r }?.let { stand(it, "") }
        aktiv = r
        WlanAngebot.vorbereiten(r.findViewById<TextView>(R.id.teile_titel).text.toString(), inhalt.nachricht, nurEinmal = true)
        WlanAngebot.anfangen()
        WlanAnbietenActivity.bevorzugt(this, true)
        main.removeCallbacks(tick)
        main.post(tick)
    }

    /** The armed answer over: nothing answered any more, [satz] in its row. */
    private fun ende(r: View, satz: String) {
        aktiv = null
        WlanAngebot.vergessen()
        main.removeCallbacks(tick)
        WlanAnbietenActivity.bevorzugt(this, false)
        stand(r, satz)
    }

    /** [QR]: [text] as a QR code over the whole screen, at full brightness, until „Schließen“. */
    private fun zeigeQr(titel: String, text: String) {
        val d = Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
        d.setContentView(R.layout.dialog_qr)
        val m = resources.displayMetrics
        d.findViewById<ImageView>(R.id.qr_bild).setImageBitmap(Visitenkarte.qrBild(text, minOf(m.widthPixels, m.heightPixels) * 9 / 10))
        d.findViewById<TextView>(R.id.qr_titel).text = titel
        d.findViewById<Button>(R.id.qr_zu).setOnClickListener { d.dismiss() }
        d.window?.let { w ->
            w.setBackgroundDrawable(Color.WHITE.toDrawable())
            w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        }
        d.show()
    }

    private fun reihe(g: ViewGroup, t: Thema): View = layoutInflater.inflate(R.layout.item_teilen, g, false).also { r ->
        r.findViewById<ImageView>(R.id.teile_bild).setImageResource(t.bild)
        r.findViewById<TextView>(R.id.teile_titel).text = t.titel
        val unter = listOfNotNull(t.unter, getString(R.string.teilen_iphone).takeIf { t.iphone && t.festlegen == null }, t.keinQr)
        r.findViewById<TextView>(R.id.teile_unter).apply {
            text = unter.joinToString(" · ")
            isVisible = unter.isNotEmpty()
        }
        val nfc = r.findViewById<Button>(R.id.teile_nfc)
        val qr = r.findViewById<Button>(R.id.teile_qr)
        val festlegen = r.findViewById<Button>(R.id.teile_festlegen)
        nfc.isVisible = t.festlegen == null
        qr.isVisible = t.festlegen == null
        festlegen.isVisible = t.festlegen != null
        festlegen.setOnClickListener { t.festlegen?.invoke() }
        nfc.setOnClickListener { t.holen(r) { anbieten(r, it) } }
        qr.isEnabled = t.keinQr == null
        qr.setOnClickListener { t.holen(r) { i -> i.qr?.let { zeigeQr(t.titel, it) } } }
    }

    private fun knoepfe(r: View, an: Boolean) {
        r.findViewById<Button>(R.id.teile_nfc).isEnabled = an
        r.findViewById<Button>(R.id.teile_qr).isEnabled = an
    }

    private fun stand(r: View, satz: String) {
        r.findViewById<TextView>(R.id.teile_stand).apply {
            text = satz
            isVisible = satz.isNotEmpty()
        }
    }

    private fun dauer(ms: Long): String {
        val s = (ms + SEKUNDE - 1) / SEKUNDE
        return String.format(Locale.GERMANY, "%d:%02d", s / MINUTE, s % MINUTE)
    }

    companion object {
        private const val SEKUNDE = 1_000L
        private const val MINUTE = 60

        /** Where „Gast-WLAN“ is: after „Meine Kontaktdaten“. */
        private const val GAST_REIHE = 1

        /** The client's line to Homes; replaced in tests. */
        @VisibleForTesting
        var http: OkHttpClient? = null
    }
}
