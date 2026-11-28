package cloud.dopp.homes.kann

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import cloud.dopp.homes.data.Napi.KannKey
import cloud.dopp.homes.widget.DeviceWidgetProvider
import cloud.dopp.homes.widget.EnergyWidgetProvider
import cloud.dopp.homes.widget.ToolWidgetProvider
import cloud.dopp.homes.widget.WidgetStore

/**
 * The device widget in the system's widget picker follows the box
 * (homes ADR 0001 §3): a provider is disabled while the box lacks its key
 * *and* none of its widgets is placed — disabling it would take a placed one
 * off the home screen, so a placed one keeps it, showing the module's reason
 * ([cloud.dopp.homes.widget.WidgetFallback.weg]). With what the box can do
 * unknown, both are enabled, as before. The tool widget ([werkzeug]) is
 * offered while the kept catalog has a tool the app offers — none since
 * Notizen and Dokumente went (owner 2026-11-27) — and stays while one of its
 * widgets is placed.
 */
object Anbieter {

    /**
     * Switch the device provider to what [kann] offers; the energy provider (taken out, owner 2026-11-28) is
     * never offered and stays only while one is placed. True when one changed.
     */
    fun schalten(ctx: Context, kann: Kann?): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx)
        val pm = ctx.packageManager
        val geraet = folgen(ctx, mgr, pm, DeviceWidgetProvider::class.java, kann.hat(Liste.WIDGETS, KannKey.GERAET))
        val energie = folgen(ctx, mgr, pm, EnergyWidgetProvider::class.java, false)
        return geraet || energie
    }

    /** The tool widget in the system's picker: only while a tool is offered, or one of its widgets is placed. */
    fun werkzeug(ctx: Context): Boolean = folgen(
        ctx, AppWidgetManager.getInstance(ctx), ctx.packageManager, ToolWidgetProvider::class.java,
        WidgetStore.keptToolCatalog(ctx).any { it.isOffered },
    )

    /** [cls] enabled while [angeboten] or placed, else disabled; true when it changed. */
    private fun folgen(ctx: Context, mgr: AppWidgetManager, pm: PackageManager, cls: Class<*>, angeboten: Boolean): Boolean {
        val c = ComponentName(ctx, cls)
        // not readable: as if placed, never disabled on a guess
        val placed = runCatching { mgr.getAppWidgetIds(c).isNotEmpty() }.getOrDefault(true)
        val want = if (angeboten || placed) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (pm.getComponentEnabledSetting(c) == want) return false
        pm.setComponentEnabledSetting(c, want, PackageManager.DONT_KILL_APP)
        return true
    }
}
