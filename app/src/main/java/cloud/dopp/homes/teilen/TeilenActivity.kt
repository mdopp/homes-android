package cloud.dopp.homes.teilen

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import cloud.dopp.homes.R
import cloud.dopp.homes.ausloeser.TagInhalt
import cloud.dopp.homes.ausloeser.WlanTag
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.kann.KannStore
import cloud.dopp.homes.ui.padForSystemBars
import okhttp3.OkHttpClient
import kotlin.concurrent.thread

/**
 * „Teilen“ (owner 2026-11-28: „einfach nur eine Zeile pro Thema und dann
 * rechts einen Button für NFC und einen für das Anzeigen eines QR-Codes“):
 * opened from „Dieses Telefon“, the start screen's tile or the notification a
 * reading phone caused ([Teilen]). One row each: Kontakt austauschen (a
 * session on Homes, [AustauschActivity]), Meine Kontaktdaten,
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
        /** „Kontakt austauschen“: its buttons open „Was teilen?“ ([AustauschActivity]), which starts the session. */
        val austausch: Boolean = false,
        val holen: (View, (Inhalt) -> Unit) -> Unit = { _, _ -> },
    )

    /** The row whose answer is armed now; one at a time. */
    private var aktiv: View? = null

    private val angebot = Anbieten(this) { satz -> aktiv?.let { stand(it, satz) } }

    private val gastFestgelegt = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != RESULT_OK) return@registerForActivityResult
        render()
        val n = Behalten.gastWlan(this) ?: return@registerForActivityResult
        val g = liste()
        (0 until g.childCount).map { g.getChildAt(it) }.firstOrNull { it.findViewById<TextView>(R.id.teile_titel).text.toString() == getString(R.string.gast_titel) }
            ?.let { r -> gast(n).holen(r) { anbieten(r, it) } } // set: armed at once
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teilen)
        padForSystemBars(findViewById(R.id.teilen_root))
        Teilen.weg(this)
        findViewById<SwitchCompat>(R.id.teilen_beruehrung).apply {
            isVisible = Austausch.client(this@TeilenActivity) != null
            isChecked = Austausch.beiBeruehrung(this@TeilenActivity)
            setOnCheckedChangeListener { _, an -> Austausch.setzeBeiBeruehrung(this@TeilenActivity, an) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (angebot.an) angebot.vorn() else render()
    }

    override fun onPause() {
        angebot.weg() // nothing answered while the screen is not in front
        super.onPause()
    }

    override fun onDestroy() {
        angebot.zu(isFinishing)
        super.onDestroy()
    }

    private fun liste() = findViewById<ViewGroup>(R.id.teilen_liste)

    private fun render() {
        val g = liste()
        g.removeAllViews()
        themen().forEach { g.addView(reihe(g, it)) }
    }

    private fun themen(): List<Thema> = buildList {
        if (Austausch.client(this@TeilenActivity) != null) { // a session on Homes: only while paired
            add(Thema(getString(R.string.austausch_titel), R.drawable.ic_modul_kontakte, getString(R.string.austausch_unter), festlegen = null, austausch = true))
        }
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
        angebot.abbrechen()
        aktiv?.takeIf { it !== r }?.let { stand(it, "") }
        aktiv = r
        angebot.starten(r.findViewById<TextView>(R.id.teile_titel).text.toString(), inhalt.nachricht)
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
        nfc.setOnClickListener { if (t.austausch) austauschen(AustauschActivity.NFC) else t.holen(r) { anbieten(r, it) } }
        qr.isEnabled = t.keinQr == null
        qr.setOnClickListener { if (t.austausch) austauschen(AustauschActivity.QR) else t.holen(r) { i -> i.qr?.let { Anbieten.qr(this, t.titel, it) } } }
    }

    private fun austauschen(wie: String) {
        angebot.abbrechen()
        aktiv?.let { stand(it, "") }
        startActivity(Intent(this, AustauschActivity::class.java).putExtra(AustauschActivity.WIE, wie))
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

    companion object {
        /** The client's line to Homes; replaced in tests. */
        @VisibleForTesting
        var http: OkHttpClient? = null
    }
}
