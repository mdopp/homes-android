package cloud.dopp.homes.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import cloud.dopp.homes.R
import cloud.dopp.homes.data.Card
import cloud.dopp.homes.widget.DeviceLook

/**
 * „Deine Geräte“ as tiles (owner, 2026-11-04, variant A2 as rendered). Each
 * keeps what a row did: a tap switches, a long press opens the device in
 * Homes, the accented level or „aus“ opens [LevelSheet], a lock and a garage
 * ask first, one that does not answer says so, and offline all are greyed.
 * The widgets' look ([DeviceLook]): the picture in its state's colour says
 * the state, words only where it cannot; the name without the room; no outlines.
 * A phone: one tile to a row, each one line like the rest (owner 2026-11-14:
 * „Die Zeilen sollten alle gleich sein. Diese halben stören das Design.“).
 * Wider (a foldable, a tablet): a grid of equal cells, each tile one line.
 */
class FavoritenAnsicht(
    private val ctx: Context,
    /** The values are the last ones known (the box not reached): grey them, and a tap says no „schaltet …“. */
    private val stale: () -> Boolean,
    private val tap: (Card, switching: () -> Unit) -> Unit,
    private val longPress: (Card) -> Unit,
    private val openLevel: (Card, DeviceControls.Slider, List<DeviceControls.Button>, switching: () -> Unit) -> Unit,
    private val send: (Card, DeviceControls.Button, switching: () -> Unit) -> Unit,
) {
    private val inflater = LayoutInflater.from(ctx)
    private val grey = stale()
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    /** The favourites as tiles in their order: a phone's one-line rows, or wider a grid of equal one-line cells. */
    fun kacheln(group: ViewGroup, cards: List<Card>) {
        val gap = dp(GAP_DP)
        // the window's width: a fold opened or a tablet turned makes the screen anew; slots keep the cells in line
        val n = spalten(ctx.resources.configuration.screenWidthDp - 2 * GUTTER_DP)
        val rows = cards.chunked(n)
        rows.forEachIndexed { i, reihe ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                weightSum = maxOf(n, reihe.size).toFloat()
            }
            reihe.forEach { card ->
                val slot = android.widget.FrameLayout(ctx).apply { setPadding(gap / 2, 0, gap / 2, 0) }
                slot.addView(kachel(slot, card, R.layout.item_device_tile_wide), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                row.addView(slot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
            group.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = -gap / 2
                    marginEnd = -gap / 2
                    if (i > 0) topMargin = gap
                },
            )
        }
    }

    private fun kachel(parent: ViewGroup, card: Card, layout: Int): View {
        val v = inflater.inflate(layout, parent, false)
        v.tag = card.entityId
        val name = DeviceLook.shortName(card.name)
        val colour = if (grey) DeviceLook.OFF else DeviceLook.tint(card.domain, card.deviceClass, card)
        val icon = v.findViewById<ImageView>(R.id.row_icon)
        icon.setImageDrawable(bild(card))
        icon.imageTintList = ColorStateList.valueOf(colour)
        icon.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(colour, DOT_ALPHA))
        v.findViewById<TextView>(R.id.row_name).text = name
        val wordsView = v.findViewById<TextView>(R.id.row_room)
        val levelView = v.findViewById<TextView>(R.id.row_level)
        val words = worte(card)
        wordsView.text = words
        wordsView.visibility = if (words == null) View.GONE else View.VISIBLE
        val switching = {
            if (!stale()) {
                wordsView.text = ctx.getString(R.string.home_device_switching)
                wordsView.visibility = View.VISIBLE
                levelView.visibility = View.GONE
            }
        }
        val level = DeviceControls.of(card).slider
        val stop = DeviceControls.coverButtons(card).filter { it.service == "cover.stop_cover" }
        // the picture's words for a screen reader: „Küchenlicht, an · 60 %“
        val said = listOf(name, StartDevices.stateText(card))
        if (level != null) {
            val says = level.says()
            levelView.text = says
            levelView.visibility = View.VISIBLE
            levelView.contentDescription = ctx.getString(R.string.home_device_level, name, level.label, says)
            levelView.setOnClickListener { openLevel(card, level, stop, switching) }
            reachAtLeast48dp(v, levelView)
        }
        v.contentDescription = said.joinToString(", ")
        v.isClickable = true
        // a cover's tile opens its sheet (its ▲ ■ ▼ are on the tile); everything else switches as before
        v.setOnClickListener {
            if (card.domain == "cover" && level != null) openLevel(card, level, stop, switching) else tap(card, switching)
        }
        v.setOnLongClickListener {
            longPress(card)
            true
        }
        v.backgroundTintList = ColorStateList.valueOf(if (active(colour)) ColorUtils.blendARGB(TILE, colour, WASH) else TILE)
        val buttons = DeviceControls.coverButtons(card).ifEmpty { DeviceControls.of(card).buttons }
        if (buttons.isNotEmpty()) segment(v.findViewById(R.id.row_controls), card, buttons, switching)
        if (grey) v.alpha = STALE_ALPHA
        return v
    }

    /**
     * The widgets' picture of [card] ([DeviceLook.icon]): a lamp lit or dark,
     * a garage door open or shut, a blind up or down — and a blind part-way
     * the same two pictures, the lowered one over the raised one down to its
     * position, so the picture says „part-way“ too.
     */
    private fun bild(card: Card): android.graphics.drawable.Drawable {
        val res = DeviceLook.icon(card.domain, card.deviceClass, card.state)
        val pos = card.position
        if (res != R.drawable.ic_blind_open || pos == null || pos !in 1..99) return AppCompatResources.getDrawable(ctx, res)!!
        val shade = android.graphics.drawable.ClipDrawable(
            AppCompatResources.getDrawable(ctx, R.drawable.ic_blind_closed),
            android.view.Gravity.TOP,
            android.graphics.drawable.ClipDrawable.VERTICAL,
        ).apply { level = (100 - pos) * CLIP_PER_PERCENT }
        return android.graphics.drawable.LayerDrawable(arrayOf(AppCompatResources.getDrawable(ctx, R.drawable.ic_blind_open)!!, shade))
    }

    /**
     * The words a tile keeps: only what its picture and level cannot say — a
     * thing that does not answer, one on its way („öffnet …“, „schließt ab …“),
     * a jammed lock, and what has no picture of its state (a heating's
     * reading, a player).
     */
    private fun worte(card: Card): String? {
        val s = card.state?.trim()?.lowercase(java.util.Locale.ROOT)
        return when {
            s == null || s == "unavailable" || s == "unknown" -> StartDevices.stateText(card)
            s in UNTERWEGS || s == "jammed" -> StartDevices.stateText(card)
            card.domain in BILDLICH -> null
            else -> StartDevices.stateText(card)
        }
    }

    /** The fine buttons as pictures in one quiet pill: ▲ ■ ▼, − +, ❚❚ ▶; each 40 dp wide and 48 dp high. */
    private fun segment(holder: LinearLayout, card: Card, buttons: List<DeviceControls.Button>, switching: () -> Unit) {
        holder.visibility = View.VISIBLE
        holder.background = AppCompatResources.getDrawable(ctx, R.drawable.seg_homes)
        holder.setPadding(0, 0, 0, 0) // the pill's inset is not the buttons' padding: they stay 48 dp high
        buttons.forEach { b ->
            val btn = ImageButton(ctx)
            btn.setImageResource(ICONS[b.label] ?: R.drawable.ic_device)
            btn.imageTintList = ColorStateList.valueOf(ctx.getColor(R.color.homes_text))
            btn.setBackgroundResource(R.drawable.btn_seg_homes)
            btn.scaleType = ImageView.ScaleType.CENTER
            btn.contentDescription = DeviceLook.shortName(card.name) + ": " + b.label
            btn.setOnClickListener { send(card, b, switching) }
            holder.addView(btn, LinearLayout.LayoutParams(dp(SEG_W_DP), dp(TOUCH_DP)))
        }
    }

    /** The level's touch target, at least 48 dp each way, while its text stays the size of the line. */
    private fun reachAtLeast48dp(row: View, level: View) {
        val min = dp(TOUCH_DP)
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (level.visibility != View.VISIBLE || level.width == 0) return@addOnLayoutChangeListener
            val r = android.graphics.Rect(0, 0, level.width, level.height)
            (row as ViewGroup).offsetDescendantRectToMyCoords(level, r)
            r.inset(-((min - r.width()) / 2).coerceAtLeast(0), -((min - r.height()) / 2).coerceAtLeast(0))
            row.touchDelegate = android.view.TouchDelegate(r, level)
        }
    }

    /** On, open or moving: the tile takes a wash of its colour; shut, locked and off stay quiet. */
    private fun active(colour: Int) = !grey && colour != DeviceLook.OFF && colour != DeviceLook.UNKNOWN && colour != DeviceLook.SECURE

    companion object {
        private const val GAP_DP = 8
        private const val GUTTER_DP = 24

        /** A cell's least width: a cover's one line with ▲ ■ ▼, measured whole at 312 dp (the 360 dp phone's). */
        private const val ZELLE_DP = 312

        /** Cells of at least [ZELLE_DP] in [breiteDp] of room; fewer than two is a phone. */
        fun spalten(breiteDp: Int) = maxOf(1, (breiteDp + GAP_DP) / (ZELLE_DP + GAP_DP))

        private const val TOUCH_DP = 48
        private const val SEG_W_DP = 40
        private const val CLIP_PER_PERCENT = 100
        private val UNTERWEGS = setOf("opening", "closing", "locking", "unlocking")
        private val BILDLICH = setOf("light", "switch", "cover", "lock")
        private const val TILE = 0xFF141419.toInt()
        private const val WASH = 0.13f
        private const val DOT_ALPHA = 0x2E
        private const val STALE_ALPHA = 0.55f
        private val ICONS = mapOf(
            "Hoch" to R.drawable.ic_ctl_up,
            "Stopp" to R.drawable.ic_ctl_stop,
            "Runter" to R.drawable.ic_ctl_down,
            "Kälter" to R.drawable.ic_ctl_minus,
            "Wärmer" to R.drawable.ic_ctl_plus,
            "Leiser" to R.drawable.ic_ctl_minus,
            "Lauter" to R.drawable.ic_ctl_plus,
            "Pause" to R.drawable.ic_ctl_pause,
            "Weiter" to R.drawable.ic_ctl_play,
        )
    }
}
