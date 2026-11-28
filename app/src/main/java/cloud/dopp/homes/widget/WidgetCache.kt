package cloud.dopp.homes.widget

import android.content.Context
import androidx.core.content.edit
import cloud.dopp.homes.data.Card
import org.json.JSONObject

/**
 * Persistent **last-good card** of each device widget (#46): a failed read
 * draws the card read last instead of an empty tile, after a reboot too. Only
 * a widget that has never loaded shows the placeholder.
 *
 * Storage is a **plain** `MODE_PRIVATE` prefs file — deliberately NOT encrypted,
 * so it is not coupled to the AndroidKeyStore wipe that used to take the token
 * (and thus the widgets) down with it. Values are non-secret UI state.
 */
object WidgetCache {
    private const val PREFS = "homes_widget_cache"

    private fun p(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun putCard(ctx: Context, id: Int, card: Card) {
        val o = JSONObject()
            .put("entityId", card.entityId)
            .put("name", card.name)
            .put("domain", card.domain)
            .putOpt("deviceClass", card.deviceClass)
            .putOpt("state", card.state)
            .putOpt("unit", card.unit)
            .putOpt("page", card.page)
        card.brightness?.let { o.put("brightness", it) }
        card.position?.let { o.put("position", it) }
        card.temperature?.let { o.put("temperature", it) }
        // the lock chooser reads the latch bit from here (#92), after a restart too
        card.supportedFeatures?.let { o.put("supportedFeatures", it) }
        runCatching { p(ctx).edit().putString("card_$id", o.toString()).apply() }
    }

    fun getCard(ctx: Context, id: Int): Card? {
        val s = runCatching { p(ctx).getString("card_$id", null) }.getOrNull() ?: return null
        return runCatching {
            val o = JSONObject(s)
            Card(
                entityId = o.getString("entityId"),
                name = o.optString("name"),
                domain = o.optString("domain"),
                deviceClass = o.str("deviceClass"),
                state = o.str("state"),
                unit = o.str("unit"),
                brightness = if (o.has("brightness")) o.optInt("brightness") else null,
                position = if (o.has("position")) o.optInt("position") else null,
                temperature = if (o.has("temperature")) o.optDouble("temperature") else null,
                supportedFeatures = if (o.has("supportedFeatures")) o.optInt("supportedFeatures") else null,
                page = o.str("page"),
            )
        }.getOrNull()
    }

    /** Drop the card of [id] (widget removed / re-bound). */
    fun clear(ctx: Context, id: Int) {
        p(ctx).edit().remove("card_$id").apply()
    }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key).ifBlank { null }
}
