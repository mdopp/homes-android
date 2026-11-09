package cloud.dopp.homes.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import cloud.dopp.homes.R
import cloud.dopp.homes.data.Energy
import cloud.dopp.homes.data.EnergyFlow
import cloud.dopp.homes.verbindung.Verbindung
import kotlin.math.abs
import kotlin.math.max

/**
 * The one energy widget (owner, 2026-11-02; redrawn 2026-11-03 twice, the
 * second time after „Das Energie Widget ist auch nur Text. Grafiken könnten
 * helfen es besser zu verstehen. Und ein aktueller Akkustand“).
 *
 * - Two cells high and more: the flow now as a picture like Homes' Energie
 *   page ([EnergyPicture], [EnergyFluss]) — Solar, Netz, Akku with how full
 *   it is as a ring, Haus, the ways the power takes now drawn — and beside it,
 *   with the room, what the counters added today (erzeugt, ins Netz,
 *   Netzbezug) in the ways' colours and the Autarkie now. While Homes says nothing
 *   about today the picture takes the whole cell.
 * - One cell high: the battery with its charge as a small ring picture (or the
 *   grid, while Homes does not say the charge), the grid's direction now
 *   beside it, and with a little more room the house's draw and one line of
 *   Heute.
 *
 * The body opens Homes' Energie page ([HomesPages.ENERGIE]). No arrows to read
 * again (owner, 2026-11-03): the live stream keeps it current. While Homes
 * cannot be reached ([Verbindung.stand]) it shows the values read last,
 * greyed, and since when.
 */
object EnergyRender {

    fun build(
        ctx: Context,
        appWidgetId: Int,
        energy: Energy?,
        paired: Boolean,
        onTap: PendingIntent,
    ): RemoteViews {
        val opts = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(appWidgetId)
        val minW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val minH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        // the portrait size: the launcher's narrowest width, its tallest height
        val maxH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        val w = if (minW > 0) minW else DEFAULT_W
        val h = max(maxH, minH).takeIf { it > 0 } ?: DEFAULT_H
        val stand = if (paired && energy != null) Verbindung.stand(ctx) else null
        val v = if (full(minW, minH)) buildFull(ctx, energy, paired, onTap, w, if (stand != null) h - STAND_DP else h)
        else buildSmall(ctx, energy, paired, onTap, h, showSub(minH), showToday(minH), todayBeside(w))
        if (stand != null) alt(ctx, v, stand, full(minW, minH))
        return v
    }

    /** The values read last, greyed, and since when: under the picture, or instead of the small tier's title. */
    private fun alt(ctx: Context, v: RemoteViews, stand: String, full: Boolean) {
        v.setInt(if (full) R.id.e_flow else R.id.e_ring, "setImageAlpha", ALT_ALPHA)
        if (full) {
            val px = { dp: Int -> (dp * density(ctx)).toInt() }
            v.setViewPadding(R.id.e_root, px(10), px(8), px(14), px(8 + STAND_DP))
            v.setViewVisibility(R.id.e_stand, View.VISIBLE)
            v.setTextViewText(R.id.e_stand, stand)
            TODAY_VALUES.forEach { v.setTextColor(it, EnergyStyle.MUTED) }
            v.setTextColor(R.id.e_autarkie_v, EnergyStyle.MUTED)
        } else {
            v.setTextViewText(R.id.e_title, stand)
            v.setTextColor(R.id.e_main, EnergyStyle.MUTED)
        }
    }

    /** The picture's alpha while it shows the values read last. */
    private const val ALT_ALPHA = 96

    /** The height the line „Stand …“ takes under the picture, dp. */
    private const val STAND_DP = 16

    /** The placed default, 4×2, when the launcher does not tell the size. */
    private const val DEFAULT_W = 300
    private const val DEFAULT_H = 150

