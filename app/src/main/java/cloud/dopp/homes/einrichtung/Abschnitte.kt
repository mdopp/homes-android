package cloud.dopp.homes.einrichtung

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.DrawableRes
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.verbindung.Verbindung
import cloud.dopp.homes.widget.HomesPages
import java.util.Locale

/**
 * The start screen's „Öffnen“ and „Deine Aufgaben“ (owner, 2026-11-04), from
 * the kept answer of `/napi/einrichtung` ([EinrichtungStore]).
 *
 * **Öffnen** lists the modules Homes marks `oeffnen` ([Einrichtung.oeffnen], owner
 * 2026-11-07), each with its address, opened in a Custom Tab, where the person's
 * login is, and below them „Homes“, Homes' own start page. A row says under its
 * title where it stands (`stand`) and opens `ziel` where Homes names one — the
 * timeline itself, not the page about it (owner 2026-11-08: „direkter bauen“).
 * A module with its own app is a task while the app is missing, and then the
 * phone's own app icon is the way in.
 *
 * **Deine Aufgaben** lists the first [ERSTE] open tasks with the number open
 * beside the title, as Homes' pages show it: a tap gets the app from its store
 * while it is missing, else opens the task's steps on Homes. With more, a last
 * row „Alle N Aufgaben“ opens Homes' list of all (owner 2026-11-14: „zu viele
 * Aufgaben … immer nur die ersten mit Verweis auf alle“).
 *
 * Without an answer — never read, or an older Homes (404) — both sections are
 * gone, quietly. While the box is not reached (Verbindung) they show what was
 * read last, greyed, and say nothing of their own.
 */
object Abschnitte {

    /** How many tasks the start screen shows before „Alle N Aufgaben“. */
    const val ERSTE = 3

    /** The tasks shown, and whether a row to all of them follows. */
    fun <T> kurz(alle: List<T>, offen: Int): Pair<List<T>, Boolean> = alle.take(ERSTE) to (maxOf(offen, alle.size) > ERSTE)

    /** Draw on each resume and on each change, and ask Homes (at most every five minutes) while [a] is in front. */
    fun zeigen(a: ComponentActivity) {
        val empfaenger = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = zeichnen(a)
        }
        a.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                val filter = IntentFilter(EinrichtungLeser.ACTION_GEAENDERT).apply { addAction(Verbindung.GEAENDERT) }
                ContextCompat.registerReceiver(a, empfaenger, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                zeichnen(a)
                EinrichtungLeser.lesen(a)
            }

