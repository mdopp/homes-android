package cloud.dopp.homes

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.Napi.KannKey
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.kann.Kann
import cloud.dopp.homes.kann.KannStore
import cloud.dopp.homes.kann.Teilen
import cloud.dopp.homes.ui.HomeActivity
import cloud.dopp.homes.widget.ShareTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The share targets follow the box (homes ADR 0001 §3, §10; homes-android#232):
 * one component per target, switched by `teilen`; everything enabled while
 * what the box can do is unknown; the Direct Share target follows the same
 * keys; and a disabled target's trace is its module's line in Mehr.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TeilenTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @After
    fun clean() {
        Teilen.schalten(ctx(), null)
        KannStore.clear(ctx())
    }

    private fun kann(teilen: String, off: String = "") = Kann.lesen(
        """{"version":1,"module":[$off],"app":{"widgets":[],"teilen":[$teilen],"konto":[],"sicherung":[],"mehr":[]}}""",
    )

    private fun state(cls: String) = ctx().packageManager.getComponentEnabledSetting(ComponentName(ctx().packageName, cls))

    /** Who Android offers a share of [typ] to, of this app's components. */
    private fun ziele(typ: String, action: String = Intent.ACTION_SEND): List<String> =
        ctx().packageManager.queryIntentActivities(
            Intent(action).setType(typ).putExtra(Intent.EXTRA_STREAM, Uri.parse("content://x/y")), PackageManager.MATCH_DEFAULT_ONLY,
        ).map { it.activityInfo.name }

    @Test
    fun unknownEveryTargetIsOn() {
        assertFalse("nothing to switch", Teilen.schalten(ctx(), null))
        listOf(KannKey.MERKEN, KannKey.DOKUMENT, KannKey.STANDORT_IMPORT).forEach { assertTrue(it, Teilen.an(ctx(), it)) }
        assertTrue(ziele("text/plain").isNotEmpty())
        assertTrue(ziele("image/jpeg").isNotEmpty())
        assertTrue(ziele("application/gpx+xml").isNotEmpty())
        assertTrue(Teilen.an(ctx(), KannKey.MUSIK))
        assertEquals(listOf("cloud.dopp.homes.hochladen.MusikTeilenActivity"), ziele("audio/mpeg"))
    }

    /** „Zur Musik hinzufügen“ follows the box's `musik` (owner, 2026-11-04); a ZIP offers it beside the history import. */
    @Test
    fun theMusicTargetFollowsTheBox() {
        Teilen.schalten(ctx(), kann("\"musik\""))
        assertEquals(listOf("cloud.dopp.homes.hochladen.MusikTeilenActivity"), ziele("audio/flac", Intent.ACTION_SEND_MULTIPLE))
        assertEquals(listOf("cloud.dopp.homes.hochladen.MusikTeilenActivity"), ziele("application/zip"))
        Teilen.schalten(ctx(), kann("\"standort-import\""))
        assertTrue("no music: ${ziele("audio/mpeg")}", ziele("audio/mpeg").isEmpty())
        Teilen.schalten(ctx(), null)
        assertEquals(
            setOf("cloud.dopp.homes.hochladen.MusikTeilenActivity", "cloud.dopp.homes.ui.StandortImportActivity", "cloud.dopp.homes.archiv.ArchivTeilenActivity"),
            ziele("application/zip").toSet(),
        )
    }

    /** „Ins Archiv“ follows the box's `archiv` (owner, 2026-11-11): a ZIP, a JSON or a LinkedIn CSV. */
    @Test
    fun theArchiveTargetFollowsTheBox() {
        val archiv = "cloud.dopp.homes.archiv.ArchivTeilenActivity"
        Teilen.schalten(ctx(), kann("\"archiv\""))
        assertEquals(listOf(archiv), ziele("application/zip"))
        assertEquals(listOf(archiv), ziele("text/csv"))
        assertEquals(listOf(archiv), ziele("application/zip", Intent.ACTION_SEND_MULTIPLE))
        assertTrue(Teilen.an(ctx(), KannKey.ARCHIV))
        Teilen.schalten(ctx(), kann("\"musik\""))
        assertFalse(Teilen.an(ctx(), KannKey.ARCHIV))
        assertTrue("no archive: ${ziele("text/csv")}", ziele("text/csv").isEmpty())
    }

    @Test
    fun aKeyThatGoesDisablesItsTargetAndOneThatComesBackEnablesIt() {
        assertTrue(Teilen.schalten(ctx(), kann("\"merken\"")))
        assertTrue(Teilen.an(ctx(), KannKey.MERKEN))
        assertFalse(Teilen.an(ctx(), KannKey.DOKUMENT))
        assertFalse(Teilen.an(ctx(), KannKey.STANDORT_IMPORT))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state("cloud.dopp.homes.ui.StandortImportActivity"))
        assertFalse("switched already", Teilen.schalten(ctx(), kann("\"merken\"")))
        assertTrue(Teilen.schalten(ctx(), kann("\"merken\",\"dokument\",\"standort-import\"")))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state("cloud.dopp.homes.ui.StandortImportActivity"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state("cloud.dopp.homes.ui.TeilenDokument"))
    }

    @Test
    fun androidOffersOnlyTheTargetsTheBoxTakes() {
        Teilen.schalten(ctx(), kann("\"dokument\""))
        assertTrue("no text: ${ziele("text/plain")}", ziele("text/plain").isEmpty())
        assertEquals(listOf("cloud.dopp.homes.ui.TeilenDokument"), ziele("image/jpeg"))
        assertEquals(listOf("cloud.dopp.homes.ui.TeilenDokument"), ziele("image/*", Intent.ACTION_SEND_MULTIPLE))
        assertTrue("no history import: ${ziele("application/gpx+xml")}", ziele("application/gpx+xml").isEmpty())
        Teilen.schalten(ctx(), null)
        assertEquals(listOf("cloud.dopp.homes.ui.TeilenMerken"), ziele("text/plain"))
    }

    @Test
    fun theDirectShareTargetFollowsTheSameKeys() {
        ServerStore.setBaseUrl(ctx(), "https://127.0.0.1:1")
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_T")
        Teilen.schalten(ctx(), kann("\"dokument\""))
        assertEquals(setOf(ShareTarget.CATEGORY_DOKUMENT), ShareTarget.info(ctx())?.categories)
        Teilen.schalten(ctx(), kann("\"merken\""))
        assertEquals(setOf(ShareTarget.CATEGORY), ShareTarget.info(ctx())?.categories)
        Teilen.schalten(ctx(), kann(""))
        assertNull("the box takes no share: no target", ShareTarget.info(ctx()))
        Teilen.schalten(ctx(), null)
        assertEquals(setOf(ShareTarget.CATEGORY, ShareTarget.CATEGORY_DOKUMENT), ShareTarget.info(ctx())?.categories)
    }

    /** While the history import is off in the share sheet, its trace is Standortverlauf's line in Mehr. */
    @Test
    fun aDisabledTargetsTraceIsItsModulesLineInMehr() {
        val body = """{"version":1,"module":[{"name":"standort","titel":"Standortverlauf","an":false,"warum":"fehlt",
            "grund":"Auf dieser Box läuft kein Reitti.","bietet":{"widgets":[],"teilen":["standort-import"],"konto":[],"sicherung":[],
            "mehr":["standortverlauf"]},"spur_id":"fehlt","spur":"auf dieser Box nicht eingerichtet","anfordern":true}],
            "app":{"widgets":["geraet","energie"],"teilen":["merken","dokument"],"konto":[],"sicherung":[],"mehr":["daten-sichern"]}}"""
        KannStore.neu(ctx(), body, null)
        Teilen.schalten(ctx(), KannStore.kann(ctx()))
        assertFalse(Teilen.an(ctx(), KannKey.STANDORT_IMPORT))
        ServerStore.setBaseUrl(ctx(), "https://127.0.0.1:1")
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_T")
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val group = a.findViewById<ViewGroup>(R.id.spuren_group)
        val row = (0 until group.childCount).map { group.getChildAt(it) }
            .single { it.findViewById<TextView>(R.id.spur_titel).text == "Standortverlauf" }
        val teilen = row.findViewById<TextView>(R.id.spur_teilen)
        assertEquals(View.VISIBLE, teilen.visibility)
        assertEquals("Teilen: GPX- und GeoJSON-Dateien in deinen Standortverlauf", teilen.text.toString())
        assertEquals(View.VISIBLE, row.findViewById<View>(R.id.spur_anfordern).visibility)
    }
}
