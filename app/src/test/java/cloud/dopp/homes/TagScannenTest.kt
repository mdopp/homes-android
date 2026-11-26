package cloud.dopp.homes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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
import org.json.JSONObject
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
import java.util.concurrent.TimeUnit

/**
 * A tag's scan action (homes ADR 0004 §4, owner 2026-11-26 „Go. Passt alles“):
 * „Was soll der Tag tun?“ offers exactly the actions the box lists, the tag's
 * own preselected, and sends the choice with „Sichern“ and „Tag beschreiben“;
 * the list says a tag's action under its name; a touched scan tag shows Homes'
 * sentence, and each refusal its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TagScannenTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private companion object {
        const val AKTIONEN = """"aktionen":[{"id":"","titel":"Home Assistant melden"},""" +
            """{"id":"scannen-ich","titel":"Scannen – für mich"},{"id":"scannen-haushalt","titel":"Scannen – für den Haushalt"}]"""
        const val TAGS = """{"ok":true,"tags":[{"tag":"homes-aaaaaaaaaaaa","name":"Scanner","aktion":"scannen-ich"},""" +
            """{"tag":"homes-bbbbbbbbbbbb","name":"Bürotür","aktion":""}],"vorschlaege":[],$AKTIONEN}"""
        const val NUR_HA = """{"ok":true,"tags":[{"tag":"homes-bbbbbbbbbbbb","name":"Bürotür"}],"vorschlaege":[]}"""
        const val NEU = """{"ok":true,"tag":"homes-cccccccccccc","uri":"cloud.dopp.homes://tag/homes-cccccccccccc","aktion":"scannen-haushalt"}"""
    }

    private inner class Homes : Dispatcher() {
        var melden: () -> MockResponse = { MockResponse().setBody("""{"ok":true,"name":"Scanner","aktion":"scannen-ich","text":"Der Scanner startet – nur für dich."}""") }
        var neu: () -> MockResponse = { MockResponse().setBody(NEU) }
        var tags: () -> MockResponse = { MockResponse().setBody(TAGS) }
        val anfragen = CopyOnWriteArrayList<Pair<String, String>>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            anfragen += "${request.method} ${request.path}" to request.body.readUtf8()
            return when (request.path) {
                "/napi/ausloeser" -> melden()
                "/napi/ausloeser/tag" -> neu()
                "/napi/ausloeser/tags" -> tags()
                else -> MockResponse().setResponseCode(404)
            }
        }

        fun gesendet(): JSONObject = JSONObject(anfragen.last { it.first == "POST /napi/ausloeser/tag" }.second)
    }

    private val homes = Homes()
    private val server = MockWebServer().also { it.dispatcher = homes }
    private val base get() = server.url("/").toString().trimEnd('/')

    /** NFC that never brings a tag: these tests name tags, the writing is AusloeserTest's. */
    private class Schreiber : TagSchreiber {
        var uriVon: (() -> String?)? = null
        val uri: String? get() = uriVon?.invoke()
        override fun bereit(ctx: Context) = TagSchreiber.Bereit.JA
        override fun an(activity: Activity, uri: () -> String?, ergebnis: (TagSchreiber.Ergebnis) -> Unit) {
            uriVon = uri
        }
        override fun aus(activity: Activity) {
            uriVon = null
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

    private fun client() = AusloeserClient(base, "homes_device_T")

    private fun warte(bis: () -> Boolean) {
        val ende = System.currentTimeMillis() + 10_000
        while (!bis() && System.currentTimeMillis() < ende) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    // --- the client -------------------------------------------------------------------

    @Test
    fun theListBringsEachTagsActionAndTheBoxsActionsInOrder() {
        val a = client().tags() as Ausgang.Tags
        assertEquals(listOf("scannen-ich", ""), a.tags.map { it.aktion })
        assertEquals(
            listOf(
                AusloeserClient.Aktion("", "Home Assistant melden"),
                AusloeserClient.Aktion("scannen-ich", "Scannen – für mich"),
                AusloeserClient.Aktion("scannen-haushalt", "Scannen – für den Haushalt"),
            ),
            a.aktionen,
        )
        homes.tags = { MockResponse().setBody(NUR_HA) }
        val alt = client().tags() as Ausgang.Tags
        assertTrue("an older Homes lists none", alt.aktionen.isEmpty())
        assertEquals("", alt.tags.single().aktion)
    }

    @Test
    fun theActionGoesOnlyWhenChosen() {
        assertEquals(Ausgang.Angelegt("homes-cccccccccccc", "cloud.dopp.homes://tag/homes-cccccccccccc", "scannen-haushalt"), client().benennen("Flur", aktion = "scannen-haushalt"))
        assertEquals("scannen-haushalt", homes.gesendet().getString("aktion"))
        client().benennen("Flur", "homes-cccccccccccc", "")
        assertEquals("back to HA is said as \"\"", "", homes.gesendet().getString("aktion"))
        client().benennen("Flur", "homes-cccccccccccc")
        assertFalse("none: the tag keeps its action", homes.gesendet().has("aktion"))

        homes.neu = { MockResponse().setResponseCode(400).setBody("""{"ok":false,"error":"Das kann ein Tag auf dieser Box nicht tun."}""") }
        assertEquals("Das kann ein Tag auf dieser Box nicht tun.", Ausloeser.fehler(client().benennen("Flur", aktion = "scannen-ich")))
    }

    @Test
    fun aScanTagSaysWhatStartedAndEachRefusalItsSentence() {
        val a = client().nfc("homes-aaaaaaaaaaaa")
        assertEquals(Ausgang.Gemeldet("Scanner", "scannen-ich", "Der Scanner startet – nur für dich."), a)
        assertEquals("Der Scanner startet – nur für dich.", Ausloeser.tagZeile(a))
        listOf(
            403 to "Für dich allein scannt nur ein Telefon, das mit deinem Login gekoppelt ist.",
            503 to "Auf dieser Box ist kein Scanner eingerichtet.",
            502 to "Der Scanner antwortet gerade nicht. Versuch es gleich noch einmal.",
        ).forEach { (code, satz) ->
            homes.melden = { MockResponse().setResponseCode(code).setBody("""{"ok":false,"error":"$satz"}""") }
            assertEquals("$code", satz, Ausloeser.tagZeile(client().nfc("homes-aaaaaaaaaaaa")))
        }
    }

    // --- a touched scan tag -----------------------------------------------------------

    private fun beruehrt(uri: String): TagActivity =
        Robolectric.buildActivity(TagActivity::class.java, Intent(NfcAdapter.ACTION_NDEF_DISCOVERED, Uri.parse(uri))).setup().get()

    private fun TagActivity.zeile() = findViewById<TextView>(R.id.tag_zeile).text.toString()

    @Test
    fun aTouchedScanTagShowsHomesSentenceNotGemeldet() {
        val t = beruehrt("cloud.dopp.homes://tag/homes-aaaaaaaaaaaa")
        assertEquals("running at once", "Meldet …", t.zeile())
        warte { t.zeile() != "Meldet …" }
        assertEquals("Der Scanner startet – nur für dich.", t.zeile())
        assertEquals("a scan tag is no unknown tag", View.GONE, t.findViewById<Button>(R.id.tag_benennen).visibility)
        shadowOf(Looper.getMainLooper()).idleFor(TagActivity.ZEIGEN_MS, TimeUnit.MILLISECONDS)
        assertTrue(t.isFinishing)
    }

    @Test
    fun aTouchedScanTagThatFailsSaysWhy() {
        homes.melden = { MockResponse().setResponseCode(503).setBody("""{"ok":false,"error":"Auf dieser Box ist kein Scanner eingerichtet."}""") }
        val t = beruehrt("cloud.dopp.homes://tag/homes-aaaaaaaaaaaa")
        warte { t.zeile() != "Meldet …" }
        assertEquals("Auf dieser Box ist kein Scanner eingerichtet.", t.zeile())
    }

    // --- the Auslöser screen ------------------------------------------------------------

    private fun bildschirm(): AusloeserActivity = Robolectric.buildActivity(AusloeserActivity::class.java).setup().get()

    private fun AusloeserActivity.gruppe() = findViewById<RadioGroup>(R.id.ausloeser_aktion)
    private fun AusloeserActivity.wahl(): List<String> = (0 until gruppe().childCount).map { (gruppe().getChildAt(it) as RadioButton).text.toString() }
    private fun AusloeserActivity.gewaehlt(): String? = findViewById<RadioButton>(gruppe().checkedRadioButtonId)?.text?.toString()
    private fun AusloeserActivity.waehle(titel: String) {
        (0 until gruppe().childCount).map { gruppe().getChildAt(it) as RadioButton }.first { it.text.toString() == titel }.performClick()
    }
    private fun AusloeserActivity.zeile(i: Int): TextView = findViewById<LinearLayout>(R.id.ausloeser_liste).getChildAt(i) as TextView
    private fun AusloeserActivity.knopf() = findViewById<Button>(R.id.ausloeser_schreiben)
    private fun AusloeserActivity.stand() = findViewById<TextView>(R.id.ausloeser_stand).text.toString()

    private fun geladen(): AusloeserActivity {
        val a = bildschirm()
        warte { a.gruppe().childCount == 3 }
        return a
    }

    @Test
    fun theChoiceIsExactlyWhatTheBoxListsANewTagStartsAsHas() {
        val a = geladen()
        assertEquals(View.VISIBLE, a.gruppe().visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.ausloeser_aktion_frage).visibility)
        assertEquals("Was soll der Tag tun?", a.findViewById<TextView>(R.id.ausloeser_aktion_frage).text.toString())
        assertEquals(listOf("Home Assistant melden", "Scannen – für mich", "Scannen – für den Haushalt"), a.wahl())
        assertEquals("a new tag: HA's", "Home Assistant melden", a.gewaehlt())
    }

    @Test
    fun theListSaysATagsActionUnderItsName() {
        val a = geladen()
        assertEquals("Scanner\nScannen – für mich", a.zeile(0).text.toString())
        assertEquals("HA's report says nothing extra", "Bürotür", a.zeile(1).text.toString())
    }

    @Test
    fun aRenamedTagKeepsOrChangesItsAction() {
        val a = geladen()
        a.zeile(0).performClick()
        assertEquals("its own preselected", "Scannen – für mich", a.gewaehlt())
        a.waehle("Home Assistant melden")
        a.knopf().performClick()
        assertEquals("running on the button at once", "Sichert …", a.knopf().text.toString())
        warte { a.knopf().isEnabled }
        val o = homes.gesendet()
        assertEquals("homes-aaaaaaaaaaaa", o.getString("tag"))
        assertEquals("back to HA", "", o.getString("aktion"))
        assertTrue(a.stand(), a.stand().startsWith("Der Tag heißt jetzt „Scanner“."))
        assertEquals("the form is a new tag's again", "Home Assistant melden", a.gewaehlt())
    }

    @Test
    fun aNewTagIsWrittenWithItsAction() {
        val a = geladen()
        a.findViewById<EditText>(R.id.ausloeser_name).setText("Flur")
        a.waehle("Scannen – für den Haushalt")
        a.knopf().performClick()
        warte { schreiber.uri != null }
        assertEquals("scannen-haushalt", homes.gesendet().getString("aktion"))
        assertEquals("cloud.dopp.homes://tag/homes-cccccccccccc", schreiber.uri)
    }

    @Test
    fun anActionTheBoxLacksIsSaidAndTheFormStays() {
        homes.neu = { MockResponse().setResponseCode(400).setBody("""{"ok":false,"error":"Das kann ein Tag auf dieser Box nicht tun."}""") }
        val a = geladen()
        a.zeile(1).performClick()
        a.waehle("Scannen – für mich")
        a.knopf().performClick()
        warte { a.knopf().isEnabled }
        assertEquals("Das kann ein Tag auf dieser Box nicht tun.", a.stand())
        assertEquals("Sichern", a.knopf().text.toString())
        assertEquals("Scannen – für mich", a.gewaehlt())
    }

    @Test
    fun anOlderHomesOffersNoChoiceAndSendsNone() {
        homes.tags = { MockResponse().setBody(NUR_HA) }
        val a = bildschirm()
        warte { a.zeile(0).text.toString() == "Bürotür" }
        assertEquals(View.GONE, a.gruppe().visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.ausloeser_aktion_frage).visibility)
        a.zeile(0).performClick()
        a.findViewById<EditText>(R.id.ausloeser_name).setText("Bürotür oben")
        a.knopf().performClick()
        warte { a.knopf().isEnabled }
        assertFalse("its action is left as it is", homes.gesendet().has("aktion"))
    }
}
