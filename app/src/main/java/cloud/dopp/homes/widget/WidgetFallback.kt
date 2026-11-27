package cloud.dopp.homes.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import cloud.dopp.homes.R
import cloud.dopp.homes.kann.KannStore
import cloud.dopp.homes.kann.Liste
import cloud.dopp.homes.ui.HomeActivity

/**
 * A clean "konnte nicht laden" fallback for any widget provider (#32). When a
 * data fetch or render throws, pushing this instead of letting the exception
 * escape keeps the host from showing Android's own "Widget kann nicht geladen
 * werden". A tap re-runs the provider's refresh so the user can retry.
 */
object WidgetFallback {

    /**
     * Render the error placeholder for [appWidgetId]. Never throws — any failure
     * here is swallowed (the last-good view stays on screen) since this is the
     * last line of defence. [onTap] (when given) re-triggers a refresh.
     */
    fun show(context: Context, appWidgetId: Int, onTap: PendingIntent? = null) {
        try {
            val v = RemoteViews(context.packageName, R.layout.widget_error)
            if (onTap != null) v.setOnClickPendingIntent(R.id.we_root, onTap)
            AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, v)
        } catch (t: Throwable) {
            // Nothing more we can safely do — leave the previous render in place.
        }
    }

    /**
     * A placed widget whose type [key] the box no longer offers (homes ADR 0001
     * §3): the module's reason, quiet, instead of an error; a tap opens the
     * app, where its trace offers „Hier anfordern“. False — nothing drawn —
     * while the box offers it or what the box can do is unknown.
     */
    fun weg(context: Context, appWidgetId: Int, key: String): Boolean {
        val kann = KannStore.kann(context) ?: return false
        if (kann.hat(Liste.WIDGETS, key)) return false
        zeigeWeg(context, appWidgetId, kann.spurVon(Liste.WIDGETS, key)?.grund ?: context.getString(R.string.spur_widget_weg))
        return true
    }

    /**
     * A widget the app took out (owner 2026-11-27: Notizen, Dokumente): the app cannot take it off the home
     * screen, so it says how, quietly, and fetches nothing.
     */
    fun ausgebaut(context: Context, appWidgetId: Int) = zeigeWeg(context, appWidgetId, context.getString(R.string.widget_ausgebaut))

    private fun zeigeWeg(context: Context, appWidgetId: Int, grund: String) {
        runCatching {
            val v = RemoteViews(context.packageName, R.layout.widget_error)
            v.setViewVisibility(R.id.we_icon, View.GONE)
            v.setTextViewText(R.id.we_text, grund)
            val open = PendingIntent.getActivity(
                context, WEG_CODE + appWidgetId, Intent(context, HomeActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.we_root, open)
            AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, v)
        }
    }

    private const val WEG_CODE = 700_000
}