    /**
     * Room for the picture: two cells of height. A size the launcher did not
     * tell (0) draws the full widget — the placed default is 4×2. Pure, so it
     * is JVM-testable.
     */
    fun full(minW: Int, minH: Int): Boolean = (minW == 0 && minH == 0) || minH >= 110

    /** Room for Heute beside the picture: about four cells wide. Pure. */
    private fun todayBeside(w: Int): Boolean = w >= 250

    /** The small tier's second line: only with more height than the bare 2×1. */
    fun showSub(minH: Int): Boolean = minH >= 85

    /** The small tier's line of heute: only with clearly more height than 2×1. */
    fun showToday(minH: Int): Boolean = minH >= 95

    /**
     * A leg's word: what it does now, so the number needs no legend — PV,
     * Haus, ins Netz or aus dem Netz, Akku lädt or entlädt (owner,
     * 2026-11-03: Jetzt in the present tense).
     */
    private fun legWord(f: EnergyFlow?, fallback: String): String {
        val w = f?.watts
        return when (f?.sense) {
            "grid" -> when {
                w == null -> "Netz"
                abs(w) < 1 -> "Netz ruht"
                w < 0 -> "ins Netz"
                else -> "aus dem Netz"
            }
            "battery" -> if (w == null) "Akku" else "Akku " + EnergyStyle.directionFor("battery", w)
            else -> f?.label?.ifBlank { fallback } ?: fallback
        }
    }

    /**
     * Heute in one line for the small tier: what the sun made, else what came
     * from the grid — null when Homes said nothing about today.
     */
    fun todayLine(energy: Energy?): String? {
        val slots = EnergyStyle.orderedTotals(energy?.totals.orEmpty())
        val pv = slots[0]?.today
        val drawn = slots[2]?.today
        return when {
            pv != null -> "Heute " + EnergyStyle.formatKwh(pv) + " erzeugt"
            drawn != null -> "Heute " + EnergyStyle.formatKwh(drawn) + " Netzbezug"
            else -> null
        }
    }

    /** Does Homes say anything about today? Without it the Heute part is left out. */
    private fun hasToday(energy: Energy?): Boolean = energy?.totals.orEmpty().any { it.today != null }

    private fun density(ctx: Context) = ctx.resources.displayMetrics.density

