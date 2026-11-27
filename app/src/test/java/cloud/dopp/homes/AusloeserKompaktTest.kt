package cloud.dopp.homes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.ausloeser.Ausloeser
import cloud.dopp.homes.ausloeser.AusloeserActivity
import cloud.dopp.homes.ausloeser.AusloeserClient
import cloud.dopp.homes.ausloeser.AusloeserClient.Ausgang
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * The Auslöser screen kept short (owner 2026-11-27: „Neu nimmt zu viel Platz
 * ein“): the tags first, the new-tag form closed behind the list's last row
 * „+ Neuen Tag beschreiben“, opened in place by it or by a blank tag held to
 * the phone, closed by „Abbrechen“ and after it was done; and what a tag did
 * last (homes ADR 0004 §9): `zuletzt` under its name, the nfc answer's `text`
 * on the touched tag's line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AusloeserKompaktTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private companion object {
        const val TAG = "homes-aaaaaaaaaaaa"
        const val NEU = """{"ok":true,"tag":"homes-cccccccccccc","uri":"https://www.dopp.cloud/tag/homes-cccccccccccc","aktion":""}"""
        val UTC: TimeZone = TimeZone.getTimeZone("UTC")

        /** [ms] as Homes writes a time, RFC 3339 in UTC. */
        fun rfc(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = UTC }.format(Date(ms))
    }

    private inner class Homes : Dispatcher() {
        var melden: () -> MockResponse = { MockResponse().setBody("""{"ok":true,"name":"Bürotür"}""") }
        var zuletzt = ""

        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
            "/napi/ausloeser" -> melden()
            "/napi/ausloeser/tag" -> MockResponse().setBody(NEU)
            "/napi/ausloeser/tags" -> MockResponse().setBody(
                """{"ok":true,"tags":[{"tag":"$TAG","name":"Bürotür","aktion":"","automationen":[]$zuletzt}],"vorschlaege":[],"aktionen":[]}""",
            )
            else -> MockResponse().setResponseCode(404)
        }
    }

    private val homes = Homes()
    private val server = MockWebServer().also { it.dispatcher = homes }
    private val base get() = server.url("/").toString().trimEnd('/')

    private class Schreiber : TagSchreiber {
        var uriVon: (() -> String?)? = null
        var ergebnis: ((TagSchreiber.Ergebnis) -> Unit)? = null
        val uri: String? get() = uriVon?.invoke()
        override fun bereit(ctx: Context) = TagSchreiber.Bereit.JA
        override fun an(activity: Activity, uri: () -> String?, ergebnis: (TagSchreiber.Ergebnis) -> Unit) {
            uriVon = uri
            this.ergebnis = ergebnis
        }
        override fun aus(activity: Activity) {
            uriVon = null
            ergebnis = null
        }
    }

    private val schreiber = Schreiber()

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

    private fun bildschirm(): AusloeserActivity {
        val a = Robolectric.buildActivity(AusloeserActivity::class.java).setup().get()
        warte { (a.findViewById<LinearLayout>(R.id.ausloeser_liste).getChildAt(0) as? TextView)?.text?.startsWith("Bürotür") == true }
        return a
    }

    private fun AusloeserActivity.formular() = findViewById<View>(R.id.ausloeser_formular)
    private fun AusloeserActivity.neuZeile() = findViewById<TextView>(R.id.ausloeser_neu_zeile)
    private fun AusloeserActivity.titel() = findViewById<TextView>(R.id.ausloeser_name_titel).text.toString()
    private fun AusloeserActivity.zeile() = findViewById<LinearLayout>(R.id.ausloeser_liste).getChildAt(0) as TextView

    // --- the layout ---------------------------------------------------------------------

    @Test
    fun theTagsComeFirstAndTheFormIsClosed() {
        val a = bildschirm()
        assertEquals(View.GONE, a.formular().visibility)
        assertEquals(View.VISIBLE, a.neuZeile().visibility)
        assertEquals("+ Neuen Tag beschreiben", a.neuZeile().text.toString())
        assertEquals("no empty line under the list", View.GONE, a.findViewById<View>(R.id.ausloeser_stand).visibility)
        // the tags' card is the screen's first card, before the charger's
        val karten = a.findViewById<ViewGroup>(R.id.ausloeser_root).getChildAt(0) as ViewGroup
        val liste = a.findViewById<View>(R.id.ausloeser_liste).parent as View
        val laden = generateSequence(a.findViewById<View>(R.id.ausloeser_laden)) { it.parent as? View }.first { it.parent === karten }
        assertTrue("the tags before the charger", karten.indexOfChild(liste) < karten.indexOfChild(laden))
        assertTrue("the tags are the first card", (0 until karten.indexOfChild(liste)).none { karten.getChildAt(it) is LinearLayout })
    }

    @Test
    fun theLastRowOpensTheFormInPlaceAndAbbrechenClosesIt() {
        val a = bildschirm()
        a.neuZeile().performClick()
        assertEquals(View.VISIBLE, a.formular().visibility)
        assertEquals("the row gives way to the form", View.GONE, a.neuZeile().visibility)
        assertEquals("Neuen Tag beschreiben", a.titel())
        a.findViewById<EditText>(R.id.ausloeser_name).setText("Flur")
        a.findViewById<Button>(R.id.ausloeser_zurueck).performClick()
        assertEquals(View.GONE, a.formular().visibility)
        assertEquals(View.VISIBLE, a.neuZeile().visibility)
        assertEquals("nothing kept", "", a.findViewById<EditText>(R.id.ausloeser_name).text.toString())
    }

    @Test
    fun aTagTappedOpensTheFormForIt() {
        val a = bildschirm()
        a.zeile().performClick()
        assertEquals(View.VISIBLE, a.formular().visibility)
        assertEquals("Tag umbenennen", a.titel())
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.ausloeser_entfernen).visibility)
        a.findViewById<Button>(R.id.ausloeser_zurueck).performClick()
        assertEquals(View.GONE, a.formular().visibility)
    }

    @Test
    fun aWrittenTagClosesTheFormAndSaysSo() {
        val a = bildschirm()
        a.neuZeile().performClick()
        assertEquals(View.GONE, a.findViewById<View>(R.id.ausloeser_entfernen).visibility)
        a.findViewById<EditText>(R.id.ausloeser_name).setText("Flur")
        a.findViewById<Button>(R.id.ausloeser_schreiben).performClick()
        warte { schreiber.uri != null }
        assertEquals("waiting: the form stays", View.VISIBLE, a.formular().visibility)
        schreiber.ergebnis!!(TagSchreiber.Ergebnis.Fertig)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, a.formular().visibility)
        val stand = a.findViewById<TextView>(R.id.ausloeser_stand)
        assertEquals(View.VISIBLE, stand.visibility)
        assertTrue(stand.text.toString(), stand.text.startsWith("„Flur“ ist fertig."))
    }

    @Test
    fun aFailedWriteKeepsTheFormOpen() {
        val a = bildschirm()
        a.neuZeile().performClick()
        a.findViewById<EditText>(R.id.ausloeser_name).setText("Flur")
        a.findViewById<Button>(R.id.ausloeser_schreiben).performClick()
        warte { schreiber.uri != null }
        schreiber.ergebnis!!(TagSchreiber.Ergebnis.Fehler("Der Tag war zu früh weg."))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, a.formular().visibility)
        assertEquals("Noch einmal versuchen", a.findViewById<Button>(R.id.ausloeser_schreiben).text.toString())
    }

    // --- what a tag did last (§9) ---------------------------------------------------------

    @Test
    fun homesTimeIsReadAndSaidAsAPersonSaysIt() {
        val jetzt = Ausloeser.zeitpunkt("2026-11-27T20:00:00Z")!!
        assertEquals(Ausloeser.zeitpunkt("2026-11-27T21:00:00+01:00"), jetzt)
        assertEquals(jetzt + 250, Ausloeser.zeitpunkt("2026-11-27T20:00:00.25Z"))
        assertEquals("seconds", jetzt, Ausloeser.zeitpunkt(jetzt / 1000))
        assertEquals("ms", jetzt, Ausloeser.zeitpunkt(jetzt))
        listOf(null, "", "gestern", "2026-11-27", true).forEach { assertNull("taken: $it", Ausloeser.zeitpunkt(it)) }

        assertEquals("heute 18:12", Ausloeser.wann(Ausloeser.zeitpunkt("2026-11-27T18:12:00Z")!!, jetzt, UTC))
        assertEquals("gestern 07:30", Ausloeser.wann(Ausloeser.zeitpunkt("2026-11-26T07:30:00Z")!!, jetzt, UTC))
        assertEquals("20.11., 18:12", Ausloeser.wann(Ausloeser.zeitpunkt("2026-11-20T18:12:00Z")!!, jetzt, UTC))
    }

    @Test
    fun theListSaysWhatATagDidLast() {
        val vorhin = System.currentTimeMillis() - 60_000
        homes.zuletzt = ""","zuletzt":{"wann":"${rfc(vorhin)}","person":"anna","text":"Bürolicht aus"}"""
        val a = bildschirm()
        assertEquals("Bürotür\nzuletzt ${Ausloeser.wann(vorhin)}, anna – Bürolicht aus", a.zeile().text.toString())
        assertTrue(a.zeile().text.contains("zuletzt heute") || a.zeile().text.contains("zuletzt gestern"))
    }

    @Test
    fun withoutAPersonOrATimeTheLineIsShorter() {
        homes.zuletzt = ""","zuletzt":{"wann":"kaputt","text":"Bürolicht aus"}"""
        assertEquals("Bürotür\nBürolicht aus", bildschirm().zeile().text.toString())
    }

    @Test
    fun theClientReadsZuletztOnlyWithItsText() {
        homes.zuletzt = ""","zuletzt":{"wann":"2026-11-27T18:12:00Z"}"""
        val t = (AusloeserClient(base, "homes_device_T").tags() as Ausgang.Tags).tags.single()
        assertNull("nothing to show", t.zuletzt)
        homes.zuletzt = ""","zuletzt":{"wann":"2026-11-27T18:12:00Z","text":"Bürolicht aus"}"""
        val z = (AusloeserClient(base, "homes_device_T").tags() as Ausgang.Tags).tags.single().zuletzt
        assertEquals(AusloeserClient.Zuletzt(Ausloeser.zeitpunkt("2026-11-27T18:12:00Z"), "", "Bürolicht aus"), z)
    }

    @Test
    fun aTouchedTagSaysMeldetThenWhatItsAutomationsDid() {
        // Homes waits for HA's automations (§9): a few seconds, „Meldet …“ meanwhile
        homes.melden = {
            MockResponse().setBody(
                """{"ok":true,"name":"Bürotür","ergebnisse":[{"automation":"licht","name":"Licht aus","lief":true,"grund":""}],""" +
                    """"text":"Bürotür: Licht aus ist gelaufen."}""",
            ).setBodyDelay(1, TimeUnit.SECONDS)
        }
        val t = Robolectric.buildActivity(
            TagActivity::class.java, Intent(NfcAdapter.ACTION_NDEF_DISCOVERED, Uri.parse("cloud.dopp.homes://tag/$TAG")),
        ).setup().get()
        val zeile = t.findViewById<TextView>(R.id.tag_zeile)
        assertEquals("Meldet …", zeile.text.toString())
        warte { zeile.text.toString() != "Meldet …" }
        assertEquals("Bürotür: Licht aus ist gelaufen.", zeile.text.toString())
        assertEquals("a named tag: no „Benennen“", View.GONE, t.findViewById<Button>(R.id.tag_benennen).visibility)
    }
}
