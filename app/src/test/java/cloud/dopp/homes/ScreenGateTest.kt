package cloud.dopp.homes

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import cloud.dopp.homes.ausloeser.Ausloeser
import cloud.dopp.homes.ausloeser.Ladestation
import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.BoxApiClient
import cloud.dopp.homes.data.ScreenGate
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * With the screen off nothing goes over the network for the widgets: not only the
 * stream, every widget request to Homes. A widget update Android delivers after
 * the screen went off posted the watch set on API 31 (live e2e, step 2);
 * [ScreenGate] refuses it before a socket is opened.
 *
 * Two callers go with the screen off on purpose: the backup job (CONTRACT (e)),
 * and — new, the owner's yes of 2026-11-26 (homes ADR 0004,
 * AGENTS.md) — „Ladestation melden“'s one request, only behind its switch.
 * [theChargerReportGoesWithTheScreenOffOnlyBehindItsSwitch] holds that
 * exception to exactly that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenGateTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private fun screen(on: Boolean) =
        shadowOf(ctx().getSystemService(PowerManager::class.java)).setIsInteractive(on)

    @Before
    fun paired() {
        // a port nothing listens on: a request that does go out fails to connect
        ServerStore.setBaseUrl(ctx(), "https://127.0.0.1:1")
        // Robolectric has no keystore: the token goes to plain prefs here.
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_TESTTOKEN123")
    }

    @After
    fun clean() {
        screen(true)
        ServerStore.clear(ctx())
        TokenStore.clear(ctx())
    }

    @Test
    fun screenOffNoRequestGoesOut() {
        screen(false)
        assertFalse(ScreenGate.open(ctx()))
        val refused = runCatching { ApiClient(ctx()).getCard("light.flur") }.exceptionOrNull()
        assertTrue("refused at the gate, not at the socket: $refused", refused is ScreenGate.ScreenOffException)
        assertEquals(0, ApiClient(ctx()).postWatch(ApiClient.watchBody(listOf("sensor.pv"))))
        assertNull(ApiClient(ctx()).favoritesBody())
        assertNull(BoxApiClient(ctx()).getAssistent())
    }

    @Test
    fun screenOnTheRequestGoesOut() {
        screen(true)
        assertTrue(ScreenGate.open(ctx()))
        val failed = runCatching { ApiClient(ctx()).getCard("light.flur") }.exceptionOrNull()
        assertFalse("the gate refused with the screen on", failed is ScreenGate.ScreenOffException)
    }

    /**
     * homes ADR 0004 (the owner's yes of 2026-11-26): with „Ladestation melden“ off, laying the phone
     * on its charger with the screen off sends nothing; with it on, the one report goes, screen off or not.
     */
    @Test
    fun theChargerReportGoesWithTheScreenOffOnlyBehindItsSwitch() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"name":""}"""))
        // the plain test server as the paired Homes (ServerStore keeps https only)
        ctx().getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit()
            .putString("base_url", server.url("/").toString().trimEnd('/')).commit()
        val vorher = Ladestation.senden
        Ladestation.senden = { _, lauf -> lauf() } // in place: the test reads what went
        try {
            screen(false)
            ctx().sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_WIRELESS))
            val ladestation = Ladestation(ctx())

            Ausloeser.setzeLadenMelden(ctx(), false)
            ladestation.anpassen()
            ctx().sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("switch off: nothing goes", server.takeRequest(200, TimeUnit.MILLISECONDS))

            Ausloeser.setzeLadenMelden(ctx(), true)
            ladestation.anpassen()
            ctx().sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
            shadowOf(Looper.getMainLooper()).idle()
            val req = server.takeRequest(5, TimeUnit.SECONDS)
            assertTrue("switch on: the report goes with the screen off", req != null)
            assertEquals("/napi/ausloeser", req!!.path)
            val body = JSONObject(req.body.readUtf8())
            assertEquals("laden", body.getString("art"))
            assertEquals("an", body.getString("zustand"))
            assertEquals("kabellos", body.getString("ladeart"))
            assertFalse("a charger report names no tag", body.has("tag"))
            ladestation.aus()
        } finally {
            Ladestation.senden = vorher
            Ausloeser.setzeLadenMelden(ctx(), false)
            server.shutdown()
        }
    }

    /** Every request of the two clients passes the gate; whoami is the pairing's, made with the person at the screen. */
    @Test
    fun everyRequestPassesTheGate() {
        val api = File(RepoFiles.main, "java/cloud/dopp/homes/data/ApiClient.kt").readLines()
        val bypass = api.filter { it.contains("newCall(") && !it.contains("authed(") && !it.contains("Napi.WHOAMI") }
        assertTrue("ApiClient calls outside authed(): $bypass", bypass.isEmpty())
        val authed = api.dropWhile { !it.contains("private fun authed(") }.take(3).joinToString("\n")
        assertTrue(authed, authed.contains("ScreenGate.check(ctx)"))

        val box = File(RepoFiles.main, "java/cloud/dopp/homes/data/BoxApiClient.kt").readText()
        assertEquals("BoxApiClient has one request", 1, Regex("""newCall\(""").findAll(box).count())
        assertTrue(box.substringAfter("private fun bytes(").substringBefore("newCall(").contains("ScreenGate.open(ctx)"))
    }
}
