package cloud.dopp.homes

import android.content.Context
import android.view.View
import android.view.ViewGroup
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
 * Mehr → Daten sichern (#194): the home screen leads there; the screen
 * shows both sources, the guide and „Jetzt sichern“, asks for no key; the
 * daily run waits for WLAN, the charger and enough battery, „Jetzt sichern“
 * only for WLAN.
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
    fun theScreenShowsBothSourcesTheGuideAndNoKeyField() {
        val a = Robolectric.buildActivity(SicherungActivity::class.java).setup().get()
        val whatsapp = a.findViewById<View>(R.id.sicherung_whatsapp)
        val signal = a.findViewById<View>(R.id.sicherung_signal)
        assertEquals("WhatsApp", whatsapp.findViewById<TextView>(R.id.quelle_name).text.toString())
        assertEquals("Signal", signal.findViewById<TextView>(R.id.quelle_name).text.toString())
        listOf(whatsapp, signal).forEach {
            assertEquals("Noch kein Ordner gewählt", it.findViewById<TextView>(R.id.quelle_ordner).text.toString())
            assertEquals("Letzte Übertragung: noch keine", it.findViewById<TextView>(R.id.quelle_zuletzt).text.toString())
            assertEquals("Ordner wählen", it.findViewById<TextView>(R.id.quelle_waehlen).text.toString())
        }
        val anleitung = texte(a.findViewById(R.id.sicherung_anleitung))
        listOf(
            "Ende-zu-Ende-verschlüsseltes Backup", "64-stelligen Schlüssel verwenden", "Vaultwarden",
            "Chat-Backups", "Auf dem Gerät", "30-stellige Passphrase",
            "SMS und Anrufe übertragen", "Handy-Kontakte", "App-Passwort", "jede Stunde", "Google-Kalender",
            "Gelöschte Termine bleiben im Archiv", "Mehr → Standortverlauf",
            "je nach App-Version", "die sich selbst löschen", "einmaligen Ansicht",
        ).forEach { assertTrue("the guide lacks „$it“", anleitung.contains(it)) }
        assertFalse("Discord is gone (owner, 2026-11-03)", anleitung.contains("Discord"))
        assertEquals("Daten sichern", a.getString(R.string.sicherung_title))
        assertFalse("no key field: keys go into the vault on the box", hat(a.window.decorView, EditText::class.java))
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
     * Kontakte, SMS und Anrufe (laib ADR 0029): two cards like WhatsApp's and
     * Signal's, the switch in the title row, off, one plain sentence in the
     * body style (not the small capitals of a label) and, while off, nothing else.
     */
    @Test
    fun twoCardsWithTheSwitchInTheTitleAndNothingElseWhileOff() {
        val a = Robolectric.buildActivity(SicherungActivity::class.java).setup().get()
        mapOf(
            R.id.handy_kontakte_karte to Triple(R.id.handy_kontakte, "Kontakte", "„Handy-Kontakte“"),
            R.id.handy_sms_karte to Triple(R.id.handy_sms, "SMS und Anrufe", "für dein Archiv verschlüsselt"),
        ).forEach { (karteId, t) ->
            val (schalterId, titel, satz) = t
            val karte = a.findViewById<ViewGroup>(karteId)
            val zeile = karte.getChildAt(0) as ViewGroup
            assertEquals(titel, (zeile.getChildAt(0) as TextView).text.toString())
            val schalter = zeile.getChildAt(1) as SwitchCompat
            assertEquals(schalterId, schalter.id)
            assertFalse(schalter.isChecked)
            val sichtbar = (1 until karte.childCount).map { karte.getChildAt(it) }.filter { it.visibility == View.VISIBLE }
            assertEquals("off: only the sentence", 1, sichtbar.size)
            val text = sichtbar.single() as TextView
            assertTrue(text.text.toString(), text.text.contains(satz))
            assertFalse("no capitals", text.isAllCaps)
            assertEquals("no letter spacing", 0f, text.letterSpacing, 0f)
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
            assertEquals("Letzte Übertragung: noch keine – geschieht beim nächsten Sichern", a.findViewById<TextView>(it).text.toString())
        }
        store.merkeKontakte(1_762_050_000_000, cloud.dopp.homes.data.SicherungClient.Kontakte(412, 3, 1, 0))
        store.merkeLetzte(
            1_762_050_000_000,
            mapOf(cloud.dopp.homes.sicherung.Art.SMS to 1234, cloud.dopp.homes.sicherung.Art.MMS to 56, cloud.dopp.homes.sicherung.Art.ANRUFE to 789),
        )
        a = Robolectric.buildActivity(SicherungActivity::class.java).setup().get()
        val zeit = cloud.dopp.homes.sicherung.Anzeige.zeit(1_762_050_000_000)
        assertEquals("Letzte Übertragung: $zeit – 412 Kontakte, 3 neu, 1 geändert", a.findViewById<TextView>(R.id.handy_kontakte_stand).text.toString())
        assertEquals("Letzte Übertragung: $zeit – 1.234 SMS, 56 MMS, 789 Anrufe", a.findViewById<TextView>(R.id.handy_sms_stand).text.toString())
        WorkManager.getInstance(ctx()).cancelAllWork()
    }

    /**
     * The intro covers all of it; the guide says per source what to do and what
     * happens (owner, 2026-11-03).
     */
    @Test
    fun theIntroAndTheGuideCoverContactsSmsAndCalls() {
        val a = Robolectric.buildActivity(SicherungActivity::class.java).setup().get()
        val intro = a.getString(R.string.sicherung_intro)
        listOf("WhatsApp", "Signal", "Kontakte", "SMS", "Anrufe", "Nach einem Schlüssel fragt sie nie").forEach {
            assertTrue("the intro lacks „$it“", intro.contains(it))
        }
        val anleitung = texte(a.findViewById(R.id.sicherung_anleitung))
        listOf(
            "nicht Passwort und nicht Passkey", "aus einem Passkey lässt sich der Schlüssel nicht auslesen",
            "aus- und gleich wieder einschalten",
        ).forEach { assertTrue("the guide lacks „$it“", anleitung.contains(it)) }
        listOf("In der Homes-App", "in der Homes-App", "Mehr → Daten sichern", "Eingeschränkte Einstellungen")
            .forEach { assertFalse("the guide still says „$it“", anleitung.contains(it)) }
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
