package cloud.dopp.homes

import android.Manifest
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.kann.Anbieter
import cloud.dopp.homes.kann.Kann
import cloud.dopp.homes.kann.KannStore
import cloud.dopp.homes.konto.HomesKonto
import cloud.dopp.homes.konto.HomesKonto.Teil
import cloud.dopp.homes.konto.KontoStore
import cloud.dopp.homes.sicherung.SicherungStore
import cloud.dopp.homes.sicherung.SicherungWorker
import cloud.dopp.homes.ui.HomesKontoActivity
import cloud.dopp.homes.ui.SicherungActivity
import cloud.dopp.homes.widget.DeviceWidgetProvider
import cloud.dopp.homes.widget.EnergyWidgetProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.Executors

/**
 * The account, the backup and the widget providers follow the box (homes ADR
 * 0001 §3, §10; homes-android#233): a part the box lacks is a switch off and
 * disabled with its trace below; its sync stops and resumes, nothing on the
 * phone deleted; the backup job skips it; a provider is disabled only while
 * its key is missing and none of its widgets is placed. With what the box can
 * do unknown, everything is as before. The two screens are drawn into
 * `build/widget-preview/app-<name>.png` (and `$WIDGET_PREVIEW_DIR`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [PruefendeSyncs::class], qualifiers = "w411dp-h891dp-xxhdpi")
class KannTeileTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        if (runCatching { WorkManager.getInstance(app) }.isFailure) {
            WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        }
        HomesKonto.entfernen(app)
        KannStore.clear(app)
        TokenStore.backend = app.getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        ServerStore.setBaseUrl(app, "https://homes.example.org")
        TokenStore.save(app, "homes_device_T")
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
        )
    }

    @After
    fun tearDown() {
        HomesKonto.entfernen(app)
        KannStore.clear(app)
        Anbieter.schalten(app, null)
        TokenStore.clear(app)
        ServerStore.clear(app)
    }

    /** A box without the shared calendar: the account module offers contacts only. */
    private val ohneKalender = """{"version":1,"module":[{"name":"kalender","titel":"Kalender","an":false,"warum":"fehlt",
        "grund":"Auf dieser Box läuft kein Radicale.","bietet":{"widgets":[],"teilen":[],"konto":["kalender"],"sicherung":[],"mehr":[]},
        "spur_id":"fehlt","spur":"auf dieser Box nicht eingerichtet","anfordern":true},
        {"name":"sicherung","titel":"Daten sichern","an":false,"warum":"aus","grund":"Auf dieser Box ausgeschaltet.",
        "bietet":{"widgets":[],"teilen":[],"konto":[],"sicherung":["whatsapp","signal","sms"],"mehr":["daten-sichern"]},
        "spur_id":"wartet","spur":"wartet auf Freigabe","anfordern":false},
        {"name":"energie","titel":"Energie","an":false,"warum":"fehlt","grund":"Kein Home Assistant.",
        "bietet":{"widgets":["energie"],"teilen":[],"konto":[],"sicherung":[],"mehr":[]},"spur_id":"fehlt","anfordern":true}],
        "app":{"widgets":["geraet"],"teilen":[],"konto":["kontakte"],"sicherung":["kontakte"],"mehr":[]}}"""

    private val alles = """{"version":1,"module":[],"app":{"widgets":["geraet","energie"],"teilen":[],"konto":["kontakte","kalender"],
        "sicherung":["whatsapp","signal","sms","kontakte"],"mehr":[]}}"""

    private fun syncable(teil: Teil) = ContentResolver.getIsSyncable(HomesKonto.konto, teil.authority)

    // --- the Homes account ----------------------------------------------------------

    @Test
    fun aPartTheBoxLacksStopsSyncingAndResumesWhenItComesBack() {
        HomesKonto.einschalten(app, Teil.KONTAKTE)
        HomesKonto.einschalten(app, Teil.KALENDER)
        HomesKonto.folgen(app, Kann.lesen(ohneKalender))
        assertEquals("stopped", 0, syncable(Teil.KALENDER))
        assertFalse(ContentResolver.getSyncAutomatically(HomesKonto.konto, Teil.KALENDER.authority))
        assertTrue(KontoStore(app).angehalten(Teil.KALENDER))
        assertTrue("the account and its entries stay", HomesKonto.da(app))
        assertTrue("the other part syncs on", HomesKonto.an(app, Teil.KONTAKTE))
        HomesKonto.folgen(app, Kann.lesen(alles))
        assertEquals("resumed", 1, syncable(Teil.KALENDER))
        assertTrue(ContentResolver.getSyncAutomatically(HomesKonto.konto, Teil.KALENDER.authority))
        assertFalse(KontoStore(app).angehalten(Teil.KALENDER))
    }

    @Test
    fun unknownChangesNothingAndAPartThatWasOffStaysOff() {
        HomesKonto.einschalten(app, Teil.KONTAKTE)
        HomesKonto.folgen(app, null)
        assertTrue(HomesKonto.an(app, Teil.KONTAKTE))
        HomesKonto.folgen(app, Kann.lesen(ohneKalender))
        assertFalse("off before, not held", KontoStore(app).angehalten(Teil.KALENDER))
        HomesKonto.folgen(app, Kann.lesen(alles))
        assertFalse("the box offers it again: still the person's choice", HomesKonto.an(app, Teil.KALENDER))
    }

    @Test
    fun switchingTheOtherPartOffKeepsTheAccountOfAHeldPart() {
        HomesKonto.einschalten(app, Teil.KONTAKTE)
        HomesKonto.einschalten(app, Teil.KALENDER)
        HomesKonto.folgen(app, Kann.lesen(ohneKalender))
        HomesKonto.ausschalten(app, Teil.KONTAKTE)
        assertTrue("the held calendar's entries are not lost with it", HomesKonto.da(app))
    }

    @Test
    fun theScreenShowsAMissingPartAsADisabledSwitchWithItsTrace() {
        HomesKonto.einschalten(app, Teil.KONTAKTE)
        HomesKonto.einschalten(app, Teil.KALENDER)
        KannStore.neu(app, ohneKalender, null)
        HomesKonto.folgen(app, KannStore.kann(app))
        val a = Robolectric.buildActivity(HomesKontoActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val kalender = a.findViewById<SwitchCompat>(R.id.konto_kalender_an)
        assertFalse(kalender.isChecked)
        assertFalse(kalender.isEnabled)
        val spur = a.findViewById<ViewGroup>(R.id.konto_kalender_spur)
        assertEquals(View.VISIBLE, spur.visibility)
        assertEquals("Kalender", spur.findViewById<TextView>(R.id.spur_titel).text.toString())
        assertEquals("auf dieser Box nicht eingerichtet", spur.findViewById<TextView>(R.id.spur_stand).text.toString())
        assertEquals("Auf dieser Box läuft kein Radicale.", spur.findViewById<TextView>(R.id.spur_grund).text.toString())
        assertEquals(View.VISIBLE, spur.findViewById<Button>(R.id.spur_anfordern).visibility)
        assertTrue("contacts stay as they are", a.findViewById<SwitchCompat>(R.id.konto_kontakte_an).isEnabled)
        assertEquals(View.GONE, a.findViewById<View>(R.id.konto_kontakte_spur).visibility)
        draw(a.findViewById(R.id.konto_root), "app-konto-teil-fehlt")
    }

    // --- Daten sichern --------------------------------------------------------------

    @Test
    fun theBackupScreenShowsMissingPartsDisabledWithTheirTrace() {
        KannStore.neu(app, ohneKalender, null)
        SicherungStore(app).setzeSmsAn(true)
        val a = Robolectric.buildActivity(SicherungActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val sms = a.findViewById<SwitchCompat>(R.id.handy_sms)
        assertFalse(sms.isChecked)
        assertFalse(sms.isEnabled)
        val spur = a.findViewById<ViewGroup>(R.id.handy_sms_spur)
        assertEquals(View.VISIBLE, spur.visibility)
        assertEquals("wartet auf Freigabe", spur.findViewById<TextView>(R.id.spur_stand).text.toString())
        assertEquals("a wish waits: no button", View.GONE, spur.findViewById<View>(R.id.spur_anfordern).visibility)
        assertEquals("no orange line beside the trace", View.GONE, a.findViewById<View>(R.id.handy_sms_meldung).visibility)
        assertTrue("contacts are offered", a.findViewById<SwitchCompat>(R.id.handy_kontakte).isEnabled)
        val whatsapp = a.findViewById<View>(R.id.sicherung_whatsapp)
        assertFalse(whatsapp.findViewById<Button>(R.id.quelle_waehlen).isEnabled)
        assertEquals(View.VISIBLE, whatsapp.findViewById<View>(R.id.quelle_spur).visibility)
        draw(a.findViewById(R.id.sicherung_root), "app-sicherung-teil-fehlt")
    }

    @Test
    fun theJobSkipsAPartTheBoxLacks() {
        val store = SicherungStore(app)
        store.setzeKontakteAn(true)
        store.setzeSmsAn(true)
        // no SMS permission: a run of the part would leave its sentence
        KannStore.neu(app, ohneKalender, null)
        run()
        assertNull("sms skipped: no word from it", store.meldung(cloud.dopp.homes.data.Napi.Quelle.SMS))
        KannStore.neu(app, alles, null)
        run()
        assertNotNull("offered again: the part runs", store.meldung(cloud.dopp.homes.data.Napi.Quelle.SMS))
    }

    private fun run() {
        TestWorkerBuilder<SicherungWorker>(app, Executors.newSingleThreadExecutor()).build().doWork()
    }

    // --- the widget providers -------------------------------------------------------

    private fun state(cls: Class<*>) = app.packageManager.getComponentEnabledSetting(ComponentName(app, cls))

    @Test
    fun aProviderGoesOnlyWhileItsKeyIsMissingAndNoneIsPlaced() {
        Anbieter.schalten(app, null)
        assertEquals("unknown: the device widget as before", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(DeviceWidgetProvider::class.java))
        assertFalse("nothing changes again", Anbieter.schalten(app, null))
        Anbieter.schalten(app, Kann.lesen(alles))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(DeviceWidgetProvider::class.java))
    }

    /** The energy widget went (owner 2026-11-28): never in the picker, even with the energy on the box. */
    @Test
    fun theEnergyWidgetIsNeverOffered() {
        Anbieter.schalten(app, Kann.lesen(alles))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(EnergyWidgetProvider::class.java))
        Anbieter.schalten(app, null)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(EnergyWidgetProvider::class.java))
    }

    @Test
    fun aProviderWithAPlacedWidgetIsNeverDisabled() {
        shadowOf(AppWidgetManager.getInstance(app)).createWidget(EnergyWidgetProvider::class.java, R.layout.widget_error)
        Anbieter.schalten(app, Kann.lesen(ohneKalender))
        assertEquals("a placed one keeps it: it says it is gone", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(EnergyWidgetProvider::class.java))
    }

    private fun draw(root: View, name: String) {
        val w = (411 * app.resources.displayMetrics.density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val bmp = Bitmap.createBitmap(root.measuredWidth, root.measuredHeight, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(app.getColor(R.color.homes_bg))
        root.draw(Canvas(bmp))
        listOfNotNull(File(RepoFiles.root, "app/build/widget-preview"), System.getenv("WIDGET_PREVIEW_DIR")?.let(::File)).forEach { dir ->
            dir.mkdirs()
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
