package cloud.dopp.homes.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import cloud.dopp.homes.R
import cloud.dopp.homes.ausloeser.Ausloeser
import cloud.dopp.homes.ausloeser.AusloeserActivity
import cloud.dopp.homes.data.Napi.KannKey
import cloud.dopp.homes.data.Napi.Quelle
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.kann.KannStore
import cloud.dopp.homes.kann.Liste
import cloud.dopp.homes.konto.HomesKonto
import cloud.dopp.homes.konto.KontoStore
import cloud.dopp.homes.sicherung.SicherungStore
import cloud.dopp.homes.standort.StandortStore
import cloud.dopp.homes.teilen.Behalten
import cloud.dopp.homes.teilen.BehalteneActivity
import cloud.dopp.homes.teilen.GastWlanActivity
import cloud.dopp.homes.teilen.TeilenActivity
import cloud.dopp.homes.teilen.VisitenkarteActivity

/**
 * What this phone does with the box, in one place (the owner, 2026-11-27):
 * the start screen's row „Dieses Telefon · <name>“ says it in one line —
 * „gesichert heute 03:11 · Kalender synchron“, orange while a backup part is
 * overdue — and its page groups it: what it backs up (Daten sichern), what
 * it is connected with (Homes auf dem Handy), what it reports (the
 * Standortverlauf, the charger), and its pairing with the app's version. Each
 * row opens the screen that was there before; nothing of their logic moved.
 */
object Telefon {

    /** One backup part the person set up: its source, its name, when it last went up (null: not yet). */
    data class Teil(val quelle: Quelle, val name: String, val zuletzt: Long?)

