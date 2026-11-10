package cloud.dopp.homes.widget

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.net.toUri
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.Device
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.einrichtung.EinrichtungStore
import cloud.dopp.homes.einrichtung.Kurz
import cloud.dopp.homes.einrichtung.Verknuepfung
import cloud.dopp.homes.einrichtung.VerknuepfungActivity
import cloud.dopp.homes.einrichtung.Verknuepfungen
import cloud.dopp.homes.kann.KannStore
import cloud.dopp.homes.ui.StartDevices
import kotlin.concurrent.thread

/**
 * Publishes the app-icon long-press menu (owner, 2026-11-04): Garagentor,
 * Einkaufsliste, Zeitleiste and Dokument scannen ([Verknuepfungen], pure and
 * JVM-tested), then „Dokumente-Adresse kopieren“ ([DokumenteAdresse], owner
 * 2026-11-10), each only where the box has it, as **dynamic** shortcuts —
 * built from Homes' answer, so a missing target hides its shortcut, and each
 * one pinnable to the home screen like any shortcut.
 *
 * `res/xml/shortcuts.xml` holds only the share target of the assistant's
 * sharing shortcut ([ShareTarget], #188), which is published here last and
 * takes one of the launcher's slots.
 *
 * Everything is best-effort: a launcher that rejects the publish (or one of the
 * OEM launchers that supports no shortcuts at all) must never take the app down
 * with it.
 */
object AppShortcuts {

    /**
     * Republish the menu: when the app's screen becomes visible, so a
     * restore/reinstall is picked up too, when the assistant's name changes,
     * and when Homes' modules do. Off the caller's thread: it touches
     * SharedPreferences and the launcher.
     */
    fun refreshAsync(ctx: Context) {
        val app = ctx.applicationContext
        thread { runCatching { refresh(app) } }
    }

    /** Synchronous publish — [refreshAsync] is the normal entry point. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        // Ask, never assume: launchers commonly show four, but the allowed number
        // is the launcher's to state (0 below API 25 → nothing is published).
        val cap = ShortcutManagerCompat.getMaxShortcutCountPerActivity(app)
        val share = if (cap > 0) runCatching { ShareTarget.info(app, rank = cap - 1) }.getOrNull() else null
        // Android keeps a long-lived shortcut for the share sheet after it left the
        // dynamic set: drop ours when there is no target, and any older share id
        // an earlier version published (it would stay in the sheet beside this one).
        runCatching {
            val stale = ShortcutManagerCompat.getShortcuts(app, ShortcutManagerCompat.FLAG_MATCH_CACHED)
                .map { it.id }
                .filter { it.startsWith(ShareTarget.ID_PREFIX) && (share == null || it != ShareTarget.ID) }
            val gone = if (share == null) (stale + ShareTarget.ID).distinct() else stale
            if (gone.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(app, gone)
        }
        val paired = ServerStore.isConfigured(app) && TokenStore.isPaired(app)
        val entries = if (!paired) emptyList() else Verknuepfungen.liste(EinrichtungStore.einrichtung(app)?.ziele.orEmpty(), garage(app))
        // „Dokumente-Adresse kopieren“ after the four, before the share target (owner 2026-11-10)
        val frei = maxOf(0, if (share == null) cap else cap - 1)
        val adresse = KannStore.kann(app)?.dokumenteAdresse?.takeIf { paired && frei > 0 && DokumenteAdresse.kopie(it) != null }
        val vier = entries.take(if (adresse == null) frei else frei - 1).mapIndexed { i, v -> info(app, v, i) }
        val infos = vier + listOfNotNull(adresse?.let { DokumenteAdresse.shortcut(app, it, vier.size) }, share)
        if (infos.isEmpty()) {
            runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(app) }
            return
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(app, infos) }
    }

    /** The garage among the person's favourites, else among the household's devices. */
    private fun garage(ctx: Context): ShortcutDevice? =
        Verknuepfungen.garage(StartDevices.load(ctx).orEmpty().map { ShortcutDevice(it.entityId, it.name, it.domain, it.deviceClass) })
            ?: Verknuepfungen.garage(catalogDevices(ctx))

    /**
     * The household catalogue: the cache while it is fresh, otherwise refetched
     * and remembered, and the (stale) cache again when the fetch fails.
     */
    private fun catalogDevices(ctx: Context): List<ShortcutDevice> {
        val cached = WidgetStore.shortcutCatalog(ctx)
        if (WidgetStore.shortcutCatalogFresh(ctx)) return cached
        val fresh = runCatching { catalogOf(ApiClient(ctx).listAddable()) }.getOrNull()
        if (fresh.isNullOrEmpty()) return cached
        WidgetStore.setShortcutCatalog(ctx, fresh)
        return fresh
    }

    /** The household's devices as shortcut candidates. */
    fun catalogOf(devices: List<Device>): List<ShortcutDevice> =
        devices.map { ShortcutDevice(it.entityId, it.name, it.domain, it.deviceClass) }

    private fun info(ctx: Context, v: Verknuepfung, rank: Int): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(ctx, Verknuepfungen.PREFIX + v.art.id)
            .setShortLabel(ctx.getString(v.art.kurz))
            .setLongLabel(ctx.getString(v.art.lang))
            // its own opaque field, not a white glyph the launcher would turn into a disc (#99)
            .setIcon(IconCompat.createWithResource(ctx, v.art.bild))
            .setRank(rank)
            .setIntent(intentFor(ctx, v))
            .build()

    /**
     * The intent behind one entry. **No shortcut carries a service**: the
     * garage's asks [WidgetActionReceiver] for the device's own tap — Homes'
     * 403 puts the confirm dialog before an open, as on the start screen —
     * and the others land on [VerknuepfungActivity], which opens the address
     * in a Custom Tab or the camera.
     */
    fun intentFor(ctx: Context, v: Verknuepfung): Intent = when (v.art) {
        Kurz.GARAGE -> Intent(ctx, WidgetActionActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(WidgetActionReceiver.EXTRA_ENTITY, v.ziel)
            .putExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, true)
            .putExtra(WidgetActionActivity.EXTRA_NAME, v.name) // for wording only
        Kurz.SCANNEN -> Intent(ctx, VerknuepfungActivity::class.java).setAction(VerknuepfungActivity.SCANNEN)
        Kurz.EINKAUF, Kurz.ZEITLEISTE -> Intent(ctx, VerknuepfungActivity::class.java).setAction(Intent.ACTION_VIEW).setData(v.ziel.toUri())
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
}
