package cloud.dopp.homes

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.ui.HomeActivity
import cloud.dopp.homes.widget.HomesPages
import cloud.dopp.homes.widget.ToolActionActivity
import cloud.dopp.homes.widget.WidgetKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * A widget tap tells Homes which widget it came from (#180): the page it opens
 * carries `von=widget.<kind>` exactly once, so Homes counts it server-side. The
 * app counts nothing and makes no extra call. The old app did the same with
 * `?src=widget.*`; here the parameter is `von`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetVonTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun unpair() = ServerStore.clear(ctx)

    private fun vons(url: String): List<String> =
        url.substringAfter('?', "").split('&').filter { it.startsWith("von=") }

    /** The kinds, as Homes reads them: lower-case, `[a-z0-9._-]`, one per widget that opens a page. */
    @Test
    fun theKinds() {
        assertEquals(
            listOf("device", "energy", "tool"),
            WidgetKind.values().map { it.kind },
        )
        WidgetKind.values().forEach {
            assertTrue(it.kind, Regex("[a-z0-9][a-z0-9._-]*").matches(it.kind))
            assertEquals("widget.${it.kind}", it.von)
        }
    }

    @Test
    fun aPageWithoutAQueryGetsOne() {
        assertEquals(
            "https://homes.example/start?von=widget.tool",
            HomesPages.url("https://homes.example/", HomesPages.START, WidgetKind.TOOL),
        )
    }

    @Test
    fun aPageWithAQueryGetsItAppended() {
        assertEquals(
            "https://homes.example/raeume/light.flur?tab=1&von=widget.tool",
            HomesPages.url("https://homes.example", "/raeume/light.flur?tab=1", WidgetKind.TOOL),
        )
    }

    /** A page Homes named with a `von` of its own still carries exactly one, the widget's. */
    @Test
    fun exactlyOnceEvenWhenThePageNamedOne() {
        val url = HomesPages.url("https://homes.example", "/raeume/x?von=widget.other&a=b", WidgetKind.TOOL)
        assertEquals(listOf("von=widget.tool"), vons(url))
        assertTrue(url, url.contains("a=b"))
    }

    /** Not from a widget (a launcher shortcut, the app), the page is as it was. */
    @Test
    fun noKindNoParameter() {
        assertEquals("https://homes.example/energie", HomesPages.url("https://homes.example", HomesPages.ENERGIE, null))
    }

    /** Every kind, through the PendingIntent a widget hands the launcher. */
    @Test
    fun everyKindsTapCarriesItExactlyOnce() {
        ServerStore.setBaseUrl(ctx, "https://homes.example")
        WidgetKind.values().forEachIndexed { n, kind ->
            for (page in listOf(HomesPages.START, "/raeume/light.flur?tab=1")) {
                val saved = shadowOf(HomesPages.tap(ctx, 9_000 + n * 2 + page.length, page, kind)).savedIntent
                assertEquals(Intent.ACTION_VIEW, saved.action)
                assertEquals("$kind on $page", listOf("von=widget.${kind.kind}"), vons(saved.data.toString()))
            }
        }
    }

    @Test
    fun unpairedATapStillOpensTheApp() {
        val saved = shadowOf(HomesPages.tap(ctx, 9_500, HomesPages.START, WidgetKind.TOOL)).savedIntent
        assertEquals(HomeActivity::class.java.name, saved.component?.className)
    }

    /** A row of the tool widget opens its page through ToolActionActivity. */
    @Test
    fun aRowsTemplateCarriesItsWidgetsKind() {
        val saved = shadowOf(ToolActionActivity.template(ctx, 9_600, WidgetKind.TOOL)).savedIntent
        assertEquals("tool", saved.getStringExtra(ToolActionActivity.EXTRA_VON))
    }

    @Test
    fun aRowOpensItsPageWithTheKind() {
        ServerStore.setBaseUrl(ctx, "https://homes.example")
        val i = Intent(ctx, ToolActionActivity::class.java)
            .putExtra(ToolActionActivity.EXTRA_PAGE, "/raeume/light.flur")
            .putExtra(ToolActionActivity.EXTRA_VON, "tool")
        val activity = Robolectric.buildActivity(ToolActionActivity::class.java, i).create().get()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals("https://homes.example/raeume/light.flur?von=widget.tool", started.data.toString())
    }

    /** An extra that is not one of the kinds is dropped, never passed through to Homes. */
    @Test
    fun anUnknownKindIsDropped() {
        ServerStore.setBaseUrl(ctx, "https://homes.example")
        val i = Intent(ctx, ToolActionActivity::class.java)
            .putExtra(ToolActionActivity.EXTRA_PAGE, "/start")
            .putExtra(ToolActionActivity.EXTRA_VON, "tool&x=1")
        val activity = Robolectric.buildActivity(ToolActionActivity::class.java, i).create().get()
        assertEquals("https://homes.example/start", shadowOf(activity).nextStartedActivity.data.toString())
        assertNull(WidgetKind.of("tool&x=1"))
    }

    /** Each provider names its own kind on its page taps; none opens a page without one. */
    @Test
    fun eachProviderTapsWithItsOwnKind() {
        val widget = File(RepoFiles.main, "java/cloud/dopp/homes/widget")
        val expected = mapOf(
            "DeviceWidgetProvider.kt" to WidgetKind.DEVICE,
            "ToolWidgetProvider.kt" to WidgetKind.TOOL,
        )
        val call = Regex("""(HomesPages\.tap|ToolActionActivity\.template)\(([^\n]*)\)""")
        expected.forEach { (file, kind) ->
            val calls = call.findAll(File(widget, file).readText()).toList()
            assertTrue("$file opens no page", calls.isNotEmpty())
            calls.forEach { c ->
                assertTrue("$file: ${c.value} does not name WidgetKind.${kind.name}", c.value.contains("WidgetKind.${kind.name})"))
            }
        }
    }
}
