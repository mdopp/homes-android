package cloud.dopp.homes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the app is and is not (#176; the owner's call on how the widgets stay
 * live). It is widgets and pairing, kept live by one foreground service of
 * type `specialUse` that holds the stream while the screen is on, restarts
 * after boot, and shows one quiet notification for it. It is not a poll: no
 * widget update period, no alarm, no wakelock of its own. Not `dataSync`
 * either — Android 15 caps that at 6 hours a day. No web shell, no voice.
 *
 * Two jobs, and only these two. The backup (the owner's call in #194, laib
 * ADR 0029): the daily pick-up of the phone's WhatsApp and Signal backups, a
 * WorkManager worker that never goes foreground and posts nothing. And
 * Hochladen (the owner's call of 2026-11-04: a large upload keeps going with
 * the screen off, with its own notification): the files shared to the music
 * and the Takeout ZIPs for the Standortverlauf. It runs in WorkManager's
 * foreground service, `dataSync`, only while it uploads, and its progress is
 * that service's notification — the app's second foreground service and its
 * second notification, beside the live widgets', and no other. The folders come through the
 * system picker: no storage permission. Beside it, optional and each behind
 * its own switch and permission (laib ADR 0029): the contacts (READ_CONTACTS),
 * the SMS and the call log (READ_SMS, READ_CALL_LOG). Read only: never a
 * permission to write or send them.
 *
 * Standortverlauf (owner 2026-11-02), optional behind its own switch: the
 * location in the background, delivered by Android's own LocationManager by
 * PendingIntent to one receiver that is not exported. It adds no service,
 * no job, no alarm and no wakelock: the points go to Homes while a delivery
 * has the phone awake anyway.
 *
 * No Google Play services, anywhere (the owner's decision, 2026-11-02): no
 * `com.google.android.gms` library, class or permission.
 *
 * Das Homes-Konto (the owner's call of 2026-11-03, #206): the household's
 * shared contacts and calendars in a real Android account „Homes“, so the
 * dialer, WhatsApp, mail and the calendar app see them. Its authenticator and
 * its two sync adapters are bound services Android alone may bind; none goes
 * foreground or posts anything. Android's SyncManager runs them, hourly at
 * most; the app schedules nothing of its own for them. They write only into
 * the account's own entries, as the sync adapter, and send back what the
 * phone changed there (supportsUploading, owner 2026-11-05); each part behind its own switch and
 * permission (WRITE_CONTACTS; READ_ and WRITE_CALENDAR).
 */
class ManifestTest {

    private val manifest by lazy { File(RepoFiles.main, "AndroidManifest.xml").readText() }
    private val gradle by lazy { File(RepoFiles.root, "app/build.gradle").readText() }
    private val sources by lazy { RepoFiles.mainKotlin().associate { RepoFiles.relative(it) to it.readText() } }

    /** The one file that may hold a foreground service and post its notification. */
    private val serviceFile = "app/src/main/java/cloud/dopp/homes/realtime/LiveService.kt"

    /** The one file that may name WorkManager: the backup job (#194). */
    private val workerFile = "app/src/main/java/cloud/dopp/homes/sicherung/SicherungWorker.kt"

    /** The Hochladen job (owner, 2026-11-04): its worker, its foreground and its notification, in one file. */
    private val hochladenFile = "app/src/main/java/cloud/dopp/homes/hochladen/Hochladen.kt"

    /** The source manifest's entries that take a library's entry out of the merged one. */
    private val removed = Regex("""<(service|receiver|provider|uses-permission)\b[^>]*tools:node="remove"[^>]*/>""")

    /** The manifest as Gradle merged it with the libraries', as the unit tests' Robolectric reads it. */
    private val merged by lazy {
        val props = java.util.Properties()
        javaClass.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties")!!.use { props.load(it) }
        val path = props.getProperty("android_merged_manifest")!!
        val f = File(path).let { if (it.isAbsolute) it else File(RepoFiles.root, "app/$path") }
        f.readText()
    }

    private fun permissions(xml: String): Set<String> =
        Regex("""<uses-permission[^>]*android:name="([^"]+)"""").findAll(xml.replace(removed, ""))
            .map { it.groupValues[1].removePrefix("android.permission.") }.toSet()

    private fun noneSay(vararg needles: String, except: Set<String> = emptySet()): List<String> =
        sources.filterKeys { it !in except }
            .flatMap { (path, text) -> needles.filter { text.contains(it) }.map { "$path: $it" } }

    private val ours = setOf(
        "INTERNET",
        "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_SPECIAL_USE", // the live service
        "FOREGROUND_SERVICE_DATA_SYNC", // the Hochladen job while it uploads (owner, 2026-11-04)
        "RECEIVE_BOOT_COMPLETED", // its restart after boot
        "POST_NOTIFICATIONS", // its notification, shown on Android 13+
        // its restart after the process died: on the exemption list a restart may go foreground
        "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        // Kontakte, SMS und Anrufe (laib ADR 0029): read only, each behind its own switch
        "READ_CONTACTS", "READ_SMS", "READ_CALL_LOG",
        // Standortverlauf (owner 2026-11-02): behind its own switch
        "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
        "ACCESS_NETWORK_STATE", // whether the phone is on a Wi-Fi
        // Das Homes-Konto (owner 2026-11-03, #206): into its own account only, each part behind its own switch
        "WRITE_CONTACTS", "READ_CALENDAR", "WRITE_CALENDAR", "READ_SYNC_SETTINGS", "WRITE_SYNC_SETTINGS",
        "READ_SYNC_STATS", // whether a sync runs, for the line under „Jetzt abgleichen“
    )

    /**
     * Exactly these permissions: the network, what the live service needs,
     * reading contacts, SMS and calls, and the Homes account's contacts and
     * calendars. No camera of the app's own: its capture screen is gone
     * (owner, 2026-11-02); the QR scanner brings its own. No storage, and
     * nothing that sends messages.
     */
    @Test
    fun thePermissionsAreExactlyTheseAndNoOthers() {
        assertEquals(ours, permissions(manifest))
    }

    /**
     * What the libraries add, and nothing more: WorkManager (the backup job,
     * #194) brings its job service and, for it, the wake lock and the network
     * state; androidx.core its receiver guard; the QR scanner of the pairing
     * (zxing-android-embedded) the camera it scans with. No storage permission
     * in any form.
     */
    @Test
    fun theMergedManifestAddsOnlyWorkManagersOwn() {
        assertEquals(
            ours + setOf("WAKE_LOCK", "cloud.dopp.homes.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", "CAMERA"),
            permissions(merged),
        )
        listOf("STORAGE", "READ_MEDIA", "ACCESS_MEDIA_LOCATION", "SEND_SMS", "WRITE_CALL_LOG", "RECEIVE_SMS", "WRITE_SMS").forEach { word ->
            assertFalse("a storage permission: $word", permissions(merged).any { it.contains(word) })
        }
        val foreground = Regex("""android:foregroundServiceType="([^"]+)"""").findAll(merged).map { it.groupValues[1] }.toList()
        assertEquals("two foreground services, the live one and the uploads'", 2, foreground.size)
        assertEquals(setOf("specialUse", "dataSync"), foreground.toSet())
        val wm = merged.substringAfter("androidx.work.impl.foreground.SystemForegroundService").substringBefore("/>")
        assertTrue("WorkManager's foreground service runs the uploads as a data sync: $wm", wm.contains("""android:foregroundServiceType="dataSync""""))
        assertTrue("the job runs through JobScheduler", merged.contains("androidx.work.impl.background.systemjob.SystemJobService"))
    }

    /**
     * Two foreground services: the live widgets', `specialUse`, with the reason
     * Play asks for, always there; and WorkManager's, `dataSync`, for the
     * Hochladen job only while it uploads (owner, 2026-11-04).
     */
    @Test
    fun theLiveForegroundServiceAndTheUploadsAndTheWidgetLists() {
        val services = Regex("""<service\b[^>]*>""").findAll(manifest.replace(removed, "")).map { it.value }.toList()
        val foreground = services.filter { it.contains("foregroundServiceType") }
        assertEquals("foreground services: $foreground", 2, foreground.size)
        val upload = foreground.single { it.contains("androidx.work.impl.foreground.SystemForegroundService") }
        assertTrue(upload, upload.contains("""android:foregroundServiceType="dataSync""""))
        val fgs = foreground.single { it != upload }
        assertTrue(fgs, fgs.contains("""android:name=".realtime.LiveService""""))
        assertTrue(fgs, fgs.contains("""android:foregroundServiceType="specialUse""""))
        assertTrue(fgs, fgs.contains("""android:exported="false""""))
        val block = manifest.substringAfter(fgs).substringBefore("</service>")
        assertTrue("no special-use subtype", block.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
        assertTrue("the subtype does not say what it is for", block.contains("live widget connection to the user's own Homes server"))
        val konto = services.filter { it.contains(".konto.") }
        assertEquals(
            "the Homes account's three services (#206)",
            listOf(".konto.KontoDienst", ".konto.KontakteSyncDienst", ".konto.KalenderSyncDienst"),
            konto.map { Regex("""android:name="([^"]+)"""").find(it)!!.groupValues[1] },
        )
        (services - fgs - upload - konto.toSet()).forEach { assertTrue("$it is not a widget list", it.contains("RemoteViewsService\"")) }
        // dataSync is capped at 6 h a day (Android 15): never the live widgets', only the uploads' (the job says when it runs out)
        assertEquals("dataSync only for the uploads", 1, Regex("""foregroundServiceType="dataSync"""").findAll(manifest).count())
    }

    /**
     * The Homes account (#206): its services are bound by Android only (the
     * account manager, the sync manager), and its sync adapters take Homes'
     * data in and never send anything back.
     */
    @Test
    fun theHomesAccountIsBoundByAndroidAndWritable() {
        val own = manifest.replace(removed, "")
        fun dienst(name: String) = own.substringAfter("""android:name=".konto.$name"""").substringBefore("</service>")
        assertTrue(dienst("KontoDienst").contains("""android:permission="android.permission.ACCOUNT_MANAGER""""))
        assertTrue(dienst("KontoDienst").contains("android.accounts.AccountAuthenticator"))
        listOf("KontakteSyncDienst" to "kontakte_sync", "KalenderSyncDienst" to "kalender_sync").forEach { (name, xml) ->
            val d = dienst(name)
            assertTrue(d, d.contains("""android:permission="android.permission.BIND_SYNC_ADAPTER""""))
            assertTrue(d, d.contains("@xml/$xml"))
            val adapter = File(RepoFiles.main, "res/xml/$xml.xml").readText()
            assertTrue("$xml sends the phone's changes back (owner, 2026-11-05)", adapter.contains("""android:supportsUploading="true""""))
            assertTrue(adapter.contains("""android:accountType="cloud.dopp.homes""""))
        }
        assertTrue("the contacts app edits the account", File(RepoFiles.main, "res/xml/kontakte_konto.xml").readText().contains("<EditSchema>"))
        val kalender = sources.getValue("app/src/main/java/cloud/dopp/homes/konto/KalenderAbgleich.kt")
        assertTrue(
            "the calendars Homes lets the phone write are its own, the others read only",
            kalender.contains("if (k.schreibbar) Calendars.CAL_ACCESS_OWNER else Calendars.CAL_ACCESS_READ"),
        )
        val konto = sources.getValue("app/src/main/java/cloud/dopp/homes/konto/HomesKonto.kt")
        assertTrue("hourly at most", konto.contains("STUNDE = 60L * 60L") && konto.contains("addPeriodicSync(konto, teil.authority, Bundle.EMPTY, STUNDE)"))
    }

    /**
     * Only the Homes account writes contacts or calendars, and only as its
     * sync adapter into its own account: every URI it writes is built by
     * `alsAdapter`. Nothing else in the app writes either.
     */
    @Test
    fun onlyTheHomesAccountWritesContactsOrCalendars() {
        val schreibt = listOf("applyBatch", "newInsert", "newUpdate", "newDelete", ".insert(", ".update(", ".delete(", "bulkInsert")
        val konto = setOf(
            "app/src/main/java/cloud/dopp/homes/konto/KontakteAbgleich.kt",
            "app/src/main/java/cloud/dopp/homes/konto/KalenderAbgleich.kt",
            "app/src/main/java/cloud/dopp/homes/konto/HomesKonto.kt",
        )
        // Kontakt prüfen lassen (owner 2026-11-07): the person's own contacts, by an accepted correction only
        val korrektur = "app/src/main/java/cloud/dopp/homes/sicherung/HandyKorrektur.kt"
        val fremd = sources.filterKeys { it !in konto && it != korrektur }.filterValues { t -> t.contains("ContactsContract") || t.contains("CalendarContract") }
            .flatMap { (p, t) -> schreibt.filter { t.contains(it) }.map { "$p: $it" } }
        assertTrue("writes contacts or calendars outside the Homes account:\n" + fremd.joinToString("\n"), fremd.isEmpty())
        konto.forEach { p ->
            val t = sources.getValue(p)
            Regex("""(?:newInsert|newUpdate|newDelete|\.insert|\.update|\.delete)\(([^,)]*)""").findAll(t).forEach { m ->
                val ziel = m.groupValues[1].trim()
                assertTrue("$p writes $ziel not as the account's sync adapter", ziel.startsWith("alsAdapter(") || ziel.startsWith("zeile") || ziel == "uri" || ziel.startsWith("KontakteAbgleich.alsAdapter(") || ziel.startsWith("KalenderAbgleich.alsAdapter("))
            }
        }
    }

    /**
     * The one writer of a person's own contacts (Kontakt prüfen lassen, owner
     * 2026-11-07): data rows only, never a raw contact or a contact, never the
     * calendar, never as a sync adapter (the person's account syncs the edit),
     * and only the Homes account's raw contacts left out; it is called by the
     * corrections' run alone.
     */
    @Test
    fun aPersonsOwnContactIsWrittenOnlyByAnAcceptedCorrection() {
        val t = sources.getValue("app/src/main/java/cloud/dopp/homes/sicherung/HandyKorrektur.kt")
        Regex("""(?:newInsert|newUpdate|newDelete)\(([^)]*)\)""").findAll(t).forEach { m ->
            val ziel = m.groupValues[1].trim()
            assertTrue("HandyKorrektur writes $ziel, not a data row", ziel == "ContactsContract.Data.CONTENT_URI" || ziel.startsWith("zeile("))
        }
        assertTrue("not as the app", !t.contains("alsAdapter") && !t.contains("CALLER_IS_SYNCADAPTER") && !t.contains("CalendarContract"))
        assertTrue("the Homes account's raw contacts are written", t.contains("ACCOUNT_TYPE} != ?") && t.contains("HomesKonto.TYP") && t.contains("RAW_CONTACT_IS_READ_ONLY} = 0"))
        val callers = sources.filter { (p, s) -> p != "app/src/main/java/cloud/dopp/homes/sicherung/HandyKorrektur.kt" && s.contains("HandyKorrektur(") }.keys
        assertEquals("who builds the writer", setOf("app/src/main/java/cloud/dopp/homes/sicherung/SicherungWorker.kt"), callers)
    }

    /** One receiver restarts the live service after a boot and after an app update, both allowed starts. */
    @Test
    fun theBootReceiverRestartsTheLiveServiceOnly() {
        assertEquals(1, Regex("""android.intent.action.BOOT_COMPLETED""").findAll(manifest).count())
        assertEquals(1, Regex("""android.intent.action.MY_PACKAGE_REPLACED""").findAll(manifest).count())
        val receiver = manifest.substringAfter("""android:name=".realtime.BootReceiver"""").substringBefore("</receiver>")
        assertTrue(receiver, receiver.contains("android.intent.action.BOOT_COMPLETED"))
        assertTrue(receiver, receiver.contains("android.intent.action.MY_PACKAGE_REPLACED"))
    }

    /**
     * The fed widgets without a configure screen of their own get the shared
     * no-UI one, required, so placing one from the launcher is a visible
     * activity — an allowed start for the live service on Android 12+.
     */
    @Test
    fun theFedWidgetsWithoutAScreenStartTheServiceWhenPlaced() {
        listOf("energy_widget_info.xml").forEach { name ->
            val info = File(RepoFiles.main, "res/xml/$name").readText()
            assertTrue(name, info.contains("""android:configure="cloud.dopp.homes.widget.LiveConfigActivity""""))
            assertFalse("$name: an optional configure step is skipped at placement", info.contains("configuration_optional"))
        }
        val activity = manifest.substringAfter("""android:name=".widget.LiveConfigActivity"""").substringBefore("</activity>")
        assertTrue(activity, activity.contains("android.appwidget.action.APPWIDGET_CONFIGURE"))
        assertTrue(activity, activity.contains("Translucent"))
    }

    /** No widget asks Android to wake it: the live service resyncs on screen-on. */
    @Test
    fun noWidgetPolls() {
        val infos = File(RepoFiles.main, "res/xml").listFiles()!!.filter { it.name.endsWith("_widget_info.xml") }
        assertTrue("no widget info files", infos.isNotEmpty())
        val polling = infos.mapNotNull { f ->
            val period = Regex("""android:updatePeriodMillis="(\d+)"""").find(f.readText())?.groupValues?.get(1)
            if (period != "0") "${f.name}: ${period ?: "not set"}" else null
        }
        assertTrue("widgets that poll:\n" + polling.joinToString("\n"), polling.isEmpty())
    }

    @Test
    fun oneWorkerLibraryAndNoWebShellInTheBuild() {
        val work = Regex("""implementation\s+'(androidx\.work:[^:']+)""").findAll(gradle).map { it.groupValues[1] }.toList()
        assertEquals("WorkManager for the backup and the Hochladen job only (#194, owner 2026-11-04)", listOf("androidx.work:work-runtime"), work)
        assertFalse("the Bubblewrap TWA block", gradle.contains("twaManifest"))
        assertFalse("android-browser-helper", gradle.contains("androidbrowserhelper"))
    }

    /**
     * Two notifications: the live service's, and the Hochladen job's
     * (owner, 2026-11-04); each in its one file, and only those two go foreground.
     */
    @Test
    fun noOtherNotificationNoWebShellNoVoiceInTheSources() {
        val hits = noneSay(
            "NotificationCompat", "NotificationManager", "NotificationChannel",
            except = setOf(serviceFile, hochladenFile),
        ) + noneSay("startForeground", except = setOf(serviceFile)) + noneSay(
            "TrustedWebActivity", "androidx.browser.trusted", "RecognizerIntent", "SpeechRecognizer",
        ) + noneSay("DATA_SYNC", "dataSync", except = setOf(hochladenFile))
        assertTrue("left in the sources:\n" + hits.joinToString("\n"), hits.isEmpty())
        assertTrue("the live service has no notification", sources[serviceFile]?.contains("startForeground") == true)
    }

    /**
     * Nothing that runs the app later or keeps the CPU up — no job (JobScheduler),
     * no alarm (AlarmManager), no wakelock (PowerManager) — but two workers, each
     * in its one file: the backup (#194), which never goes foreground, and
     * Hochladen (owner, 2026-11-04), in front only while it uploads. Asking
     * PowerManager whether the screen is on is fine; holding it awake is not.
     */
    @Test
    fun noJobNoAlarmNoWakelockAndOneWorkerInTheSources() {
        val hits = noneSay(
            "JobScheduler", "JobService", "JobInfo", "JOB_SCHEDULER_SERVICE",
            "AlarmManager", "ALARM_SERVICE", "setExactAndAllowWhileIdle", "setAndAllowWhileIdle", "setAlarmClock",
            "newWakeLock", "WakeLock", "PARTIAL_WAKE_LOCK", "acquire(",
            "CoroutineWorker",
        ) + noneSay("setForeground", "ForegroundInfo", except = setOf(hochladenFile)) +
            noneSay("androidx.work", "WorkManager", "WorkRequest", except = setOf(workerFile, hochladenFile))
        assertTrue("left in the sources:\n" + hits.joinToString("\n"), hits.isEmpty())
        val worker = sources.getValue(workerFile)
        assertEquals("two workers", 2, Regex(""": Worker\(""").findAll(sources.values.joinToString("\n")).count())
        assertTrue(worker.contains("class SicherungWorker(") && worker.contains(": Worker("))
        assertTrue(sources.getValue(hochladenFile).contains("class HochladenWorker("))
        assertFalse("the backup never goes foreground", worker.contains("setForeground"))
    }

    /**
     * Standortverlauf's receiver is reached by the app's own PendingIntents
     * only, and only Aufzeichnung asks Android for locations — by
     * PendingIntent, never a one-off poll (no single update, no current
     * location, no listener that needs the process).
     */
    @Test
    fun theLocationComesByPendingIntentToOneHiddenReceiver() {
        val receiver = manifest.substringAfter("""android:name=".standort.StandortEmpfaenger"""").substringBefore("/>")
        assertTrue(receiver, receiver.contains("""android:exported="false""""))
        assertFalse(receiver, receiver.contains("intent-filter"))
        val aufzeichnung = "app/src/main/java/cloud/dopp/homes/standort/Aufzeichnung.kt"
        val hits = noneSay("requestLocationUpdates", "LocationManager", "requestTriggerSensor", except = setOf(aufzeichnung)) +
            noneSay("requestSingleUpdate", "getCurrentLocation", "LocationListener")
        assertTrue("left in the sources:\n" + hits.joinToString("\n"), hits.isEmpty())
        val calls = Regex("""requestLocationUpdates\(([^)]*)\)""").findAll(sources.getValue(aufzeichnung)).map { it.groupValues[1] }.toList()
        assertTrue("no request at all", calls.isNotEmpty())
        calls.forEach { assertTrue("a request not by PendingIntent: $it", it.trim().endsWith("haupt") || it.trim().endsWith("passiv")) }
    }

    /**
     * No Google Play services (the owner, 2026-11-02): not in the build, not
     * in the merged manifest, not in the sources. The app never needed them;
     * a phone without them works the same.
     */
    @Test
    fun noGooglePlayServices() {
        val gradles = RepoFiles.walk().filter { it.name.endsWith(".gradle") || it.name.endsWith(".gradle.kts") || it.name.endsWith(".toml") }
        assertTrue("no build files found", gradles.any { it.name == "build.gradle" })
        val inBuild = gradles.filter { f -> f.readText().let { it.contains("com.google.android.gms") || it.contains("play-services") || it.contains("com.google.firebase") } }
            .map { RepoFiles.relative(it) }
        assertTrue("Google Play services in the build: $inBuild", inBuild.isEmpty())
        assertFalse("Google Play services in the merged manifest", merged.contains("com.google.android.gms"))
        val inSources = noneSay("com.google.android.gms", "com.google.firebase")
        assertTrue("left in the sources:\n" + inSources.joinToString("\n"), inSources.isEmpty())
    }

    @Test
    fun noJobServiceOrAlarmReceiverInTheManifest() {
        // WorkManager's foreground service is named once, given its data-sync type for the Hochladen job (owner, 2026-11-04)
        val wm = Regex("""<service\s+android:name="androidx\.work\.impl\.foreground\.SystemForegroundService"[^>]*/>""")
        assertEquals(1, wm.findAll(manifest).count())
        val own = manifest.replace(removed, "").replace(wm, "")
        listOf("BIND_JOB_SERVICE", "SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM", "androidx.work", "WAKE_LOCK").forEach {
            assertFalse("manifest names $it", own.contains(it))
        }
        assertTrue("nothing a library brings is taken out any more", removed.findAll(manifest).toList().isEmpty())
    }

    /**
     * The backup folders come through the system picker and a persisted read
     * grant (#194): no broad storage access, and nothing in the app writes,
     * moves or deletes a document in WhatsApp's or Signal's folder — except a
     * restore on a new phone (#197): [SafZiel] alone makes folders and files
     * and writes them, in a folder granted for that through the same picker,
     * and never deletes, renames, moves or copies one.
     */
    @Test
    fun theFoldersAreReadThroughThePickerOnly() {
        val writer = "app/src/main/java/cloud/dopp/homes/sicherung/SafZiel.kt"
        val hits = noneSay(
            "MANAGE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "getExternalStorageDirectory",
            "deleteDocument", "renameDocument", "moveDocument", "copyDocument", "removeDocument", "openOutputStream",
        ) + noneSay("createDocument", "FLAG_GRANT_WRITE_URI_PERMISSION", except = setOf(writer))
        assertTrue("left in the sources:\n" + hits.joinToString("\n"), hits.isEmpty())
        val saf = sources.getValue("app/src/main/java/cloud/dopp/homes/sicherung/SafOrdner.kt")
        assertTrue(saf.contains("takePersistableUriPermission(baum, Intent.FLAG_GRANT_READ_URI_PERMISSION)"))
        val modes = Regex("""openFileDescriptor\([^)]*"(\w+)"\)""")
        assertEquals("read only", setOf("r"), modes.findAll(saf).map { it.groupValues[1] }.toSet())
        // only the writer opens a document for writing, and never truncates it whole on open ("w", "wt")
        val schreibt = sources.filter { (_, text) -> modes.findAll(text).any { it.groupValues[1] != "r" } }.keys
        assertEquals(setOf(writer), schreibt)
        assertEquals(setOf("rw"), modes.findAll(sources.getValue(writer)).map { it.groupValues[1] }.toSet())
        val screen = sources.getValue("app/src/main/java/cloud/dopp/homes/ui/SicherungActivity.kt")
        assertTrue(screen.contains("ActivityResultContracts.OpenDocumentTree()"))
        val restore = sources.getValue("app/src/main/java/cloud/dopp/homes/ui/ZurueckholenActivity.kt")
        assertTrue(restore.contains("ActivityResultContracts.OpenDocumentTree()"))
    }

    /**
     * The widgets the owner took out (2026-11-02, 2026-11-03) are gone from the
     * manifest, so the launcher's picker no longer offers them: all devices,
     * the active devices, the camera, the two energy widgets beside the one that
     * stays, the two Box widgets — with their list service, capture screen and
     * file provider. Their sources and widget infos are gone too. The widget
     * for one device came back (2026-11-03).
     */
    @Test
    fun theRemovedWidgetsAreNotInTheManifest() {
        val gone = listOf(
            "DevicesWidgetProvider", "ActiveDevicesWidgetProvider", "CameraWidgetProvider",
            "EnergyStatsWidgetProvider", "EnergyTotalsWidgetProvider", "BoxOverviewWidgetProvider", "BoxServicesWidgetProvider",
            "ActiveDevicesRemoteViewsService", "CameraCaptureActivity",
        )
        gone.forEach { name ->
            assertFalse("$name is still in the manifest", Regex("""android:name="\.(widget|ui)\.$name"""").containsMatchIn(manifest))
            assertFalse("$name is still in the merged manifest", merged.contains(".$name\""))
            assertTrue("$name.kt is still there", RepoFiles.mainKotlin().none { it.name == "$name.kt" })
        }
        // the capture's file provider went with it; the one left is Dokument scannen's (owner, 2026-11-04)
        assertEquals(1, Regex("""<provider\b""").findAll(manifest).count())
        val provider = manifest.substringAfter("<provider").substringBefore("</provider>")
        assertTrue(provider, provider.contains("androidx.core.content.FileProvider") && provider.contains("""android:exported="false""""))
        assertTrue(provider, provider.contains("""android:authorities="${'$'}{applicationId}.scan""""))
        val pfade = File(RepoFiles.main, "res/xml/scan_pfade.xml").readText()
        assertEquals("only cache/scan/ is shared", listOf("cache-path"), Regex("""<([a-z-]+-path)\b""").findAll(pfade).map { it.groupValues[1] }.toList())
        assertTrue(pfade, pfade.contains("""path="scan/""""))
        assertFalse("the camera", manifest.contains("android.hardware.camera"))
        val providers = Regex("""<receiver\s+android:name="\.widget\.(\w+WidgetProvider)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf("DeviceWidgetProvider", "EnergyWidgetProvider", "ToolWidgetProvider"),
            providers,
        )
        listOf(
            "devices_widget_info", "active_devices_widget_info", "camera_widget_info",
            "energy_stats_widget_info", "energy_totals_widget_info", "box_overview_widget_info", "box_services_widget_info",
        ).forEach { assertFalse("res/xml/$it.xml is still there", File(RepoFiles.main, "res/xml/$it.xml").exists()) }
    }

    /**
     * Which of Homes' module apps the phone has (owner, 2026-11-04): Android 11+
     * answers for the packages the manifest names, one by one — never every
     * package (QUERY_ALL_PACKAGES is not among [ours]).
     */
    @Test
    fun theModulesAppsAreAskedForByName() {
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")
        val pakete = Regex("""<package android:name="([^"]+)"""").findAll(queries).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "app.alextran.immich", "org.jellyfin.mobile", "com.x8bit.bitwarden", "com.audiobookshelf.app",
                "org.thoughtcrime.securesms", "io.homeassistant.companion.android",
            ),
            pakete,
        )
        assertFalse(permissions(merged).contains("QUERY_ALL_PACKAGES"))
    }

    @Test
    fun noCatchUpNoApprovalsNoDiagnostics() {
        val gone = listOf("Notice", "Approvals", "Diagnostics", "RealtimePoll", "RealtimeService", "Voice", "Pwa")
        val left = RepoFiles.mainKotlin().map { it.name }.filter { n -> gone.any { n.startsWith(it) } }
        assertTrue("left: $left", left.isEmpty())
        val layouts = File(RepoFiles.main, "res/layout").list()!!.toList()
        val oldLayouts = layouts.filter {
            it.contains("approval") || it.contains("notice") || it.contains("diagnostics") || it.contains("voice")
        }
        assertTrue("layouts left: $oldLayouts", oldLayouts.isEmpty())
    }
}
