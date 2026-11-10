package cloud.dopp.homes

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutManagerCompat
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.einrichtung.EinrichtungStore
import cloud.dopp.homes.einrichtung.Verknuepfungen
import cloud.dopp.homes.kann.Kann
import cloud.dopp.homes.kann.KannStore
import cloud.dopp.homes.widget.AppShortcuts
import cloud.dopp.homes.widget.DokumenteAdresse
import cloud.dopp.homes.widget.DokumenteAdresseActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * „Dokumente-Adresse kopieren“ (owner 2026-11-10): Homes' `/napi/kann` gives
 * this device's person `dokumente_adresse`, `<login>+doc+@<domain>`
 * (homes#338); the app copies `<login>+doc+`, from Mehr's row and the app
 * icon's menu, and offers neither without an address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DokumenteAdresseTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun antwort(adresse: String?) =
        """{"version":1,"module":[],"app":{"mehr":["daten-sichern"${if (adresse != null) ",\"dokumente-adresse\"" else ""}]}""" +
            (adresse?.let { ""","dokumente_adresse":"$it"""" } ?: "") + "}"

    @After
    fun weg() {
        KannStore.fehlt(ctx)
        ServerStore.clear(ctx)
        TokenStore.clear(ctx)
        EinrichtungStore.clear(ctx)
    }

    private fun clip(): String? =
        ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    @Test fun kannReadsThePersonsAddressAndNoOtherHasOne() {
        assertEquals("mdopp+doc+@dopp.cloud", Kann.lesen(antwort("mdopp+doc+@dopp.cloud"))?.dokumenteAdresse)
        assertNull(Kann.lesen(antwort(null))?.dokumenteAdresse)
        assertNull(Kann.lesen(antwort(""))?.dokumenteAdresse)
    }

    @Test fun whatIsCopiedIsTheLoginAndDocAndThePlus() {
        assertEquals("mdopp+doc+", DokumenteAdresse.kopie("mdopp+doc+@dopp.cloud"))
        assertEquals("mdopp+doc+…@dopp.cloud", DokumenteAdresse.muster("mdopp+doc+@dopp.cloud"))
        for (falsch in listOf(null, "", "mdopp@dopp.cloud", "+doc+@dopp.cloud", "mdopp+doc+@", "mdopp+doc+@a@b", "md opp+doc+@dopp.cloud")) {
            assertNull(falsch, DokumenteAdresse.kopie(falsch))
        }
    }

    @Test fun copyingPutsItOnTheClipboard() {
        DokumenteAdresse.kopieren(ctx, "mdopp+doc+@dopp.cloud")
        assertEquals("mdopp+doc+", clip())
    }

    @Test fun theShortcutsActivityCopiesWhatHomesSaidLast() {
        KannStore.neu(ctx, antwort("anna+doc+@example.org"), null)
        val a = Robolectric.buildActivity(DokumenteAdresseActivity::class.java, Intent(Intent.ACTION_VIEW)).create().get()
        assertEquals("anna+doc+", clip())
        assertTrue("no screen of its own", a.isFinishing)
    }

    @Test fun theAppIconsMenuHasItOnlyWithAnAddress() {
        ServerStore.setBaseUrl(ctx, "https://homes.example")
        TokenStore.backend = ctx.getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx, "homes_device_TESTTOKEN123")
        EinrichtungStore.neu(ctx, EinrichtungTest.ANTWORT)
        KannStore.neu(ctx, antwort("anna+doc+@example.org"), null)
        AppShortcuts.refresh(ctx)
        val ids = ShortcutManagerCompat.getDynamicShortcuts(ctx).sortedBy { it.rank }.map { it.id }.filter { it.startsWith(Verknuepfungen.PREFIX) }
        assertEquals("after the others: $ids", DokumenteAdresse.ID, ids.last())
        val info = ShortcutManagerCompat.getDynamicShortcuts(ctx).first { it.id == DokumenteAdresse.ID }
        assertEquals(DokumenteAdresseActivity::class.java.name, info.intent.component?.className)

        KannStore.neu(ctx, antwort(null), null)
        AppShortcuts.refresh(ctx)
        assertFalse(ShortcutManagerCompat.getDynamicShortcuts(ctx).any { it.id == DokumenteAdresse.ID })
    }
}
