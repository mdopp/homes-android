package cloud.dopp.homes

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.ApiClient.Merken
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.data.ToolDefs
import cloud.dopp.homes.einrichtung.Bilder
import cloud.dopp.homes.kann.Anbieter
import cloud.dopp.homes.ui.ShareActivity
import cloud.dopp.homes.ui.StartAngebot
import cloud.dopp.homes.widget.ToolWidgetProvider
import cloud.dopp.homes.widget.WidgetStore
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The owner, 2026-11-27: „Widget für Notizen brauche ich nicht mehr! Widget
 * für Dokumente … Kann ganz weg.“ — neither is offered any more, a placed one
 * fetches nothing and says to remove it, and the tool widget leaves the
 * system's picker while no tool is offered. And „Wenn ich Rezepte mit der App
 * teile …“: a shared recipe says so and opens on Homes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AusgebautRezepteTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()

    /** Homes' catalog as it lists the tools today. */
    private val katalog = """{"ok":true,"kind":"tool","defs":[
          {"tool-id":"contacts","tool-label":"Kontakte","tool-api-path":"/napi/portal/contacts","tool-cell-schema":{"title":"name"}},
          {"tool-id":"doc","tool-label":"Dokumente","tool-api-path":"/napi/portal/documents","tool-cell-schema":{"title":"title"}},
          {"tool-id":"note","tool-label":"Notizen","tool-api-path":"/napi/portal/notes","tool-cell-schema":{"title":"title"}},
          {"tool-id":"task","tool-label":"Erinnerungen","tool-api-path":"/napi/portal/tasks","tool-cell-schema":{"title":"title"}}]}"""

    @Before
    fun gekoppelt() {
        server.start()
        ctx.getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit()
            .putString("base_url", server.url("/").toString().trimEnd('/')).commit()
        TokenStore.backend = ctx.getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx, "homes_device_TESTTOKEN123")
    }

    @After
    fun zu() {
        ctx.getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit().clear().commit()
        TokenStore.clear(ctx)
        runCatching { server.shutdown() }
    }

    // --- the widgets that went ------------------------------------------------------------

    @Test
    fun noToolIsOfferedAnyMore() {
        val defs = ToolDefs.parseCatalog(katalog)
        assertEquals("all four still listable", listOf("contacts", "doc", "note", "task"), defs.filter { it.isListable }.map { it.id })
        assertTrue("none offered", defs.none { it.isOffered })
        assertEquals(setOf("doc", "note"), ToolDefs.AUSGEBAUT)
        assertFalse(StartAngebot.widget("tool:doc"))
        assertFalse(StartAngebot.widget("tool:note"))
        assertTrue("the device widget stays", StartAngebot.widget("geraet"))
    }

    @Test
    fun aPlacedNotesWidgetSaysToRemoveItAndFetchesNothing() {
        val mgr = AppWidgetManager.getInstance(ctx)
        val id = shadowOf(mgr).createWidget(ToolWidgetProvider::class.java, R.layout.widget_tool)
        WidgetStore.bindTool(ctx, id, "note", "Notizen")
        ToolWidgetProvider().onUpdate(ctx, mgr, intArrayOf(id))
        val v = shadowOf(mgr).getViewFor(id)
        assertEquals(
            "Dieses Widget gibt es nicht mehr – lange drücken und entfernen.",
            v.findViewById<TextView>(R.id.we_text).text.toString(),
        )
        Thread.sleep(300)
        assertEquals("no network", 0, server.requestCount)
    }

    @Test
    fun aPlacedDocumentsWidgetToo() {
        val mgr = AppWidgetManager.getInstance(ctx)
        val id = shadowOf(mgr).createWidget(ToolWidgetProvider::class.java, R.layout.widget_tool)
        WidgetStore.bindTool(ctx, id, "doc", "Dokumente")
        ToolWidgetProvider().onUpdate(ctx, mgr, intArrayOf(id))
        assertEquals(
            "Dieses Widget gibt es nicht mehr – lange drücken und entfernen.",
            shadowOf(mgr).getViewFor(id).findViewById<TextView>(R.id.we_text).text.toString(),
        )
        assertEquals(0, server.requestCount)
    }

    private fun state() = ctx.packageManager.getComponentEnabledSetting(ComponentName(ctx, ToolWidgetProvider::class.java))

    @Test
    fun theToolWidgetLeavesThePickerWhileNoneIsOfferedOrPlaced() {
        assertTrue(Anbieter.werkzeug(ctx))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state())
        assertFalse("nothing changes again", Anbieter.werkzeug(ctx))
    }

    @Test
    fun aPlacedToolWidgetKeepsItsProvider() {
        shadowOf(AppWidgetManager.getInstance(ctx)).createWidget(ToolWidgetProvider::class.java, R.layout.widget_tool)
        assertFalse(Anbieter.werkzeug(ctx))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state())
    }

    // --- recipes --------------------------------------------------------------------------

    @Test
    fun theRecipesHaveTheirOwnPicture() {
        assertTrue(Bilder.fuer("rezepte") != Bilder.fuer("unbekannt"))
    }

    private val base get() = server.url("/").toString().trimEnd('/')
    private val host get() = server.url("/").host

    @Test
    fun aSharedRecipeSaysSoAndOpensOnHomes() {
        val r = ApiClient.merkenOutcome(
            200,
            """{"titel":"Kürbissuppe","art":"rezept","kurz":"Rezept gemerkt: Kürbissuppe, 2 Zutaten.","schon":false,"notiz":"https://$host/rezepte/kuerbissuppe"}""",
        ) as Merken.Kept
        val g = ShareActivity.gemerkt(ctx, r, base)
        assertEquals("„Kürbissuppe“ als Rezept gemerkt", g.titel)
        assertEquals("Rezept gemerkt: Kürbissuppe, 2 Zutaten.", g.text)
        assertEquals("https://$host/rezepte/kuerbissuppe", g.link)
        assertEquals("Ansehen", ctx.getString(g.knopf))
    }

    @Test
    fun anyOtherNoteIsSaidAsBefore() {
        val r = ApiClient.merkenOutcome(
            200, """{"titel":"Ein Artikel","art":"link","kurz":"Gemerkt: Ein Artikel.","notiz":"https://$host/notizen/artikel.md"}""",
        ) as Merken.Kept
        val g = ShareActivity.gemerkt(ctx, r, base)
        assertEquals("Gemerkt", g.titel)
        assertEquals("Notiz öffnen", ctx.getString(g.knopf))
    }

    @Test
    fun aLinkOffThePairedHomesIsNotOffered() {
        val r = ApiClient.merkenOutcome(
            200, """{"titel":"Kürbissuppe","art":"rezept","kurz":"x","notiz":"https://evil.example/rezepte/x"}""",
        ) as Merken.Kept
        assertNull(ShareActivity.gemerkt(ctx, r, base).link)
        assertNull("unpaired: no link", ShareActivity.gemerkt(ctx, r, null).link)
    }
}
