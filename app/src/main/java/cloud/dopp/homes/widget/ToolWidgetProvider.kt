package cloud.dopp.homes.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.Napi.KannKey
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.data.ToolDef
import cloud.dopp.homes.data.ToolDefs

/**
 * The **generic schema-driven tool widget** (#70, archetype A of #69): ONE
 * provider that renders **any** `.tool` as a list/status card, with no per-tool
 * native code anywhere in the app.
 *
 * Each instance is bound to a `tool-id` by [ToolWidgetConfigActivity]. On refresh
 * it re-reads the catalog (`/napi/defs/tool`), finds its tool, fetches that tool's
 * declared `tool-api-path` (a `/napi/…` path, see [ToolDefs.napiPath]) and
 * maps the items through the tool's `tool-cell-schema` with [ToolCells] — so the
 * server, not the app, decides which field is the title and which are the details.
 * A new `.tool` plugin therefore becomes a working widget without an app rebuild.
 *
 * Reuses the established plumbing: the #28 collection ListView, goAsync fetching,
 * the #46 last-good cache (a failed refresh keeps the previous rows) and the #32
 * [WidgetFallback] so a render failure never becomes "Widget kann nicht geladen
 * werden". A row tap offers the row's actions, wired from the catalog's
 * `tool-action-params` (#90).
 */
class ToolWidgetProvider : FedWidgetProvider(ACTION_REFRESH, 400_000, tapStartsLive = true) {

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetStore.unbindTool(context, it) }
        // The live service feeds this widget: with the last one gone it may stop.
        cloud.dopp.homes.realtime.Live.recheck(context)
    }

    override fun refresh(context: Context, appWidgetId: Int) {
        val toolId = WidgetStore.toolId(context, appWidgetId)
        if (toolId in ToolDefs.AUSGEBAUT) return WidgetFallback.ausgebaut(context, appWidgetId) // taken out: no network
        if (toolId != null && WidgetFallback.weg(context, appWidgetId, KannKey.TOOL + toolId)) return // the box lost the tool
        val mgr = AppWidgetManager.getInstance(context)
        val cached = ToolCells.decode(WidgetStore.toolCellsJson(context, appWidgetId))
        val label = WidgetStore.toolLabel(context, appWidgetId)

        // Draw the last-good rows immediately (stale-then-fresh), then re-fetch.
        try {
            mgr.updateAppWidget(appWidgetId, scaffold(context, appWidgetId, label, cached.size))
        } catch (t: Throwable) {
            WidgetFallback.show(context, appWidgetId, refreshPending(context, appWidgetId))
            return
        }

        val paired = ServerStore.isConfigured(context) && TokenStore.isPaired(context)
        if (toolId == null || !paired) return

        async(context, appWidgetId) {
            val api = ApiClient(context.applicationContext)
            val def = resolveDef(context, api, toolId)
            val cells = def?.let { ToolCells.mapAll(it, api.fetchToolRows(it)) }
            // Only a successful fetch replaces the cache — a hiccup leaves the
            // previous rows on screen instead of blanking the widget (#46).
            cells?.let { WidgetStore.setToolCellsJson(context, appWidgetId, ToolCells.encode(it)) }
            val shown = cells ?: cached
            // Follow a renamed tool: the catalog's label wins over the one the
            // config screen stored when the widget was placed.
            val header = def?.label?.ifBlank { null } ?: label
            if (header != label) WidgetStore.setToolLabel(context, appWidgetId, header)
            mgr.updateAppWidget(appWidgetId, scaffold(context, appWidgetId, header, shown.size))
            mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.tw_list)
        }
    }

    /**
     * The tool's current definition: fresh from the catalog when reachable (so a
     * changed schema takes effect without a rebuild), else from the cached catalog
     * body so an offline widget still renders its rows.
     */
    private fun resolveDef(context: Context, api: ApiClient, toolId: String): ToolDef? =
        WidgetStore.freshToolCatalog(context, api)?.firstOrNull { it.id == toolId }
            ?: WidgetStore.keptToolCatalog(context).firstOrNull { it.id == toolId }

    /**
     * Header + the collection wiring. The ListView is bound to
     * [ToolRemoteViewsService] (unique data Uri per instance so two tool widgets
     * don't share an adapter) and each row's fill-in intent completes the template.
     */
    private fun scaffold(
        context: Context, appWidgetId: Int, label: String, count: Int,
    ): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_tool)
        val bound = WidgetStore.toolId(context, appWidgetId) != null
        val title = label.ifBlank { context.getString(R.string.tool_widget_label) }
        v.setTextViewText(
            R.id.tw_title,
            if (count > 0) context.getString(R.string.tool_title_count, title, count) else title,
        )
        v.setViewVisibility(R.id.tw_empty, View.GONE)
        v.setTextViewText(
            R.id.tw_empty,
            context.getString(if (bound) R.string.tool_empty else R.string.tool_unbound),
        )

        val svc = Intent(context, ToolRemoteViewsService::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .setData(Uri.parse("homes://tool/$appWidgetId"))
        v.setRemoteAdapter(R.id.tw_list, svc)
        v.setEmptyView(R.id.tw_list, R.id.tw_empty)
        v.setPendingIntentTemplate(R.id.tw_list, ToolActionActivity.template(context, 410_000 + appWidgetId, WidgetKind.TOOL))

        // Where a body tap goes (#105): bound → Homes' start page, unbound → this
        // instance's picker. A widget that says "Tool wählen" must let the
        // tap *do* that (#104) — it used to
        // open the chat instead, which is the one thing the user did not ask for.
        // Armed on the header/empty children too, since a launcher does not reliably
        // deliver a click on the RemoteViews root (#104 again).
        val tap = if (bound) {
            HomesPages.tap(context, appWidgetId, HomesPages.START, WidgetKind.TOOL)
        } else {
            configPending(context, appWidgetId)
        }
        for (id in BODY_TAP_TARGETS) v.setOnClickPendingIntent(id, tap)
        v.setOnClickPendingIntent(R.id.tw_refresh, refreshPending(context, appWidgetId))
        return v
    }

    /**
     * Unbound instances route their tap to their own config activity — the way out
     * of the empty state, for a widget pinned from the launcher's tray as much as
     * one from the in-app offer.
     */
    private fun configPending(context: Context, appWidgetId: Int): PendingIntent {
        val i = Intent(context, ToolWidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, 420_000 + appWidgetId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH = "cloud.dopp.homes.widget.TOOL_REFRESH"

        /**
         * Every view the body tap is armed on — the root plus the two children that
         * cover the card while it has no rows. Public so the guard test enumerates
         * this list instead of re-listing ids of its own.
         */
        val BODY_TAP_TARGETS = listOf(R.id.tw_root, R.id.tw_title, R.id.tw_empty)

        /** Re-run one instance (used by the config screen after binding). */
        fun requestRefresh(context: Context, appWidgetId: Int) {
            context.sendBroadcast(refreshIntent(context, ToolWidgetProvider::class.java, ACTION_REFRESH, appWidgetId))
        }
    }
}
