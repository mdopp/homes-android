package cloud.dopp.homes.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.ui.HomeActivity

/**
 * Where a widget's body tap goes: one of Homes' own pages, in the person's
 * browser — where their Authelia session is (laib ADR 0022: the app has no web
 * shell of its own; Homes' pages run in any browser). A tool row opens the
 * item's own page when Homes names one ([item]).
 *
 * The pages are Homes' (`cmd/homes/main.go`); `NapiContractTest` holds the app
 * to them. Unpaired, a tap opens the app instead, which asks for Homes' address.
 */
object HomesPages {

    /** The house: rooms, devices, energy. */
    const val START = "/start"

    /** Every device, by room: the start screen's „Alle Geräte“. */
    const val RAEUME = "/raeume"

    /** The house's power: the energy widget's tap. */
    const val ENERGIE = "/energie"

    /** Standortverlauf: where the person connects their Reitti once. */
    const val STANDORT = "/standort"

    /** Daten sichern: the way to the keys (Homes' internal/pages/nachrichten.go). */
    const val DATEN = "/daten-sichern"

    /** Meine Daten: every source with its last arrival (homes#342, Homes' internal/pages/meine_daten.go). */
    const val MEINE_DATEN = "/meine-daten"

    /** [page] of [base]; from a widget it carries `von=widget.<kind>` exactly once (#180), so Homes counts the tap. */
    fun url(base: String, page: String, from: WidgetKind? = null): String {
        val full = base.trimEnd('/') + page
        if (from == null) return full
        val fragment = full.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }
        val noFragment = full.substringBefore('#')
        val query = noFragment.substringAfter('?', "").split('&').filter { it.isNotEmpty() && !it.startsWith("von=") }
        return noFragment.substringBefore('?') + "?" + (query + "von=${from.von}").joinToString("&") + fragment
    }

    /**
     * [raw] when it is a page on Homes: a path from its root, as Homes names an
     * item's own page (`page`, `/raeume/<entity_id>`). An absolute or
     * protocol-relative URL, a backslash, `..` or whitespace inside is null, so
     * a reply can never send a tap off Homes.
     */
    fun page(raw: String?): String? = raw?.trim()?.takeIf { p ->
        p.startsWith("/") && !p.startsWith("//") && ".." !in p &&
            p.none { it == '\\' || it.isWhitespace() || it.isISOControl() }
    }

    /** An item's tap: its own page when Homes named one, else [START]. */
    fun item(raw: String?): String = page(raw) ?: START

    /** The intent that shows [page] of the paired Homes, or the app when there is none. */
    fun intent(ctx: Context, page: String, from: WidgetKind? = null): Intent {
        val base = ServerStore.baseUrl(ctx)
        val i = if (base.isNullOrBlank()) {
            Intent(ctx.applicationContext, HomeActivity::class.java).setAction(Intent.ACTION_MAIN)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url(base, page, from))).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** A widget tap that opens [page]. Resolved when the widget draws, so a new pairing shows on the next draw. */
    fun tap(ctx: Context, reqCode: Int, page: String, from: WidgetKind): PendingIntent =
        PendingIntent.getActivity(
            ctx, reqCode, intent(ctx, page, from),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/**
 * The widgets that open a Homes page, by the name Homes counts them under
 * (`von=widget.<kind>`, #180). The old app did the same with `?src=widget.*`
 * before; the app itself counts nothing.
 */
enum class WidgetKind(val kind: String) {
    DEVICE("device"),
    ENERGY("energy"),
    TOOL("tool"),
    ;

    val von: String get() = "widget.$kind"

    companion object {
        /** The kind an intent extra names, or null for anything else: nothing foreign reaches Homes' URL. */
        fun of(raw: String?): WidgetKind? = values().firstOrNull { it.kind == raw }
    }
}
