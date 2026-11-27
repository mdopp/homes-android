package cloud.dopp.homes

import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.ApiClient.Merken
import cloud.dopp.homes.ui.ShareActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Share → Homes (homes#120): what the share sheet sends becomes one text, and
 * every answer of `POST /napi/merken` ends in something to say.
 *
 * Robolectric only for `org.json`; the functions under test are Android-free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareToHomesTest {

    @Test
    fun theSharedTextIsTitleAndLink() {
        assertEquals("Kürbissuppe https://x.de/r", ShareActivity.sharedText("Kürbissuppe", "https://x.de/r"))
        assertEquals("https://x.de/r", ShareActivity.sharedText(null, " https://x.de/r "))
        assertEquals("https://x.de/r", ShareActivity.sharedText("https://x.de/r", "https://x.de/r"))
        assertNull(ShareActivity.sharedText(" ", null))
    }

    @Test
    fun keptSaysHomesLineAndTheNote() {
        val r = ApiClient.merkenOutcome(200, """{"titel":"Kürbissuppe","art":"rezept","kurz":"Rezept gemerkt: Kürbissuppe, 2 Zutaten, 1 Schritt.","schon":false,"teilweise":false,"notiz":"https://homes.box/notizen/kuerbissuppe.md"}""")
        assertEquals(
            Merken.Kept("Rezept gemerkt: Kürbissuppe, 2 Zutaten, 1 Schritt.", "https://homes.box/notizen/kuerbissuppe.md", false, "rezept", "Kürbissuppe"),
            r,
        )
        val again = ApiClient.merkenOutcome(200, """{"kurz":"Das ist schon gemerkt: „X“.","schon":true,"notiz":""}""")
        assertEquals(Merken.Kept("Das ist schon gemerkt: „X“.", null, true), again)
        // a note address that is not https is no link to open
        assertEquals(null, (ApiClient.merkenOutcome(200, """{"kurz":"x","notiz":"javascript:alert(1)"}""") as Merken.Kept).note)
    }

    @Test
    fun refusedSaysHomesSentence() {
        assertEquals(Merken.Refused("Im Geteilten ist kein Link. Teile eine Webseite aus dem Browser."),
            ApiClient.merkenOutcome(400, """{"error":"Im Geteilten ist kein Link. Teile eine Webseite aus dem Browser."}"""))
        assertEquals(Merken.NotPaired, ApiClient.merkenOutcome(401, """{"error":"unauthorized"}"""))
        assertTrue(ApiClient.merkenOutcome(404, "404 page not found") is Merken.Refused)
        assertTrue((ApiClient.merkenOutcome(502, null) as Merken.Refused).sentence.contains("502"))
    }

    @Test
    fun theShareSheetOffersHomesForText() {
        val manifest = File(RepoFiles.main, "AndroidManifest.xml").readText()
        val activity = manifest.substringAfter("""android:name=".ui.ShareActivity"""").substringBefore("/>")
        assertTrue(activity, activity.contains("""android:taskAffinity="""""))
        // the text target is its own component, the alias `merken` (homes ADR 0001 §3)
        val alias = manifest.substringAfter("""android:name=".ui.TeilenMerken"""").substringBefore("</activity-alias>")
        assertTrue(alias, alias.contains("""android:targetActivity=".ui.ShareActivity""""))
        assertTrue(alias, alias.contains("""android.intent.action.SEND"""))
        assertTrue(alias, alias.contains("""android:mimeType="text/plain""""))
    }

    /**
     * The owner saw the dialog's text right on the shared page: the window is
     * dimmed and the answer is a card — the app's one dialog (owner,
     * 2026-11-03: every confirmation looks alike), no bare AlertDialog.
     */
    @Test
    fun theDialogIsTheAppsOneDialog() {
        val src = File(RepoFiles.root, "app/src/main/java/cloud/dopp/homes/ui/ShareActivity.kt").readText()
        assertTrue(src.contains("ActionDialog.scrim(this)") && src.contains("ActionDialog.show("))
        assertTrue("a bare AlertDialog.Builder", !src.contains("AlertDialog.Builder"))
    }
}
