package cloud.dopp.homes

import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.Assistent
import cloud.dopp.homes.data.BoxApiClient
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.ui.HomeActivity
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The start screen's claim (the owner, 2026-11-27): „Deinem Zuhause geht es
 * gut.“ only while `GET /napi/box/home` says `gut: true`; false, absent (an
 * older Homes), failed or with the screen off: the neutral „Dein Zuhause,
 * klar im Blick.“ — which also stands until Homes answered. Read when the
 * screen opens or comes back, never in the background.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClaimTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private var home: () -> MockResponse = { MockResponse().setBody("""{"state":"bereit","gut":true}""") }
    private val anfragen = CopyOnWriteArrayList<String>()
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                anfragen += request.path.orEmpty()
                return if (request.path == "/napi/box/home") home() else MockResponse().setResponseCode(404)
            }
        }
    }

    @Before
    fun gekoppelt() {
        shadowOf(ctx().getSystemService(PowerManager::class.java)).setIsInteractive(true)
        ctx().getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit()
            .putString("base_url", server.url("/").toString().trimEnd('/')).commit()
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_TESTTOKEN123")
        Assistent.save(ctx(), "Homes")
    }

    @After
    fun zu() {
        shadowOf(ctx().getSystemService(PowerManager::class.java)).setIsInteractive(true)
        ctx().getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit().clear().commit()
        TokenStore.clear(ctx())
        runCatching { server.shutdown() }
    }

    private fun claim(a: HomeActivity) = a.findViewById<TextView>(R.id.home_claim).text.toString()

    /** The start screen, and the claim once Homes was asked: [nachher] waits for that answer. */
    private fun start(nachher: (HomeActivity) -> Boolean): HomeActivity {
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        val ende = System.currentTimeMillis() + 10_000
        while (!(anfragen.contains("/napi/box/home") && nachher(a)) && System.currentTimeMillis() < ende) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        Thread.sleep(300) // the answer is drawn on the main thread
        shadowOf(Looper.getMainLooper()).idle()
        return a
    }

    @Test
    fun allIsWellSaysSo() {
        val a = start { claim(it) == "Deinem Zuhause geht es gut." }
        assertEquals("Deinem Zuhause geht es gut.", claim(a))
    }

    @Test
    fun notWellStaysNeutral() {
        home = { MockResponse().setBody("""{"state":"bereit","gut":false}""") }
        assertEquals("Dein Zuhause, klar im Blick.", claim(start { true }))
    }

    @Test
    fun anOlderHomesWithoutGutStaysNeutral() {
        home = { MockResponse().setBody("""{"state":"bereit","servicesUp":12}""") }
        assertEquals("Dein Zuhause, klar im Blick.", claim(start { true }))
    }

    @Test
    fun aFailedReadStaysNeutral() {
        home = { MockResponse().setResponseCode(502).setBody("""{"error":"Laib antwortet nicht."}""") }
        assertEquals("Dein Zuhause, klar im Blick.", claim(start { true }))
    }

    @Test
    fun theClientIsTrueOnlyForGutTrue() {
        assertTrue(BoxApiClient(ctx()).gut())
        home = { MockResponse().setBody("""{"gut":"ja"}""") }
        assertFalse("only a true", BoxApiClient(ctx()).gut())
        home = { MockResponse().setBody("kaputt") }
        assertFalse(BoxApiClient(ctx()).gut())
        shadowOf(ctx().getSystemService(PowerManager::class.java)).setIsInteractive(false)
        home = { MockResponse().setBody("""{"gut":true}""") }
        assertFalse("screen off: not asked", BoxApiClient(ctx()).gut())
    }
}
