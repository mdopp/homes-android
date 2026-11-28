package cloud.dopp.homes.teilen

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import cloud.dopp.homes.R
import cloud.dopp.homes.ausloeser.WlanAnbietenActivity
import cloud.dopp.homes.ausloeser.WlanAngebot
import cloud.dopp.homes.teilen.AustauschClient.Antwort
import cloud.dopp.homes.ui.padForSystemBars
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * „Kontakt austauschen“ → „Was teilen?“ (owner approved 2026-11-28): the
 * session's link goes to the other phone at once — by NFC, as a QR code, or
 * already on the touch that started it ([Teilen]) — and its page shows only
 * the first name until „Freigeben“ sends exactly what is switched on: the
 * card's fields (remembered per field), the guest Wi-Fi, the form to fill in
 * one's own details (an admin's; Homes says no otherwise). Pressed again, the
 * page follows. Every 3 s while open: „Link geteilt · Seite geöffnet · hat
 * eingetragen“; „Beenden“ ends it, and so does its time.
 */
class AustauschActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var stand: TextView
    private lateinit var meldung: TextView
    private lateinit var freigeben: Button
    private lateinit var wlan: SwitchCompat
    private lateinit var eintragen: SwitchCompat
    private val schalter = mutableMapOf<Visitenkarte.Feld, SwitchCompat>()
    private var vorname: String? = null
    private var felder: Map<Visitenkarte.Feld, String> = emptyMap()

    private var geteilt = false
    private var geoeffnet = false
    private var eingetragen = false
    private var vorbei = false
    private var fragt = false

    private val angebot = Anbieten(this) { satz -> stand.text = satz }

    private val abfrage = object : Runnable {
        override fun run() {
            fragen()
            main.postDelayed(this, ABFRAGE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_austausch)
        padForSystemBars(findViewById(R.id.austausch_root))
        Teilen.weg(this)
        stand = findViewById(R.id.austausch_stand)
        meldung = findViewById(R.id.austausch_meldung)
        freigeben = findViewById(R.id.austausch_freigeben)
        wlan = findViewById(R.id.austausch_wlan)
        eintragen = findViewById(R.id.austausch_eintragen)
        findViewById<Button>(R.id.austausch_nfc).setOnClickListener { Austausch.jetzt?.let { nfc(it) } }
        findViewById<Button>(R.id.austausch_qr).setOnClickListener { Austausch.jetzt?.let { qr(it) } }
        findViewById<Button>(R.id.austausch_karte_festlegen).setOnClickListener { startActivity(Intent(this, VisitenkarteActivity::class.java)) }
        freigeben.setOnClickListener { freigeben() }
        findViewById<Button>(R.id.austausch_beenden).setOnClickListener { beenden() }
        if (Austausch.jetzt?.let { abgelaufen(it.bis, System.currentTimeMillis()) } == true) Austausch.jetzt = null
        val s = Austausch.jetzt
        val wie = intent.getStringExtra(WIE)
        when {
            s == null -> starten(wie)
            wie == NFC -> nfc(s)
            wie == QR -> qr(s)
            WlanAngebot.einmal && WlanAngebot.ssid != null -> angebot.uebernehmen { ja -> danach(ja) } // armed by the touch
            else -> {
                geteilt = WlanAngebot.geteilt // the touch's offer, read before this screen came
                zeigeStand()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        karte()
        angebot.vorn()
        main.post(abfrage)
    }

    override fun onPause() {
        angebot.weg()
        main.removeCallbacks(abfrage)
        super.onPause()
    }

    override fun onDestroy() {
        angebot.zu(isFinishing)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** The card's fields, each its switch (as last time), the guest Wi-Fi's, the first name that always shows. */
    private fun karte() {
        val (v, f) = Austausch.karte(this)
        vorname = v
        felder = f
        findViewById<TextView>(R.id.austausch_vorname).text =
            v?.let { getString(R.string.austausch_vorname, it) } ?: getString(R.string.austausch_vorname_login)
        val g = findViewById<ViewGroup>(R.id.austausch_felder)
        g.removeAllViews()
        schalter.clear()
        Austausch.FELDER.filter { it in f }.forEach { feld ->
            val sw = SwitchCompat(this).apply {
                text = titel(feld, f.getValue(feld))
                isChecked = Austausch.feldAn(this@AustauschActivity, feld)
                setOnCheckedChangeListener { _, an -> Austausch.setzeFeld(this@AustauschActivity, feld, an) }
            }
            schalter[feld] = sw
            g.addView(sw)
        }
        findViewById<Button>(R.id.austausch_karte_festlegen).isVisible = f.isEmpty()
        val gast = Behalten.gastWlan(this)
        wlan.isVisible = gast != null
        gast?.let { wlan.text = getString(R.string.austausch_wlan, it.ssid) }
    }

    private fun titel(f: Visitenkarte.Feld, w: String): String = when (f) {
        Visitenkarte.Feld.NAME -> getString(R.string.austausch_nachname, w.substringAfterLast(' '))
        Visitenkarte.Feld.MOBIL -> getString(R.string.karte_feld_mobil, w)
        Visitenkarte.Feld.TELEFON -> getString(R.string.karte_feld_telefon, w)
        Visitenkarte.Feld.MAIL -> getString(R.string.karte_feld_mail, w)
        Visitenkarte.Feld.ADRESSE -> getString(R.string.karte_feld_adresse, w)
        Visitenkarte.Feld.GEBURTSTAG -> getString(R.string.karte_feld_geburtstag, w)
        Visitenkarte.Feld.FIRMA -> getString(R.string.karte_feld_firma, w)
    }

    /** A new session, then its link at once: by NFC ([wie] [NFC]) or as a QR code. */
    private fun starten(wie: String?) {
        val c = Austausch.client(this) ?: return ende(getString(R.string.share_not_paired))
        stand.text = getString(R.string.austausch_startet)
        freigeben.isEnabled = false
        thread(name = "austausch-neu") {
            val a = c.neu(Austausch.karte(this).first)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                freigeben.isEnabled = true
                if (a !is Antwort.Neu) return@runOnUiThread ende(satz(a))
                val s = Austausch.Sitzung(a.id, a.link, a.bis)
                Austausch.jetzt = s
                zeigeStand()
                main.removeCallbacks(abfrage)
                main.post(abfrage) // its first state now, then every 3 s
                if (wie == QR) qr(s) else nfc(s)
            }
        }
    }

    private fun nfc(s: Austausch.Sitzung) {
        val warum = WlanAnbietenActivity.warum(this)
        if (warum != null) {
            meldung.text = warum
            return
        }
        angebot.starten(getString(R.string.austausch_titel), ndef(s.link)) { ja -> danach(ja) }
    }

    private fun danach(geteiltJetzt: Boolean) {
        geteilt = geteilt || geteiltJetzt
        zeigeStand()
    }

    private fun qr(s: Austausch.Sitzung) {
        angebot.abbrechen()
        geteilt = true
        zeigeStand()
        Anbieten.qr(this, getString(R.string.austausch_titel), s.link)
    }

    /** „Freigeben“: exactly what is switched on, all of it; the page follows. */
    private fun freigeben() {
        val s = Austausch.jetzt ?: return
        val c = Austausch.client(this)
        if (c == null) {
            meldung.text = getString(R.string.share_not_paired)
            return
        }
        val an = schalter.filterValues { it.isChecked }.keys
        val karte = Austausch.karteJson(felder, an)
        val netz = Behalten.gastWlan(this)?.takeIf { wlan.isChecked }?.let(Austausch::wlanJson)
        val form = eintragen.isChecked
        freigeben.isEnabled = false
        freigeben.text = getString(R.string.austausch_gibt_frei)
        thread(name = "austausch-setzen") {
            val a = c.setzen(s.id, vorname, karte, netz, form)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                freigeben.isEnabled = true
                freigeben.text = getString(R.string.austausch_freigeben)
                when {
                    a is Antwort.Ok -> meldung.text = getString(R.string.austausch_freigegeben)
                    a is Antwort.Vorbei -> ende(a.satz ?: getString(R.string.austausch_vorbei))
                    else -> {
                        if (a is Antwort.Abgelehnt && a.code == HTTP_403 && form) eintragen.isChecked = false // an admin's only
                        meldung.text = satz(a)
                    }
                }
            }
        }
    }

    /** Every 3 s: was the page opened, the form sent; over once Homes says so or its time is up. */
    private fun fragen() {
        val s = Austausch.jetzt ?: return
        if (vorbei || fragt) return
        if (abgelaufen(s.bis, System.currentTimeMillis())) return ende(getString(R.string.austausch_vorbei))
        val c = Austausch.client(this) ?: return
        fragt = true
        thread(name = "austausch-stand") {
            val a = c.stand(s.id)
            runOnUiThread {
                fragt = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (a) {
                    is Antwort.Stand -> {
                        geoeffnet = a.geoeffnet
                        eingetragen = a.eingetragen
                        if (!angebot.an) zeigeStand()
                    }
                    is Antwort.Vorbei -> ende(a.satz ?: getString(R.string.austausch_vorbei))
                    else -> Unit // no answer this time: the next one, in 3 s
                }
            }
        }
    }

    /** „Beenden“: the page says it ended; then this screen closes on the next tap. */
    private fun beenden() {
        val s = Austausch.jetzt
        val c = Austausch.client(this)
        if (s == null || c == null || vorbei) return finish()
        thread(name = "austausch-ende") {
            val a = c.beenden(s.id)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (a is Antwort.Ok || a is Antwort.Vorbei) ende(getString(R.string.austausch_beendet)) else meldung.text = satz(a)
            }
        }
    }

    /** Over: nothing answered or asked any more, [satz] said; the buttons gone but „Schließen“. */
    private fun ende(satz: String) {
        vorbei = true
        Austausch.jetzt = null
        angebot.abbrechen()
        main.removeCallbacks(abfrage)
        stand.text = satz
        freigeben.isEnabled = false
        findViewById<Button>(R.id.austausch_nfc).isEnabled = false
        findViewById<Button>(R.id.austausch_qr).isEnabled = false
        findViewById<Button>(R.id.austausch_beenden).setText(R.string.inhalt_zu)
    }

    private fun zeigeStand() {
        if (vorbei) return
        stand.text = listOfNotNull(
            getString(if (geteilt) R.string.austausch_geteilt else R.string.austausch_bereit),
            getString(R.string.austausch_geoeffnet).takeIf { geoeffnet },
            getString(R.string.austausch_eingetragen).takeIf { eingetragen },
        ).joinToString(" · ")
    }

    private fun satz(a: Antwort): String = when (a) {
        is Antwort.Abgelehnt -> a.satz ?: getString(R.string.einladen_unlesbar, a.code)
        is Antwort.Vorbei -> a.satz ?: getString(R.string.austausch_vorbei)
        Antwort.NichtGekoppelt -> getString(R.string.share_not_paired)
        Antwort.Zeit -> getString(R.string.einladen_zeit)
        Antwort.Offline -> getString(R.string.einladen_offline)
        else -> getString(R.string.einladen_unlesbar, 0)
    }

    companion object {
        /** How the link goes first: [NFC] or [QR]. */
        const val WIE = "wie"
        const val NFC = "nfc"
        const val QR = "qr"

        private const val ABFRAGE_MS = 3_000L
        private const val HTTP_403 = 403

        /** The link as the one record a reader gets. */
        fun ndef(link: String) = NdefMessage(arrayOf(NdefRecord.createUri(link)))

        /** Past Homes' `gueltig_bis` (RFC 3339 in UTC, as it writes it); unreadable: not. */
        fun abgelaufen(bis: String?, jetzt: Long): Boolean {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val t = runCatching { f.parse(bis ?: return false)?.time }.getOrNull() ?: return false
            return jetzt >= t
        }
    }
}
