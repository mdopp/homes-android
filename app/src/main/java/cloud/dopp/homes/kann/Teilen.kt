package cloud.dopp.homes.kann

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import cloud.dopp.homes.data.Napi.KannKey

/**
 * The share sheet's targets follow the box (homes ADR 0001 §3): one
 * component per target — the two aliases of the share activity and the
 * history import — enabled while `teilen` names its key, disabled while the
 * box lacks it. Android's share sheet cannot show a disabled target, so its
 * trace is its module's line in Mehr (§10). With what the box can do
 * unknown, every target is enabled, as before. Switching keeps the app
 * running (DONT_KILL_APP), and an app update keeps the states.
 */
object Teilen {

    /** Each target's component, by its key in `teilen`. */
    private val ZIELE = linkedMapOf(
        KannKey.MERKEN to "cloud.dopp.homes.ui.TeilenMerken",
        KannKey.DOKUMENT to "cloud.dopp.homes.ui.TeilenDokument",
        KannKey.STANDORT_IMPORT to "cloud.dopp.homes.ui.StandortImportActivity",
        KannKey.MUSIK to "cloud.dopp.homes.hochladen.MusikTeilenActivity",
        KannKey.ARCHIV to "cloud.dopp.homes.archiv.ArchivTeilenActivity",
    )

    /** Switch every target to what [kann] offers; true when one changed. */
    fun schalten(ctx: Context, kann: Kann?): Boolean {
        val pm = ctx.packageManager
        var changed = false
        ZIELE.forEach { (key, cls) ->
            val c = ComponentName(ctx.packageName, cls)
            val want = if (kann.hat(Liste.TEILEN, key)) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            if (pm.getComponentEnabledSetting(c) != want) {
                pm.setComponentEnabledSetting(c, want, PackageManager.DONT_KILL_APP)
                changed = true
            }
        }
        return changed
    }

    /** Is the target of [key] enabled now? */
    fun an(ctx: Context, key: String): Boolean {
        val cls = ZIELE[key] ?: return false
        return ctx.packageManager.getComponentEnabledSetting(ComponentName(ctx.packageName, cls)) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
}