            override fun onPause(owner: LifecycleOwner) {
                runCatching { a.unregisterReceiver(empfaenger) }
            }
        })
    }

    fun zeichnen(a: Activity) {
        val e = EinrichtungStore.einrichtung(a)
        val grau = Verbindung.stand(a) != null
        val oeffnen = e?.oeffnen.orEmpty()
        // below the modules, Homes' own start page (owner, 2026-11-04), whenever the box answered
        val homes = ServerStore.baseUrl(a)?.takeIf { e != null }?.let { HomesPages.url(it, HomesPages.START) }
        fuellen(a, R.id.oeffnen_section, R.id.oeffnen_group, grau, oeffnen.map { m ->
            zeile(a, Bilder.fuer(m.icon), m.titel, m.stand) { m.tippen?.let { oeffnen(a, it.toUri()) } }
        } + listOfNotNull(homes?.let { zeile(a, R.drawable.ic_stat_homes, a.getString(R.string.oeffnen_homes), null) { oeffnen(a, it.toUri()) } }))
        val aufgaben = e?.aufgaben.orEmpty()
        val offen = if (e != null && e.offen > 0) e.offen else aufgaben.size
        a.findViewById<TextView>(R.id.aufgaben_zahl)?.apply {
            text = String.format(Locale.getDefault(), "%d", offen)
            alpha = if (grau) GRAU else 1f // the number is the last one known too
            contentDescription = a.resources.getQuantityString(R.plurals.aufgaben_offen, offen, offen)
        }
        val (erste, mehr) = kurz(aufgaben, offen)
        val alle = ServerStore.baseUrl(a)?.takeIf { mehr }?.let { HomesPages.url(it, HomesPages.AUFGABEN) }
        fuellen(a, R.id.aufgaben_section, R.id.aufgaben_group, grau, erste.map { m ->
            val app = m.app
            val fehlt = app != null && app.store != null && !EinrichtungLeser.installiert(a, app.paket)
            val unter = if (fehlt) a.getString(R.string.aufgabe_holen, app?.name) else a.getString(R.string.aufgabe_weiter)
            zeile(a, Bilder.fuer(m.icon), m.aufgabe?.titel ?: m.titel, unter) {
                if (fehlt) ansehen(a, app?.store) else m.aufgabe?.einrichten?.let { oeffnen(a, it.toUri()) }
            }
        } + listOfNotNull(alle?.let {
            zeile(a, R.drawable.ic_stat_homes, a.resources.getQuantityString(R.plurals.aufgaben_alle, offen, offen), null) { oeffnen(a, it.toUri()) }
        }))
    }

    private fun fuellen(a: Activity, section: Int, group: Int, grau: Boolean, zeilen: List<(ViewGroup) -> View>) {
        val g = a.findViewById<ViewGroup>(group) ?: return
        g.removeAllViews()
        zeilen.forEach { g.addView(it(g)) }
        g.alpha = if (grau) GRAU else 1f
        a.findViewById<View>(section)?.visibility = if (zeilen.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun zeile(a: Activity, @DrawableRes bild: Int, titel: String, unter: String?, tap: () -> Unit): (ViewGroup) -> View = { g ->
        a.layoutInflater.inflate(R.layout.item_einrichtung, g, false).apply {
            findViewById<ImageView>(R.id.einr_bild).setImageResource(bild)
            findViewById<TextView>(R.id.einr_titel).text = titel
            findViewById<TextView>(R.id.einr_unter).apply {
                text = unter
                visibility = if (unter == null) View.GONE else View.VISIBLE
            }
            setOnClickListener { tap() }
        }
    }

    /** [uri] in a Custom Tab (it shares the browser's login), else the browser; none: said in a toast. */
    fun oeffnen(ctx: Context, uri: Uri) {
        val tab = CustomTabsIntent.Builder().build()
        if (ctx !is Activity) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { tab.launchUrl(ctx, uri) }.isSuccess) return
        if (runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        Toast.makeText(ctx, R.string.home_no_browser, Toast.LENGTH_LONG).show()
    }

    /** An app's store page as the phone opens such a link: the store app where there is one, else the browser. */
    private fun ansehen(ctx: Context, url: String?) {
        val uri = (url ?: return).toUri()
        if (runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }.isFailure) oeffnen(ctx, uri)
    }

    /** How faint the sections are while what they show is the last thing known. */
    private const val GRAU = 0.45f
}

/** A module's picture by the app's key for it (`icon`, the module's name; drawables cannot come from Homes): an unknown key gets the generic one. */
object Bilder {
    private val NACH_ICON = mapOf(
        "einkauf" to R.drawable.ic_modul_einkauf,
        "dokumente" to R.drawable.ic_modul_dokumente,
        "kalender" to R.drawable.ic_modul_kalender,
        "musik" to R.drawable.ic_modul_musik,
        "podcasts" to R.drawable.ic_modul_podcasts,
        "filme" to R.drawable.ic_modul_filme,
        "fotos" to R.drawable.ic_modul_fotos,
        "passwoerter" to R.drawable.ic_modul_passwoerter,
        "sprechen" to R.drawable.ic_modul_sprechen,
        "netzlaufwerk" to R.drawable.ic_modul_netzlaufwerk,
        "geraete" to R.drawable.ic_modul_geraete,
        "kontakte" to R.drawable.ic_modul_kontakte,
        "notizen" to R.drawable.ic_modul_notizen,
        "werkstatt" to R.drawable.ic_modul_werkstatt,
        "nutzung" to R.drawable.ic_modul_nutzung,
        "gesehen" to R.drawable.ic_modul_gesehen,
        "energie" to R.drawable.ic_bolt,
        "konto" to R.drawable.ic_konto,
        "sicherung" to R.drawable.ic_backup,
        "standort" to R.drawable.ic_standort,
    )

    @DrawableRes
    fun fuer(icon: String?): Int = NACH_ICON[icon] ?: R.drawable.ic_services
}
