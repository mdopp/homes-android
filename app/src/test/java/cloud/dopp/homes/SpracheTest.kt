package cloud.dopp.homes

import android.os.LocaleList
import cloud.dopp.homes.data.HomesHttp
import cloud.dopp.homes.data.Sprache
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The phone's language reaches Homes (the owner's call, 2026-11-18: the
 * language follows the client): every call through [HomesHttp] carries the
 * system's languages as `Accept-Language`, the preferred first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpracheTest {
    private val server = MockWebServer()
    private lateinit var vorher: LocaleList

    @Before
    fun start() {
        vorher = LocaleList.getDefault()
        server.start()
    }

    @After
    fun stop() {
        LocaleList.setDefault(vorher)
        server.shutdown()
    }

    private fun gesendet(vararg sprachen: Locale): String? {
        LocaleList.setDefault(LocaleList(*sprachen))
        server.enqueue(MockResponse().setBody("{}"))
        HomesHttp.client().newCall(Request.Builder().url(server.url("/napi/kann")).build()).execute().close()
        return server.takeRequest().getHeader("Accept-Language")
    }

    @Test
    fun theSystemLanguageGoesWithEveryCall() {
        assertEquals("fr-FR", gesendet(Locale.FRANCE))
        assertEquals("en-US,de-DE;q=0.9", gesendet(Locale.US, Locale.GERMANY))
        assertEquals("ja-JP,es-ES;q=0.9", gesendet(Locale.JAPAN, Locale("es", "ES"))) // Homes picks es by the weights
    }

    @Test
    fun aClientWithItsOwnInterceptorKeepsTheLanguage() {
        LocaleList.setDefault(LocaleList(Locale.ITALY))
        server.enqueue(MockResponse())
        val client = HomesHttp.mit(HomesHttp.client()) { it.proceed(it.request()) }
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
        assertEquals("it-IT", server.takeRequest().getHeader("Accept-Language"))
    }

    @Test
    fun weightsFallAndRepeatsGo() {
        assertEquals(
            "de-DE,en-GB;q=0.9,fr;q=0.8",
            Sprache.acceptLanguage(listOf(Locale.GERMANY, Locale.UK, Locale.GERMANY, Locale.FRENCH, Locale.ROOT)),
        )
        assertEquals("", Sprache.acceptLanguage(emptyList()))
    }
}
