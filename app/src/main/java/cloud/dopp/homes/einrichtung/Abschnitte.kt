package cloud.dopp.homes.einrichtung

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.DrawableRes
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.ui.Ueberblick
import cloud.dopp.homes.verbindung.Verbindung
import cloud.dopp.homes.widget.HomesPages
import java.util.Locale

/**
 * The start screen's parts from the kept answer of `/napi/einrichtung`
 * ([EinrichtungStore], owner 2026-11-04; one row and a grid since 2026-11-27):
 *
 * **Deine Aufgaben** is one row: the title, the number open (grey: Homes says
 * no task is urgent yet), the next task's title as its line; a tap opens
 * Homes' list of all. Gone while none is open.
 *
 * The modules Homes lists under „Öffnen“ are tiles of „Alles auf einen
 * Blick“ ([cloud.dopp.homes.ui.Ueberblick]), drawn with it.
 *
 * Without an answer — never read, or an older Homes (404) — both are gone,
 * quietly. While the box is not reached (Verbindung) they show what was read
 * last, greyed, and say nothing of their own.
 */
object Abschnitte {

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
        aufgabenZeichnen(a, e, Verbindung.stand(a) != null)
        Ueberblick.zeichnen(a, a.findViewById(R.id.ueberblick_section), a.findViewById(R.id.ueberblick_group))
    }

    /** „Deine Aufgaben“ in one row; its tap: Homes' list of all. */
    private fun aufgabenZeichnen(a: Activity, e: Einrichtung?, grau: Boolean) {
        val section = a.findViewById<View>(R.id.aufgaben_section) ?: return
        val aufgaben = e?.aufgaben.orEmpty()
        val offen = if (e != null && e.offen > 0) e.offen else aufgaben.size
        section.isVisible = offen > 0
        section.alpha = if (grau) GRAU else 1f
        a.findViewById<TextView>(R.id.aufgaben_zahl)?.apply {
            text = String.format(Locale.getDefault(), "%d", offen)
            contentDescription = a.resources.getQuantityString(R.plurals.aufgaben_offen, offen, offen)
        }
        val naechste = aufgaben.firstOrNull()?.let { it.aufgabe?.titel ?: it.titel }
        a.findViewById<TextView>(R.id.aufgaben_naechste)?.apply {
            text = naechste
            isVisible = naechste != null
        }
        section.setOnClickListener {
            ServerStore.baseUrl(a)?.let { oeffnen(a, HomesPages.url(it, HomesPages.AUFGABEN).toUri()) }
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
        "rezepte" to R.drawable.ic_modul_rezepte, // the shared recipes, owner 2026-11-27
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
