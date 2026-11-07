package cloud.dopp.homes

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import cloud.dopp.homes.data.SicherungClient
import cloud.dopp.homes.data.SicherungClient.Korrektur
import cloud.dopp.homes.sicherung.Kontaktbuch
import cloud.dopp.homes.sicherung.KorrekturenLauf
import cloud.dopp.homes.sicherung.Korrigieren
import cloud.dopp.homes.sicherung.Lauf
import cloud.dopp.homes.sicherung.Schritt
import cloud.dopp.homes.sicherung.Zeile
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Kontakt prüfen lassen (owner 2026-11-07): an accepted correction of the
 * person's own contact becomes writes into exactly the rows it names, only
 * where the phone still holds what the review showed; a field changed on
 * the phone meanwhile is left alone and not reported written.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KorrekturTest {

    private val zeilen = listOf(
        Zeile(1, 10, StructuredName.CONTENT_ITEM_TYPE, mapOf(StructuredName.GIVEN_NAME to "Hilde", StructuredName.FAMILY_NAME to "Oma", StructuredName.DISPLAY_NAME to "Hilde Oma")),
        Zeile(2, 10, Phone.CONTENT_ITEM_TYPE, mapOf(Phone.NUMBER to "0711 44 55 66", Phone.TYPE to Phone.TYPE_HOME.toString())),
        Zeile(3, 10, Email.CONTENT_ITEM_TYPE, mapOf(Email.ADDRESS to "Hilde@Example.org")),
        Zeile(4, 10, StructuredPostal.CONTENT_ITEM_TYPE, mapOf(StructuredPostal.STREET to "Weg 1", StructuredPostal.POSTCODE to "12345", StructuredPostal.CITY to "Stadt")),
        Zeile(5, 11, Phone.CONTENT_ITEM_TYPE, mapOf(Phone.NUMBER to "0171 1")),
    )

    private fun a(nr: Int, art: String, vorher: String, nachher: String, nachherArt: String = "", va: Korrektur.Anschrift? = null, na: Korrektur.Anschrift? = null) =
        Korrektur.Aenderung(nr, art, vorher, nachher, nachherArt = nachherArt, vorherAdresse = va, nachherAdresse = na)

    @Test
    fun eachTakenFieldIsOneWriteOfItsRow() {
        val k = Korrektur(
            "f1", "lookup", "Hilde Oma",
            listOf(
                a(0, "nachname", "Oma", "Beispiel"),
                a(2, "telefon", "0711 445566", "0711 445577", "geschaeftlich"), // the phone writes it with spaces
                a(3, "telefon", "", "0171 2345678", "mobil"),
                a(4, "mail", "hilde@example.org", ""),
                a(5, "adresse", "", "", va = Korrektur.Anschrift("Weg 1", "12345", "Stadt", ""), na = Korrektur.Anschrift("Lindenweg 3", "70173", "Stuttgart", "")),
                a(6, "geburtstag", "", "1941-03-08"),
                a(7, "position", "", "Rentnerin"),
                a(8, "telefon", "0000 999", "0000 998"), // not on the phone any more
            ),
        )
        val (schritte, geschrieben) = Korrigieren.plan(k, zeilen)
        assertEquals(listOf(0, 2, 3, 4, 5, 6, 7), geschrieben)
        assertEquals(Schritt.Aendern(1, mapOf(StructuredName.FAMILY_NAME to "Beispiel", StructuredName.DISPLAY_NAME to "Hilde Beispiel")), schritte[0])
        assertEquals(Schritt.Aendern(2, mapOf(Phone.NUMBER to "0711 445577", Phone.TYPE to Phone.TYPE_WORK)), schritte[1])
        assertEquals(Schritt.Neu(10, Phone.CONTENT_ITEM_TYPE, mapOf(Phone.NUMBER to "0171 2345678", Phone.TYPE to Phone.TYPE_MOBILE)), schritte[2])
        assertEquals(Schritt.Weg(3), schritte[3])
        val adr = schritte[4] as Schritt.Aendern
        assertEquals(4L, adr.zeile)
        assertEquals("Lindenweg 3", adr.werte[StructuredPostal.STREET])
        assertEquals(Schritt.Neu(10, Event.CONTENT_ITEM_TYPE, mapOf(Event.START_DATE to "1941-03-08", Event.TYPE to Event.TYPE_BIRTHDAY)), schritte[5])
        assertEquals(Schritt.Neu(10, Organization.CONTENT_ITEM_TYPE, mapOf(Organization.TITLE to "Rentnerin")), schritte[6])
        assertEquals(7, schritte.size)
    }

    @Test
    fun aFieldChangedOnThePhoneIsLeftAloneAndOneWrittenAlreadyIsSaidWritten() {
        val k = Korrektur("f", "u", "Hilde Oma", listOf(a(0, "vorname", "Hildegard", "Hilde"), a(1, "vorname", "Hilda", "Hildchen"), a(2, "telefon", "0711 999", "0711 445566")))
        val (schritte, geschrieben) = Korrigieren.plan(k, zeilen)
        assertTrue("writes $schritte", schritte.isEmpty())
        assertEquals(listOf(0, 2), geschrieben) // both hold what they should; „Hilda“ was never there
        assertEquals(emptyList<Int>(), Korrigieren.plan(k, emptyList()).second)
    }

    // --- the run, against a Homes that serves one correction ----------------------

    private val server = MockWebServer()
    private val erledigt = ArrayList<Pair<String, String>>()

    @After
    fun zu() = server.shutdown()

    private fun homes(antwort: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path == "/napi/kontakte/korrekturen" -> MockResponse().setBody(antwort)
                    request.method == "POST" && path.startsWith("/napi/kontakte/korrekturen/") -> {
                        synchronized(erledigt) { erledigt += path.substringAfterLast('/') to request.body.readUtf8() }
                        MockResponse().setBody("""{"ok":true}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    private class Buch(val zeilen: Map<String, List<Zeile>>) : Kontaktbuch {
        val geschrieben = ArrayList<List<Schritt>>()
        override fun zeilen(uid: String, name: String) = zeilen[uid]
        override fun schreiben(schritte: List<Schritt>) { geschrieben += schritte }
    }

    @Test
    fun theRunWritesWhatWasTakenAndSaysSo() {
        homes(
            """{"korrekturen":[{"id":"f1","uid":"lookup.1","name":"Hilde Oma","aenderungen":[{"nr":1,"art":"telefon","vorher":"0711 445566","nachher":"0711 445577","vorher_art":"privat","nachher_art":"privat"}]},""" +
                """{"id":"f2","uid":"nicht-da","name":"Karl","aenderungen":[{"nr":0,"art":"vorname","vorher":"Karl","nachher":"Carl"}]}]}""",
        )
        val c = SicherungClient(server.url("/").toString().trimEnd('/'), "homes_device_t", OkHttpClient())
        assertEquals(2, c.korrekturen().size)
        val buch = Buch(mapOf("lookup.1" to zeilen))
        assertEquals(Lauf.Ergebnis.FERTIG, KorrekturenLauf(c, buch).laufen())
        assertEquals(listOf(listOf(Schritt.Aendern(2, mapOf(Phone.NUMBER to "0711 445577", Phone.TYPE to Phone.TYPE_HOME)))), buch.geschrieben)
        assertEquals("f1", erledigt[0].first)
        assertEquals("[1]", JSONObject(erledigt[0].second).getJSONArray("geschrieben").toString())
        assertEquals("f2", erledigt[1].first) // not on the phone: nothing written, said so
        assertEquals("[]", JSONObject(erledigt[1].second).getJSONArray("geschrieben").toString())
    }

    @Test
    fun aMalformedReplyIsNoCorrection() {
        val out = SicherungClient.korrekturenAus(200, """{"korrekturen":[{"id":"","uid":"x","aenderungen":[]},{"id":"a","uid":"u","name":"N","aenderungen":[{"art":"mail"},{"nr":2,"art":"adresse","vorher_adresse":{"strasse":"S","plz":"1","ort":"O","land":"L"}}]}]}""")
        assertEquals(1, out.size)
        assertEquals(listOf(2), out[0].aenderungen.map { it.nr })
        assertEquals(Korrektur.Anschrift("S", "1", "O", "L"), out[0].aenderungen[0].vorherAdresse)
    }
}
