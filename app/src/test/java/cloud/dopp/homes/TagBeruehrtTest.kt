package cloud.dopp.homes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Looper
import android.os.Parcelable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.ausloeser.Ausloeser
import cloud.dopp.homes.ausloeser.AusloeserActivity
import cloud.dopp.homes.ausloeser.LeerTagActivity
import cloud.dopp.homes.ausloeser.NfcSchreiber
import cloud.dopp.homes.ausloeser.TagActivity
import cloud.dopp.homes.ausloeser.TagSchreiber
import cloud.dopp.homes.data.TokenStore
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A touched tag however Android hands it over (field finding 2026-11-27, a
 * Samsung on Android 12+: a tag's web address comes as VIEW, not
 * NDEF_DISCOVERED): a VIEW from Android's NFC service is a touch, reported at
 * once; any other VIEW still asks for „Auslösen“. And a blank tag touched
 * anywhere (the owner, 2026-11-27: „kann sich Homes für neue Tags
 * registrieren?“) opens the new-tag form and is written while Android still
 * holds it; a tag with records is not Homes'.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TagBeruehrtTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private companion object {
        const val TAG = "homes-aaaaaaaaaaaa"
        const val NEU = """{"ok":true,"tag":"homes-cccccccccccc","uri":"https://www.dopp.cloud/tag/homes-cccccccccccc","aktion":""}"""
    }

    private inner class Homes : Dispatcher() {
        val anfragen = CopyOnWriteArrayList<String>()
        override fun dispatch(request: RecordedRequest): MockResponse {
            anfragen += "${request.method} ${request.path}"
            return when (request.path) {
                "/napi/ausloeser" -> MockResponse().setBody("""{"ok":true,"name":"Bürotür"}""").setBodyDelay(300, TimeUnit.MILLISECONDS)
                "/napi/ausloeser/tag" -> MockResponse().setBody(NEU)
                "/napi/ausloeser/tags" -> MockResponse().setBody("""{"ok":true,"tags":[],"vorschlaege":[],"aktionen":[]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private val homes = Homes()
    private val server = MockWebServer().also { it.dispatcher = homes }
    private val base get() = server.url("/").toString().trimEnd('/')
    private val host get() = server.url("/").host

    /** NFC that never brings a tag; [direkt] what writing the handed-over tag gives. */
    private class Schreiber : TagSchreiber {
        var direkt: TagSchreiber.Ergebnis? = TagSchreiber.Ergebnis.Fertig
        var uriVon: (() -> String?)? = null
        var geschrieben: Pair<Parcelable, String>? = null
        val uri: String? get() = uriVon?.invoke()
        override fun bereit(ctx: Context) = TagSchreiber.Bereit.JA
        override fun an(activity: Activity, uri: () -> String?, ergebnis: (TagSchreiber.Ergebnis) -> Unit) {
            uriVon = uri
        }
        override fun aus(activity: Activity) {
            uriVon = null
        }
        override fun direkt(tag: Parcelable, uri: String): TagSchreiber.Ergebnis? {
            geschrieben = tag to uri
            return direkt
        }
    }

    private val schreiber = Schreiber()
    private val vorherLesen = LeerTagActivity.lesen

    @Before
    fun gekoppelt() {
        ctx().getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit().putString("base_url", base).commit()
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_TESTTOKEN123")
        AusloeserActivity.schreiber = schreiber
    }

    @After
    fun zu() {
        AusloeserActivity.schreiber = NfcSchreiber
        LeerTagActivity.lesen = vorherLesen
        ctx().getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit().clear().commit()
        TokenStore.clear(ctx())
        runCatching { server.shutdown() }
    }

    private fun warte(bis: () -> Boolean) {
        val ende = System.currentTimeMillis() + 10_000
        while (!bis() && System.currentTimeMillis() < ende) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    // --- a VIEW from Android's NFC service is a touch -------------------------------------

    @Test
    fun theWayAnIntentCame() {
        val nfc = Uri.parse("android-app://com.android.nfc")
        assertEquals("nfc", TagActivity.weg(NfcAdapter.ACTION_NDEF_DISCOVERED, emptySet(), null))
        assertEquals("nfc-view", TagActivity.weg(Intent.ACTION_VIEW, emptySet(), nfc))
        assertEquals("nfc-view", TagActivity.weg(Intent.ACTION_VIEW, setOf(NfcAdapter.EXTRA_TAG), null))
        assertEquals("nfc-view", TagActivity.weg(Intent.ACTION_VIEW, setOf(NfcAdapter.EXTRA_NDEF_MESSAGES), null))
        assertEquals("nfc-view", TagActivity.weg(Intent.ACTION_VIEW, setOf(NfcAdapter.EXTRA_ID), null))
        assertEquals("link", TagActivity.weg(Intent.ACTION_VIEW, emptySet(), Uri.parse("android-app://org.thoughtcrime.securesms")))
        assertEquals("link", TagActivity.weg(Intent.ACTION_VIEW, emptySet(), Uri.parse("https://com.android.nfc/")))
        assertEquals("link", TagActivity.weg(Intent.ACTION_VIEW, setOf("android.intent.extra.REFERRER"), null))
        assertEquals("link", TagActivity.weg(Intent.ACTION_VIEW, emptySet(), null))
        assertEquals("link", TagActivity.weg(null, emptySet(), null))
    }

    private fun geoeffnet(intent: Intent): TagActivity = Robolectric.buildActivity(TagActivity::class.java, intent).setup().get()

    private fun ansicht() = Intent(Intent.ACTION_VIEW, Uri.parse("https://$host/tag/$TAG"))

    private fun TagActivity.zeile() = findViewById<TextView>(R.id.tag_zeile).text.toString()

    private fun meldetSofort(t: TagActivity) {
        assertEquals("reported at once", "Meldet …", t.zeile())
        assertEquals(View.GONE, t.findViewById<Button>(R.id.tag_ausloesen).visibility)
        warte { t.zeile() != "Meldet …" }
        assertEquals("Bürotür – gemeldet", t.zeile())
        assertEquals(1, homes.anfragen.count { it == "POST /napi/ausloeser" })
    }

    private fun fragt(t: TagActivity) {
        assertEquals("Ein Link zu einem Homes-Tag.", t.zeile())
        assertEquals(View.VISIBLE, t.findViewById<Button>(R.id.tag_ausloesen).visibility)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("nothing reported", homes.anfragen.none { it == "POST /napi/ausloeser" })
    }

    @Test
    fun aViewFromTheNfcServiceIsReportedAtOnce() =
        meldetSofort(geoeffnet(ansicht().putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://com.android.nfc"))))

    @Test
    fun aViewWithAnNfcExtraIsReportedAtOnce() =
        meldetSofort(geoeffnet(ansicht().putExtra(NfcAdapter.EXTRA_ID, byteArrayOf(1, 2, 3, 4))))

    @Test
    fun aViewFromAChatAsks() =
        fragt(geoeffnet(ansicht().putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://org.thoughtcrime.securesms"))))

    @Test
    fun aViewFromNobodyKnownAsks() = fragt(geoeffnet(ansicht()))

    // --- a blank tag touched anywhere ----------------------------------------------------

    @Test
    fun whatIsBlank() {
        assertTrue("formattable", TagSchreiber.leer(formatierbar = true, ndef = false, nachricht = null))
        assertTrue("Ndef without a message", TagSchreiber.leer(formatierbar = false, ndef = true, nachricht = null))
        val leer = NdefMessage(arrayOf(NdefRecord(NdefRecord.TNF_EMPTY, null, null, null)))
        assertTrue("Ndef with an empty record", TagSchreiber.leer(formatierbar = false, ndef = true, nachricht = leer))
        val voll = NdefMessage(arrayOf(NdefRecord.createUri("https://example.org/x")))
        assertFalse("records: not ours", TagSchreiber.leer(formatierbar = false, ndef = true, nachricht = voll))
        assertFalse("neither", TagSchreiber.leer(formatierbar = false, ndef = false, nachricht = null))
    }

    /** [formatierbar]: a factory-new sticker without NDEF yet, its memory read empty; else an Ndef tag with [nachricht]. */
    private fun beruehrtLeer(formatierbar: Boolean, nachricht: NdefMessage? = null): LeerTagActivity = beruehrt(
        if (formatierbar) {
            cloud.dopp.homes.ausloeser.Befund(ndef = false, formatierbar = true, speicherLeer = true, karte = cloud.dopp.homes.ausloeser.Karte("NTAG213", null))
        } else {
            cloud.dopp.homes.ausloeser.Befund(ndef = true, nachricht = nachricht)
        },
    )

    private fun beruehrt(b: cloud.dopp.homes.ausloeser.Befund): LeerTagActivity {
        LeerTagActivity.lesen = { LeerTagActivity.Gelesen(Bundle(), b) }
        return Robolectric.buildActivity(LeerTagActivity::class.java, Intent(NfcAdapter.ACTION_TECH_DISCOVERED)).setup().get()
    }

    @Test
    fun aCardWithDataOfItsOwnNeverOpensTheForm() {
        val zugang = cloud.dopp.homes.ausloeser.Befund(
            ndef = false, formatierbar = true, speicherLeer = false,
            karte = cloud.dopp.homes.ausloeser.Karte("MIFARE Classic 1K", byteArrayOf(1, 2, 3, 4)),
        )
        val l = beruehrt(zugang)
        assertNull("no form for it", shadowOf(l).nextStartedActivity)
        assertEquals("Das ist keine leere NFC-Karte.", ShadowToast.getTextOfLatestToast())
        val unklar = cloud.dopp.homes.ausloeser.Befund(ndef = false, formatierbar = true, karte = cloud.dopp.homes.ausloeser.Karte("NFC-A", null))
        assertNull("not sure: not here either", shadowOf(beruehrt(unklar)).nextStartedActivity)
        val unlesbar = beruehrt(cloud.dopp.homes.ausloeser.Befund(ndef = true, unlesbar = true))
        assertNull(shadowOf(unlesbar).nextStartedActivity)
        assertEquals("Tag nicht lesbar – halte ihn ruhig noch einmal ans Telefon.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun aBlankTagOpensTheNewTagFormAndIsWrittenAtOnce() {
        val l = beruehrtLeer(formatierbar = true)
        assertTrue(l.isFinishing)
        val weiter = shadowOf(l).nextStartedActivity
        assertEquals(AusloeserActivity::class.java.name, weiter.component?.className)
        val a = Robolectric.buildActivity(AusloeserActivity::class.java, weiter).setup().get()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.ausloeser_formular).visibility)
        assertEquals("Neuer Tag erkannt – wie soll er heißen?", a.findViewById<TextView>(R.id.ausloeser_name_titel).text.toString())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the name asked for", a.findViewById<EditText>(R.id.ausloeser_name).hasFocus())
        a.findViewById<EditText>(R.id.ausloeser_name).setText("Flur")
        a.findViewById<Button>(R.id.ausloeser_schreiben).performClick()
        val stand = a.findViewById<TextView>(R.id.ausloeser_stand)
        warte { stand.text.startsWith("„Flur“ ist fertig") }
        assertEquals("written without holding it again", "https://www.dopp.cloud/tag/homes-cccccccccccc", schreiber.geschrieben?.second)
        assertTrue(stand.text.toString(), stand.text.startsWith("„Flur“ ist fertig"))
        assertEquals(View.GONE, a.findViewById<View>(R.id.ausloeser_formular).visibility)
    }

    @Test
    fun anEmptyNdefTagIsBlankToo() {
        val l = beruehrtLeer(formatierbar = false, nachricht = null)
        assertEquals(AusloeserActivity::class.java.name, shadowOf(l).nextStartedActivity.component?.className)
    }

    @Test
    fun aBlankTagGoneMeanwhileIsAskedForAgain() {
        schreiber.direkt = null
        val weiter = shadowOf(beruehrtLeer(formatierbar = true)).nextStartedActivity
        val a = Robolectric.buildActivity(AusloeserActivity::class.java, weiter).setup().get()
        a.findViewById<EditText>(R.id.ausloeser_name).setText("Flur")
        a.findViewById<Button>(R.id.ausloeser_schreiben).performClick()
        val stand = a.findViewById<TextView>(R.id.ausloeser_stand)
        warte { stand.text.toString() == "Halte „Flur“ noch einmal ans Telefon …" }
        assertEquals("Halte „Flur“ noch einmal ans Telefon …", stand.text.toString())
        assertEquals("still waiting for it", "https://www.dopp.cloud/tag/homes-cccccccccccc", schreiber.uri)
    }

    @Test
    fun aTagWithRecordsIsNotOurs() {
        val l = beruehrtLeer(formatierbar = false, nachricht = NdefMessage(arrayOf(NdefRecord.createUri("https://example.org/x"))))
        assertTrue(l.isFinishing)
        assertNull("nothing shows", shadowOf(l).nextStartedActivity)
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun aBlankTagUnpairedSaysSo() {
        TokenStore.clear(ctx())
        val l = beruehrtLeer(formatierbar = true)
        assertTrue(l.isFinishing)
        assertNull(shadowOf(l).nextStartedActivity)
        assertEquals(Ausloeser.NICHT_GEKOPPELT, ShadowToast.getTextOfLatestToast())
    }
}
