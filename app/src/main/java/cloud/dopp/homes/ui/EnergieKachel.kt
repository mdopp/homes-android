package cloud.dopp.homes.ui

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.Energy
import cloud.dopp.homes.data.EnergyTotal
import cloud.dopp.homes.data.ScreenGate
import cloud.dopp.homes.data.TokenStore
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * The „Energie“ tile's line (owner 2026-11-28: „Energie brauche ich auch
 * nicht als Widget sondern nur als Link zur Anzeige.“): the numbers the
 * energy widget showed, in one line — „Haus 260 W · heute 5,3 kWh erzeugt“
 * — from `/napi/portal/energy`, read when the start screen draws, at most
 * every [ALLE_MS], off the main thread, never with the screen off, the line
 * read last kept.
 */
object EnergieKachel {

    /** How often the start screen reads the energy at most. */
    private const val ALLE_MS = 5L * 60 * 1000
    private const val PREFS = "start_energie"
    private const val ZEILE = "zeile"
    private const val UM = "um"
    private const val KW = 1000.0

    /** The line read last; null before the first read. */
    fun gemerkt(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ZEILE, null)

    /** Read the energy unless it was read lately; [neu] on the main thread when the line changed. */
    fun lesen(a: Activity, neu: () -> Unit) {
        val p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val jetzt = SystemClock.elapsedRealtime()
        val um = p.getLong(UM, -1L)
        if (um in 0..jetzt && jetzt - um < ALLE_MS) return // a reboot resets the clock: then it reads
        if (!TokenStore.isPaired(a) || !ScreenGate.open(a)) return
        p.edit { putLong(UM, jetzt) }
        val app = a.applicationContext
        thread(name = "energie") {
            val e = ApiClient(app).energyBody()?.let { runCatching { ApiClient.parseEnergy(JSONObject(it)) }.getOrNull() } ?: return@thread
            val z = zeile(e) ?: return@thread
            if (z == gemerkt(app)) return@thread
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(ZEILE, z) }
            a.runOnUiThread { if (!a.isFinishing) neu() }
        }
    }

    /** „Haus 260 W · heute 5,3 kWh erzeugt“ — what the house draws now, what the sun made today (else what came from the grid). */
    private fun zeile(e: Energy): String? {
        val haus = e.house?.watts?.let { "Haus " + watt(it) }
        val pv = e.totals.firstOrNull { ist(it, "pv", "supply", "solar", "produktion", "production", "erzeug", "generation") }?.today
        val bezug = e.totals.firstOrNull { ist(it, "import", "netzbezug", "netz_bezug", "bezug", "consumption") && !ist(it, "export", "einspeis") }?.today
        val heute = when {
            pv != null -> "heute " + kwh(pv) + " erzeugt"
            bezug != null -> "heute " + kwh(bezug) + " Netzbezug"
            else -> null
        }
        return listOfNotNull(haus, heute).joinToString(" · ").ifEmpty { null }
    }

    /** Homes' totals carry no key of their own: told by the words of their key, entity and label. */
    private fun ist(t: EnergyTotal, vararg worte: String): Boolean {
        val text = listOfNotNull(t.key, t.entityId, t.label).joinToString(" ").lowercase()
        return worte.any { text.contains(it) }
    }

    private fun watt(w: Double): String =
        if (abs(w) >= KW) String.format(Locale.GERMANY, "%.1f kW", abs(w) / KW) else String.format(Locale.GERMANY, "%.0f W", abs(w))

    private fun kwh(v: Double): String = String.format(Locale.GERMANY, "%.1f kWh", v)
}
