package cloud.dopp.homes

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.sicherung.SicherungWorker
import cloud.dopp.homes.ui.HomesKontoActivity
import cloud.dopp.homes.ui.HomeActivity
import cloud.dopp.homes.ui.SicherungActivity
import cloud.dopp.homes.ui.StandortActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Mehr → Daten sichern (#194; owner 2026-11-08: only what needs the phone):
 * the home screen leads there; the screen shows one line per source, the
 * guide only folded for a source not set up, the way to the overview on
 * Homes and to the key in the archive, „Jetzt sichern“, and asks for no key;
 * the daily run waits for WLAN, the charger and enough battery, „Jetzt
 * sichern“ only for WLAN.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SicherungScreenTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Before
    fun auf() {
        if (runCatching { WorkManager.getInstance(ctx()) }.isFailure) {
            WorkManagerTestInitHelper.initializeTestWorkManager(
                ctx(),
                Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
            )
        }
        ServerStore.setBaseUrl(ctx(), "https://127.0.0.1:1")
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_TESTTOKEN123")
    }

    @After
    fun zu() {
        ServerStore.clear(ctx())
        TokenStore.clear(ctx())
    }

    @Test
    fun homeLeadsToIt() {
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        val mehr = a.findViewById<ViewGroup>(R.id.mehr_group)
        assertEquals("Daten sichern, Standortverlauf and Homes auf dem Handy", 3, mehr.childCount)
        val row = mehr.getChildAt(0)
        assertEquals("Daten sichern", row.findViewById<TextView>(R.id.type_label).text.toString())
        row.performClick()
        val next = shadowOf(a).nextStartedActivity
        assertEquals(SicherungActivity::class.java.name, next.component?.className)
        val standort = mehr.getChildAt(1)
        assertEquals("Standortverlauf", standort.findViewById<TextView>(R.id.type_label).text.toString())
        standort.performClick()
        assertEquals(StandortActivity::class.java.name, shadowOf(a).nextStartedActivity.component?.className)
        val konto = mehr.getChildAt(2)
        assertEquals("Homes auf dem Handy", konto.findViewById<TextView>(R.id.type_label).text.toString())
        konto.performClick()
        assertEquals(HomesKontoActivity::class.java.name, shadowOf(a).nextStartedActivity.component?.className)
    }

    @Test
    fun aSourceNotSetUpSaysSoWithOneButtonAndItsGuideFolded() {
        val a = Robolectric.buildActivity(SicherungActivity::class.java).setup().get()
        val whatsapp = a.findViewById<View>(R.id.sicherung_whatsapp)
        val signal = a.findViewById<View>(R.id.sicherung_signal)
        assertEquals("WhatsApp", whatsapp.findViewById<TextView>(R.id.quelle_name).text.toString())
        assertEquals("Signal", signal.findViewById<TextView>(R.id.quelle_name).text.toString())
        mapOf(
            whatsapp to listOf("Ende-zu-Ende-verschlüsseltes Backup", "64-stelligen Schlüssel", "nicht mit Passwort oder Passkey", "Bitwarden", "com.whatsapp"),
            signal to listOf("Chat-Backups", "30-stellige Passphrase", "Bitwarden", "im Archiv eintragen"),
        ).forEach { (k, schritte) ->
            assertEquals("⚠ Noch nicht eingerichtet", k.findViewById<TextView>(R.id.quelle_zuletzt).text.toString())
            val knopf = k.findViewById<Button>(R.id.quelle_waehlen)
            assertEquals("Ordner freigeben", knopf.text.toString())
            assertEquals(View.VISIBLE, knopf.visibility)
            val anleitung = k.findViewById<View>(R.id.quelle_anleitung)
            assertEquals("folded", View.GONE, anleitung.visibility)
            k.findViewById<View>(R.id.quelle_wie).performClick()
            assertEquals(View.VISIBLE, anleitung.visibility)
            val text = k.findViewById<TextView>(R.id.quelle_schritte).text.toString()
            schritte.forEach { assertTrue("the guide lacks „$it“", text.contains(it)) }
            assertEquals("Schlüssel im Archiv eintragen", k.findViewById<Button>(R.id.quelle_schluessel).text.toString())
        }
        val alles = texte(a.findViewById(R.id.sicherung_root))
        listOf(
            "Discord", "Vaultwarden", "Google-Kalender", "App-Passwort", "Was nie ins Archiv kommt", "Standortverlauf",
            "Auf der Box", "Frei auf der Box", "Zuletzt geprüft", "Nach einem Schlüssel fragt sie nie",
        ).forEach { assertFalse("the screen still says „$it“", alles.contains(it)) }
        assertEquals("Daten sichern", a.getString(R.string.sicherung_title))
        assertFalse("no key field: keys go into the vault on the box", hat(a.window.decorView, EditText::class.java))
    }

    /** A folder chosen once whose grant is gone: ⚠, why, the one button; no guide any more. */
    @Test
    fun aLostFolderIsAWarningWithItsButton() {
        cloud.dopp.homes.sicherung.SicherungStore(ctx()).setzeOrdner(
            cloud.dopp.homes.data.Napi.Quelle.WHATSAPP,
            android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fmedia%2Fcom.whatsapp%2FWhatsApp"),
        )
        val k = Robolectric.buildActivity(SicherungActivity::class.java).setup().get().findViewById<View>(R.id.sicherung_whatsapp)
        assertEquals("⚠ WhatsApp-Ordner nicht mehr freigegeben.", k.findViewById<TextView>(R.id.quelle_zuletzt).text.toString())
        assertEquals(View.VISIBLE, k.findViewById<View>(R.id.quelle_waehlen).visibility)
        assertEquals(View.GONE, k.findViewById<View>(R.id.quelle_wie).visibility)
        assertEquals(View.GONE, k.findViewById<View>(R.id.quelle_anleitung).visibility)
    }

    /** „Alles im Überblick“ is Homes' page; the key goes to the archive's /tresor, or that page when the archive's address cannot be told. */
    @Test
    fun theOverviewAndTheKeyOpenTheirPages() {
        ServerStore.setBaseUrl(ctx(), "https://www.box.test")
        var a = Robolectric.buildActivity(SicherungActivity::class.java).setup().get()
        val ueberblick = a.findViewById<TextView>(R.id.sicherung_ueberblick)
        assertEquals("Alles im Überblick ›", ueberblick.text.toString())
        ueberblick.performClick()
        assertEquals("https://www.box.test/daten-sichern", shadowOf(a).nextStartedActivity.dataString)
        a.findViewById<View>(R.id.sicherung_signal).findViewById<View>(R.id.quelle_schluessel).performClick()
        assertEquals("https://archiv.box.test/tresor", shadowOf(a).nextStartedActivity.dataString)
        ServerStore.setBaseUrl(ctx(), "https://127.0.0.1:1")
        a = Robolectric.buildActivity(SicherungActivity::class.java).setup().get()
        a.findViewById<View>(R.id.sicherung_signal).findViewById<View>(R.id.quelle_schluessel).performClick()
        assertEquals("https://127.0.0.1:1/daten-sichern", shadowOf(a).nextStartedActivity.dataString)
    }

    @Test
    fun jetztWithoutAFolderSaysWhatToDoFirst() {
        val a = Robolectric.buildActivity(SicherungActivity::class.java).setup().get()
        a.findViewById<View>(R.id.sicherung_jetzt).performClick()
        assertEquals(
            "Erst einen Ordner freigeben oder einen Schalter einschalten, dann sichern.",
            a.findViewById<TextView>(R.id.sicherung_zustand).text.toString(),
        )
        val offen = WorkManager.getInstance(ctx()).getWorkInfosForUniqueWork(SicherungWorker.JETZT).get().filter { !it.state.isFinished }
        assertTrue("nothing enqueued: $offen", offen.isEmpty())
    }

    /**
     * Kontakte, SMS und Anrufe (laib ADR 0029): two cards, the switch in the
     * title row, off, and while off nothing else (owner 2026-11-08).
     */
    @Test
    fun twoCardsWithTheSwitchInTheTitleAndNothingElseWhileOff() {
        val a = Robolectric.buildActivity(SicherungActivity::class.java).setup().get()
        mapOf(
            R.id.handy_kontakte_karte to (R.id.handy_kontakte to "Kontakte"),
            R.id.handy_sms_karte to (R.id.handy_sms to "SMS und Anrufe"),
        ).forEach { (karteId, t) ->
            val (schalterId, titel) = t
            val karte = a.findViewById<ViewGroup>(karteId)
            val zeile = karte.getChildAt(0) as ViewGroup
            assertEquals(titel, (zeile.getChildAt(0) as TextView).text.toString())
            val schalter = zeile.getChildAt(1) as SwitchCompat
            assertEquals(schalterId, schalter.id)
            assertFalse(schalter.isChecked)
            val sichtbar = (1 until karte.childCount).map { karte.getChildAt(it) }.filter { it.visibility == View.VISIBLE }
            assertEquals("off: nothing but the switch", 0, sichtbar.size)
        }
    }

    /** On and never sent: both cards say the same words; after a run, date and what. */
    @Test
    fun theStatusLinesFollowTheOtherCards() {
        val app = shadowOf(ctx() as android.app.Application)
        app.grantPermissions(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS, android.Manifest.permission.READ_SMS, android.Manifest.permission.READ_CALL_LOG)
        val store = cloud.dopp.homes.sicherung.SicherungStore(ctx())
        store.setzeKontakteAn(true)
        store.setzeSmsAn(true)
        var a = Robolectric.buildActivity(SicherungActivity::class.java).setup().get()
        listOf(R.id.handy_kontakte_stand, R.id.handy_sms_stand).forEach {
            assertEquals("✓ Bereit – kommt beim nächsten Sichern", a.findViewById<TextView>(it).text.toString())
        }
        store.merkeKontakte(1_762_050_000_000, cloud.dopp.homes.data.SicherungClient.Kontakte(412, 3, 1, 0))
        store.merkeLetzte(
            1_762_050_000_000,
            mapOf(cloud.dopp.homes.sicherung.Art.SMS to 1234, cloud.dopp.homes.sicherung.Art.MMS to 56, cloud.dopp.homes.sicherung.Art.ANRUFE to 789),
        )
        a = Robolectric.buildActivity(SicherungActivity::class.java).setup().get()
        val zeit = cloud.dopp.homes.sicherung.Anzeige.zeit(1_762_050_000_000)
        assertEquals("✓ Zuletzt übertragen: $zeit – 412 Kontakte, 3 neu, 1 geändert", a.findViewById<TextView>(R.id.handy_kontakte_stand).text.toString())
        assertEquals("✓ Zuletzt übertragen: $zeit – 1.234 SMS, 56 MMS, 789 Anrufe", a.findViewById<TextView>(R.id.handy_sms_stand).text.toString())
        WorkManager.getInstance(ctx()).cancelAllWork()
    }

    /** Refused: the switch stays off and says why. */
    @Test
    fun aRefusedPermissionLeavesTheSwitchOff() {
        val app = shadowOf(ctx() as android.app.Application)
        app.denyPermissions(android.Manifest.permission.READ_CONTACTS)
        val a = Robolectric.buildActivity(SicherungActivity::class.java).setup().get()
        a.findViewById<SwitchCompat>(R.id.handy_kontakte).performClick()
        val anfrage = shadowOf(a).lastRequestedPermission
        assertTrue(anfrage.requestedPermissions.contains(android.Manifest.permission.READ_CONTACTS))
        a.onRequestPermissionsResult(anfrage.requestCode, anfrage.requestedPermissions, IntArray(anfrage.requestedPermissions.size) { -1 })
        assertFalse(a.findViewById<SwitchCompat>(R.id.handy_kontakte).isChecked)
        assertFalse(cloud.dopp.homes.sicherung.SicherungStore(ctx()).kontakteAn())
        val satz = a.findViewById<TextView>(R.id.handy_kontakte_meldung)
        assertEquals(View.VISIBLE, satz.visibility)
        assertTrue(satz.text.toString(), satz.text.contains("Kontakte"))
    }

    /** Allowed: the switch is on and the daily run is planned. */
    @Test
    fun anAllowedPermissionTurnsTheSwitchOn() {
        // reading for the archive, changing by an accepted correction (owner 2026-11-07): the switch asks for both
        shadowOf(ctx() as android.app.Application).grantPermissions(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)
        val a = Robolectric.buildActivity(SicherungActivity::class.java).setup().get()
        a.findViewById<SwitchCompat>(R.id.handy_kontakte).performClick()
        assertTrue(a.findViewById<SwitchCompat>(R.id.handy_kontakte).isChecked)
        assertTrue(cloud.dopp.homes.sicherung.SicherungStore(ctx()).kontakteAn())
        val geplant = WorkManager.getInstance(ctx()).getWorkInfosForUniqueWork(SicherungWorker.TAEGLICH).get()
        assertTrue(geplant.any { !it.state.isFinished })
        WorkManager.getInstance(ctx()).cancelAllWork()
        cloud.dopp.homes.sicherung.SicherungStore(ctx()).setzeKontakteAn(false)
    }

    @Test
    fun theDailyRunWaitsForWlanChargerAndBattery() {
        val r = SicherungWorker.taeglich()
        val c = r.workSpec.constraints
        assertEquals(NetworkType.UNMETERED, c.requiredNetworkType)
        assertTrue(c.requiresCharging())
        assertTrue(c.requiresBatteryNotLow())
        assertEquals(TimeUnit.DAYS.toMillis(1), r.workSpec.intervalDuration)
        assertFalse("the job never goes foreground", r.workSpec.expedited)
    }

    @Test
    fun jetztWaitsOnlyForWlan() {
        val c = SicherungWorker.jetztAnfrage().workSpec.constraints
        assertEquals(NetworkType.UNMETERED, c.requiredNetworkType)
        assertFalse(c.requiresCharging())
        SicherungWorker.jetzt(ctx())
        SicherungWorker.jetzt(ctx())
        val infos = WorkManager.getInstance(ctx()).getWorkInfosForUniqueWork(SicherungWorker.JETZT).get()
        assertEquals("a second tap adds no second run", 1, infos.size)
        assertTrue(infos.single().state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.SUCCEEDED))
        WorkManager.getInstance(ctx()).cancelAllWork()
    }

    private fun texte(v: View): String = when (v) {
        is TextView -> v.text.toString()
        is ViewGroup -> (0 until v.childCount).joinToString("\n") { texte(v.getChildAt(it)) }
        else -> ""
    }

    private fun hat(v: View, c: Class<*>): Boolean =
        c.isInstance(v) || (v is ViewGroup && (0 until v.childCount).any { hat(v.getChildAt(it), c) })
}