    /** The phone's name as its owner set it, else its model. */
    fun name(ctx: Context): String =
        runCatching { Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** The backup parts set up on this phone: a granted folder, or the SMS switch. */
    fun teile(ctx: Context): List<Teil> {
        val s = SicherungStore(ctx)
        return listOfNotNull(
            Teil(Quelle.WHATSAPP, "WhatsApp", s.uebertragen(Quelle.WHATSAPP)?.first).takeIf { s.ordner(Quelle.WHATSAPP) != null },
            Teil(Quelle.SIGNAL, "Signal", s.uebertragen(Quelle.SIGNAL)?.first).takeIf { s.ordner(Quelle.SIGNAL) != null },
            Teil(Quelle.SMS, "SMS und Anrufe", s.uebertragen(Quelle.SMS)?.first).takeIf { s.smsAn() },
        )
    }

    /** A part quiet for more than two days ([Jetzt]'s rule): the row turns orange and „Jetzt“ says it. */
    fun ueberfaellig(ctx: Context, jetzt: Long = System.currentTimeMillis()): List<Jetzt.Hinweis> =
        teile(ctx).mapNotNull { Jetzt.sicherung(it.quelle, it.name, it.zuletzt, jetzt) }

    /** The row's line: when the newest backup went up, and which household parts are in step. */
    fun zeile(ctx: Context): String = listOfNotNull(gesichert(ctx), synchron(ctx)).joinToString(" · ")

    /** „gesichert heute 03:11“, „noch nicht gesichert“ with parts set up; null without any. */
    private fun gesichert(ctx: Context): String? {
        val teile = teile(ctx)
        val zuletzt = teile.mapNotNull { it.zuletzt }.maxOrNull()
        return zuletzt?.let { ctx.getString(R.string.telefon_gesichert, Ausloeser.wann(it)) }
            ?: ctx.getString(R.string.telefon_nie).takeIf { teile.isNotEmpty() }
    }

    /** „Kalender synchron · Kontakte synchron“: the Homes account's parts on and synced once; null for none. */
    fun synchron(ctx: Context): String? {
        val konto = KontoStore(ctx)
        return HomesKonto.Teil.values().filter { HomesKonto.an(ctx, it) && konto.stand(it) != null }
            .joinToString(" · ") {
                ctx.getString(R.string.telefon_synchron, ctx.getString(if (it == HomesKonto.Teil.KALENDER) R.string.telefon_kalender else R.string.telefon_kontakte))
            }.ifEmpty { null }
    }
}

class TelefonActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_telefon)
        padForSystemBars(findViewById(R.id.telefon_root))
        findViewById<Button>(R.id.logout_btn).setOnClickListener {
            // the start screen unpairs as it always did, and shows the pairing
            startActivity(
                Intent(this, HomeActivity::class.java).putExtra(HomeActivity.EXTRA_ENTKOPPELN, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        }
        findViewById<TextView>(R.id.app_version).text = getString(
            R.string.app_version_fmt,
            runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?",
        )
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        findViewById<TextView>(R.id.telefon_name).text = Telefon.name(this)
        findViewById<TextView>(R.id.status).text = getString(R.string.home_connected, ServerStore.host(this) ?: "")
        val kann = KannStore.kann(this)
        val sichert = findViewById<ViewGroup>(R.id.telefon_sichert)
        sichert.removeAllViews()
        val angebot = StartAngebot(this, kann, ::render)
        val sicherung = Telefon.teile(this).joinToString(" · ") { t ->
            t.name + " " + (t.zuletzt?.let { Ausloeser.wann(it) } ?: getString(R.string.telefon_nie_kurz))
        }.ifEmpty { null }
        angebot.fuellen(sichert, Liste.MEHR, listOf(zeile(KannKey.DATEN_SICHERN, R.drawable.ic_backup, R.string.sicherung_title, SicherungActivity::class.java)), {
            reihe(sichert, it, sicherung)
        })
        val verbunden = findViewById<ViewGroup>(R.id.telefon_verbunden)
        verbunden.removeAllViews()
        verbunden.addView(reihe(verbunden, zeile(KONTO, R.drawable.ic_konto, R.string.konto_title, HomesKontoActivity::class.java), Telefon.synchron(this)))
        val meldet = findViewById<ViewGroup>(R.id.telefon_meldet)
        meldet.removeAllViews()
        val standort = StandortStore(this).let { s ->
            if (!s.an()) getString(R.string.telefon_aus) else s.zuletztGesendet()?.let { getString(R.string.telefon_an_zuletzt, Ausloeser.wann(it.first)) } ?: getString(R.string.telefon_an)
        }
        val laden = getString(if (Ausloeser.ladenMelden(this)) R.string.telefon_an else R.string.telefon_aus)
        val status = mapOf(KannKey.STANDORTVERLAUF to standort, KannKey.AUSLOESER to laden)
        angebot.fuellen(meldet, Liste.MEHR, listOf(
            zeile(KannKey.STANDORTVERLAUF, R.drawable.ic_standort, R.string.standort_title, StandortActivity::class.java),
            zeile(KannKey.AUSLOESER, R.drawable.ic_ausloeser, R.string.ausloeser_laden_titel, AusloeserActivity::class.java),
        ), { reihe(meldet, it, status[it.key]) })
        val teilt = findViewById<ViewGroup>(R.id.telefon_teilt)
        teilt.removeAllViews()
        val behalten = Behalten.alle(this).size
        listOf(
            zeile(TEILEN, R.drawable.ic_ausloeser, R.string.teilen_titel, TeilenActivity::class.java) to null,
            zeile(TEILEN, R.drawable.ic_modul_kontakte, R.string.karte_titel_visitenkarte, VisitenkarteActivity::class.java) to
                getString(if (Behalten.visitenkarte(this) != null) R.string.telefon_an else R.string.telefon_aus),
            zeile(TEILEN, R.drawable.ic_ausloeser, R.string.gast_titel, GastWlanActivity::class.java) to
                (Behalten.gastWlan(this)?.ssid ?: getString(R.string.telefon_aus)),
            zeile(TEILEN, R.drawable.ic_ausloeser, R.string.behalten_titel, BehalteneActivity::class.java) to
                resources.getQuantityString(R.plurals.behalten_anzahl, behalten, behalten),
        ).forEach { (z, stand) -> teilt.addView(reihe(teilt, z, stand)) }
    }

    private fun zeile(key: String, @DrawableRes bild: Int, titel: Int, ziel: Class<*>) =
        StartAngebot.Zeile(key, bild, getString(titel)) { startActivity(Intent(this, ziel)) }

    /** One row in the look of „Deine Aufgaben“: picture, name, where it stands. */
    private fun reihe(g: ViewGroup, z: StartAngebot.Zeile, stand: String?): View =
        layoutInflater.inflate(R.layout.item_einrichtung, g, false).apply {
            findViewById<ImageView>(R.id.einr_bild).setImageResource(z.icon)
            findViewById<TextView>(R.id.einr_titel).text = z.label
            findViewById<TextView>(R.id.einr_unter).apply {
                text = stand
                isVisible = stand != null
            }
            setOnClickListener { z.tap() }
        }

    private companion object {
        /** „Homes auf dem Handy“ is always there: no key of the box gates it. */
        const val KONTO = "konto"

        /** „Teilen per Berührung“ is always there: no key of the box gates it, nothing goes to the box. */
        const val TEILEN = "teilen"
    }
}
