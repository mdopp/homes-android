package cloud.dopp.homes.widget

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import cloud.dopp.homes.R
import cloud.dopp.homes.data.Napi.KannKey

/**
 * The typed table of pinnable widget types (#34): one entry per home-screen
 * widget provider with a fixed purpose, so the app's screen can offer every one
 * of them and pin the picked provider via `requestPinAppWidget`. The tool widget
 * is not here: it is offered once per tool in Homes' catalog. The app shows
 * them all in one list (owner, 2026-11-03): one device, the tools (the energy
 * widget went, owner 2026-11-28: the start screen's „Energie“ tile instead).
 *
 * Pure (label/icon are resource ids, provider is a class reference) → the table
 * itself is unit-testable on the JVM.
 */
object WidgetTypes {

    data class Entry(
        val provider: Class<*>,
        /** Its key in `/napi/kann`'s `widgets` (homes ADR 0001 §2). */
        val key: String,
        @StringRes val label: Int,
        @DrawableRes val icon: Int,
    )

    /** Ordered as shown. */
    val ALL: List<Entry> = listOf(
        Entry(DeviceWidgetProvider::class.java, KannKey.GERAET, R.string.widget_type_device, R.drawable.ic_device),
    )
}
