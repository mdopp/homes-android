package cloud.dopp.homes.ui

import android.content.Context
import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.Card
import java.util.Locale

/**
 * The start screen's devices (owner, 2026-11-02; one device per widget came
 * back 2026-11-03, [cloud.dopp.homes.widget.DeviceWidgetProvider]): the person's favorites from Homes (`/napi/portal/favorites`,
 * the things they pinned with ☆ on Homes), first on the screen, in their
 * order, as tiles ([FavoritenAnsicht]), „Alle →“ in their head opening Homes' devices page.
 *
 * A tap acts the way the device widget's did: a light or a switch toggles, a
 * cover opens or closes (a garage asks first, the server's 403), a lock opens
 * its chooser (#92). Anything else opens the device's own page on Homes.
 *
 * The list last read is kept, so the screen draws at once and a failed read
 * keeps it (stale-then-fresh). Pure but for [load]/[save]: JVM-testable.
 */
object StartDevices {

    private const val PREFS = "homes_start"
    private const val KEY_FAVORITES = "favorites_json"

    /** The domains a tap acts on; everything else opens its page. */
    val ACTING = setOf("light", "switch", "cover", "lock")

    /** Does a tap on [card] switch it (or, for a lock, open its chooser)? */
    fun acts(card: Card): Boolean = card.domain in ACTING

    /** The favorites read last, or null when there is none yet. */
    fun load(ctx: Context): List<Card>? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FAVORITES, null)
            ?.let { ApiClient.parseFavorites(it) }

    /** Keep Homes' answer [body] as the list read last. */
    fun save(ctx: Context, body: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FAVORITES, body).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** The state as the row says it, in plain German: Homes' own words where it sends them ([Card.text], a printer's), else the app's. */
    fun stateText(card: Card): String {
        card.text?.let { return it }
        val state = card.state?.trim()?.lowercase(Locale.ROOT)
        if (card.domain == "lock") return lockLabel(state)
        if (state == null || state == "unknown") return "—"
        if (state == "unavailable") return "nicht erreichbar"
        return when (card.domain) {
            "light" -> if (state == "on") listOfNotNull("an", card.brightnessPct?.let { "$it %" }).joinToString(" · ") else "aus"
            "switch" -> if (state == "on") "an" else "aus"
            "cover" -> when (state) {
                "open" -> listOfNotNull("offen", card.position?.takeIf { it in 1..99 }?.let { "$it %" }).joinToString(" · ")
                "closed" -> "zu"
                "opening" -> "öffnet …"
                "closing" -> "schließt …"
                else -> state
            }
            "climate" -> listOfNotNull(
                card.temperature?.let { String.format(Locale.GERMANY, "%.1f °C", it) } ?: words(state),
                card.setpoint?.takeIf { state != "off" }?.let { "Soll " + String.format(Locale.GERMANY, "%.1f °C", it) },
            ).joinToString(" · ")
            "media_player" -> when (state) {
                "playing" -> "spielt"
                "paused" -> "pausiert"
                "off" -> "aus"
                "idle", "standby", "on" -> "bereit"
                else -> state
            }
            "binary_sensor" -> if (state == "on") "an" else "aus"
            else -> listOfNotNull(card.state?.trim()?.replace('.', ','), card.unit).joinToString(" ")
        }
    }

    /**
     * The row's line for a favorite with a level (owner, 2026-11-03,
     * homes#230): the words before it and the level, which the row shows as
     * the button that opens [LevelSheet] — „an · 60 %“, „offen · 60 %“, and
     * „geschlossen · 0 %“, so a closed cover has something to tap; a lamp
     * that is off no words and „aus“ (owner, 2026-11-04). Null without a
     * level: the row says [stateText].
     */
    fun levelLine(card: Card): Pair<String, DeviceControls.Slider>? {
        val level = DeviceControls.of(card).slider ?: return null
        val state = card.state?.trim()?.lowercase(Locale.ROOT)
        val words = when (card.domain) {
            "light" -> if (state == "on") "an" else ""
            "cover" -> when (state) {
                "open" -> "offen"
                "closed" -> "geschlossen"
                "opening" -> "öffnet …"
                "closing" -> "schließt …"
                else -> state.orEmpty()
            }
            else -> stateText(card)
        }
        return words to level
    }

    /** A level as the row and the sheet say it. */
    fun percent(v: Int): String = "${v.coerceIn(0, 100)} %"

    /**
     * German state text for a lock (#84). It talks about the **bolt**, never the
     * door: there is no door sensor, so a lock reporting `locked` says
     * *abgeschlossen*, not *zu*. `unknown` (no MQTT retain message yet after a
     * broker or phone restart) is its own word — it must never read as
     * *abgeschlossen*.
     */
    fun lockLabel(state: String?): String = when (state?.trim()?.lowercase()) {
        "locked" -> "abgeschlossen"
        "unlocked" -> "aufgeschlossen"
        "open" -> "entriegelt"
        "locking" -> "schließt ab …"
        "unlocking" -> "schließt auf …"
        "opening" -> "entriegelt …"
        "jammed" -> "klemmt"
        "unavailable" -> "nicht erreichbar"
        null, "", "unknown", "none" -> "unbekannt"
        else -> state
    }

    private fun words(state: String): String = when (state) {
        "heat" -> "heizt"
        "cool" -> "kühlt"
        "off" -> "aus"
        "auto" -> "automatisch"
        else -> state
    }
}
