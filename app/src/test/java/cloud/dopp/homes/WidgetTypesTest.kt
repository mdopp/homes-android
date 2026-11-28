package cloud.dopp.homes

import cloud.dopp.homes.widget.DeviceWidgetProvider
import cloud.dopp.homes.widget.WidgetTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget-type table (#34) lists every pinnable provider, so the app's
 * screen offers them all in one list (owner, 2026-11-03): one device; the
 * tool widget comes once per tool. No Box widgets, and no energy widget
 * (owner 2026-11-28: a tile on the start screen instead).
 * Pure JVM coverage: no Android context needed.
 */
class WidgetTypesTest {

    @Test
    fun oneDevice() {
        assertEquals(
            listOf<Class<*>>(DeviceWidgetProvider::class.java),
            WidgetTypes.ALL.map { it.provider },
        )
    }

    /** Every widget the manifest declares is offered — the tool widget once per tool, elsewhere. */
    @Test
    fun tableMatchesTheManifest() {
        val manifest = java.io.File(RepoFiles.main, "AndroidManifest.xml").readText()
        val declared = Regex("""<receiver\s+android:name="\.widget\.(\w+WidgetProvider)"""").findAll(manifest)
            .map { it.groupValues[1] }.toSet() - "ToolWidgetProvider" - "EnergyWidgetProvider" // the energy one only for placed ones
        assertEquals(declared, WidgetTypes.ALL.map { it.provider.simpleName }.toSet())
    }

    @Test
    fun everyEntryHasLabelAndIcon() {
        assertTrue(WidgetTypes.ALL.isNotEmpty())
        WidgetTypes.ALL.forEach {
            assertTrue("label res id set", it.label != 0)
            assertTrue("icon res id set", it.icon != 0)
        }
    }
}