    private fun buildSmall(
        ctx: Context,
        e: Energy?,
        paired: Boolean,
        onTap: PendingIntent,
        h: Int,
        showSub: Boolean,
        showToday: Boolean,
        side: Boolean,
    ): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_energy_small)
        v.setOnClickPendingIntent(R.id.e_root, onTap)
        v.setViewVisibility(R.id.e_today, View.GONE)
        v.setViewVisibility(R.id.e_sub, if (showSub) View.VISIBLE else View.GONE)
        if (!paired) {
            v.setViewVisibility(R.id.e_ring, View.GONE)
            small(v, "Energie", "—", "In der App koppeln")
            return v
        }
        val f = e?.let { EnergyFluss.of(it) }
        // the ring as high as the cell less its padding
        v.setImageViewBitmap(R.id.e_ring, EnergyPicture.single(f, (h - 12).coerceIn(28, 120), density(ctx)))
        if (e == null || f == null) {
            small(v, "Energie", "…", "Wird geladen")
            return v
        }
        val sub: String
        if (EnergyPicture.singleKey(f) == "akku") {
            // the ring says the battery; the words say the grid, and the house
            v.setContentDescription(R.id.e_ring, f.name("akku") + ", " + f.text("akku").second)
            val g = e.grid
            sub = "Haus verbraucht " + f.text("haus").first
            small(v, legWord(g, "Netz"), g?.let { EnergyStyle.formatWatts(EnergyFluss.inWatts(it)) } ?: "—", sub)
        } else {
            // the ring says the grid; the words say the house, and the battery
            v.setContentDescription(R.id.e_ring, gridPhrase(f))
            val b = e.battery
            sub = if (b != null) legWord(b, "Akku") + " " + f.text("akku").first
            else gridPhrase(f)
            small(v, "Haus verbraucht", f.text("haus").first, sub)
        }
        val today = todayLine(e)
        if (side) {
            // four cells wide: the second line and Heute beside the number, not below it
            v.setViewVisibility(R.id.e_sub, View.GONE)
            v.setViewVisibility(R.id.e_side, View.VISIBLE)
            v.setTextViewText(R.id.e_side1, sub)
            v.setTextViewText(R.id.e_side2, today.orEmpty())
            return v
        }
        if (showToday && today != null) {
            v.setTextViewText(R.id.e_today, today)
            v.setViewVisibility(R.id.e_today, View.VISIBLE)
        }
        return v
    }

    /** The grid now in words: „4 W ins Netz“, „800 W aus dem Netz“, „Netz ruht“. */
    private fun gridPhrase(f: EnergyFluss): String = f.text("netz").let { (n, w) -> if (w.endsWith("Netz")) "$n $w" else "Netz $w" }

    private fun small(v: RemoteViews, title: String, value: String, sub: String) {
        v.setTextViewText(R.id.e_title, title)
        v.setTextViewText(R.id.e_main, value)
        v.setTextViewText(R.id.e_sub, sub)
    }

    private fun buildFull(ctx: Context, e: Energy?, paired: Boolean, onTap: PendingIntent, w: Int, h: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_energy)
        v.setOnClickPendingIntent(R.id.e_root, onTap)
        if (!paired) {
            v.setViewVisibility(R.id.e_root, View.GONE)
            v.setViewVisibility(R.id.e_note, View.VISIBLE)
            v.setTextViewText(R.id.e_note, "In der App koppeln")
            v.setOnClickPendingIntent(R.id.e_note, onTap)
            return v
        }
        // Heute beside the picture only when Homes says it and the cell is wide enough
        val withToday = e != null && hasToday(e) && todayBeside(w)
        v.setViewVisibility(R.id.e_today_block, if (withToday) View.VISIBLE else View.GONE)
        val f = e?.let { EnergyFluss.of(it) }
        val todayW = if (withToday) ctx.resources.getDimension(R.dimen.energy_today_width) / density(ctx) + 8 else 0f
        val gw = (w - 24 - todayW).toInt().coerceAtLeast(80)
        val gh = (h - 16).coerceAtLeast(60)
        v.setImageViewBitmap(R.id.e_flow, EnergyPicture.flow(f, gw, gh, density(ctx)))
        v.setContentDescription(R.id.e_flow, f?.words() ?: "Energie, wird geladen")
        if (!withToday) return v
        val totals = EnergyStyle.orderedTotals(e?.totals.orEmpty())
        // three rows (owner, 2026-11-03: „In den Akku“ goes): erzeugt, ins Netz, Netzbezug
        for (i in TODAY_LABELS.indices) {
            v.setTextViewText(TODAY_LABELS[i], EnergyStyle.TOTAL_LABELS[i])
            val kwh = totals[i]?.today
            v.setTextViewText(TODAY_VALUES[i], EnergyStyle.formatKwh(kwh))
            v.setTextColor(TODAY_VALUES[i], if (kwh != null) EnergyStyle.TOTAL_COLORS[i] else EnergyStyle.MUTED)
        }
        // Autarkie now, as the last line of the figures beside the picture
        val autarkie = f?.autarkie
        v.setViewVisibility(R.id.e_autarkie, if (autarkie != null) View.VISIBLE else View.GONE)
        if (autarkie != null) v.setTextViewText(R.id.e_autarkie_v, EnergyFluss.percent(autarkie * 100))
        return v
    }

    private val TODAY_LABELS = intArrayOf(R.id.e_today_l0, R.id.e_today_l1, R.id.e_today_l2)
    private val TODAY_VALUES = intArrayOf(R.id.e_today_v0, R.id.e_today_v1, R.id.e_today_v2)
}
