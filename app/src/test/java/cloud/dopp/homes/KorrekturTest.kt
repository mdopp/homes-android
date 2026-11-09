package cloud.dopp.homes

import android.database.MatrixCursor
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import cloud.dopp.homes.data.SicherungClient
import cloud.dopp.homes.data.SicherungClient.Korrektur
import cloud.dopp.homes.sicherung.HandyKontakte
import cloud.dopp.homes.sicherung.Kontakt
import cloud.dopp.homes.sicherung.Kontaktbuch
import cloud.dopp.homes.sicherung.KorrekturenLauf
import cloud.dopp.homes.sicherung.Korrigieren
import cloud.dopp.homes.sicherung.Lauf
import cloud.dopp.homes.sicherung.Schritt
import cloud.dopp.homes.sicherung.Vcard
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

    /**
     * owner 2026-11-08: a kind is written only when it changed, the main number
     * and e-mail address as Android's primary flags, the contact's own photo
     * into the raw contact's picture, the message below the note (once).
     */
    @Test
    fun kindsTheMainOneThePhotoAndTheNote() {
        val bild = byteArrayOf(-1, -40, -1)
        val mit = zeilen + Zeile(6, 10, Photo.CONTENT_ITEM_TYPE, emptyMap()) + Zeile(7, 10, Note.CONTENT_ITEM_TYPE, mapOf(Note.NOTE to "Kennt Anna"))
        val k = Korrektur(
            "f", "u", "Hilde Oma",
            listOf(
                Korrektur.Aenderung(0, "telefon", "0711 445566", "0711 445566", vorherArt = "privat", nachherArt = "privat", nachherHaupt = true),
                Korrektur.Aenderung(1, "mail", "hilde@example.org", "hilde@example.org", vorherArt = "andere", nachherArt = "geschaeftlich"),
                Korrektur.Aenderung(2, "mail", "", "hilde@neu.example", nachherArt = "privat", nachherHaupt = true),
                Korrektur.Aenderung(3, "foto", "", "", bild = bild),
                Korrektur.Aenderung(4, "notiz", "", "Bin umgezogen."),
            ),
        )
        val (schritte, geschrieben) = Korrigieren.plan(k, mit)
        assertEquals(listOf(0, 1, 2, 3, 4), geschrieben)
        assertEquals(Schritt.Aendern(2, mapOf(Phone.NUMBER to "0711 445566", ContactsContract.Data.IS_PRIMARY to 1, ContactsContract.Data.IS_SUPER_PRIMARY to 1)), schritte[0])
        assertEquals(Schritt.Aendern(3, mapOf(Email.ADDRESS to "hilde@example.org", Email.TYPE to Email.TYPE_WORK)), schritte[1])
        assertEquals(
            Schritt.Neu(10, Email.CONTENT_ITEM_TYPE, mapOf(Email.ADDRESS to "hilde@neu.example", Email.TYPE to Email.TYPE_HOME, ContactsContract.Data.IS_PRIMARY to 1, ContactsContract.Data.IS_SUPER_PRIMARY to 1)),
            schritte[2],
        )
        val foto = schritte[3] as Schritt.Aendern
        assertEquals(6L, foto.zeile)
        assertTrue(foto.werte[Photo.PHOTO] === bild)
        assertEquals(Schritt.Aendern(7, mapOf(Note.NOTE to "Kennt Anna\n\nBin umgezogen.")), schritte[4])
        // a contact without a picture or a note gets them as new rows; a note that holds it already, nothing
        val (neu, _) = Korrigieren.plan(k.copy(aenderungen = k.aenderungen.drop(3)), zeilen)
        assertEquals(Photo.CONTENT_ITEM_TYPE, (neu[0] as Schritt.Neu).mime)
        assertEquals(Schritt.Neu(10, Note.CONTENT_ITEM_TYPE, mapOf(Note.NOTE to "Bin umgezogen.")), neu[1])
        val schon = zeilen + Zeile(7, 10, Note.CONTENT_ITEM_TYPE, mapOf(Note.NOTE to "Kennt Anna\n\nBin umgezogen."))
        assertEquals(Pair(emptyList<Schritt>(), listOf(4)), Korrigieren.plan(k.copy(aenderungen = k.aenderungen.drop(4)), schon))
    }

    /** The other way: the phone's main number and e-mail go to Homes' mirror as TYPE=PREF. */
    @Test
    fun theMainOnesGoToHomesAsPref() {
        val c = MatrixCursor(arrayOf(ContactsContract.Data.MIMETYPE, "data1", "data2", ContactsContract.Data.IS_SUPER_PRIMARY))
        c.addRow(arrayOf<Any?>(Phone.CONTENT_ITEM_TYPE, "0171 1", Phone.TYPE_MOBILE, 1))
        c.addRow(arrayOf<Any?>(Phone.CONTENT_ITEM_TYPE, "0711 2", Phone.TYPE_HOME, 0))
        c.addRow(arrayOf<Any?>(Email.CONTENT_ITEM_TYPE, "a@b.de", Email.TYPE_WORK, 1))
        var k = Kontakt(uid = "u")
        while (c.moveToNext()) k = HandyKontakte.dazu(k, c)
        val text = Vcard.karte(k)
        assertTrue(text, text.contains("TEL;TYPE=CELL,VOICE,PREF:0171 1\r\n"))
        assertTrue(text, text.contains("TEL;TYPE=HOME,VOICE:0711 2\r\n"))
        assertTrue(text, text.contains("EMAIL;TYPE=INTERNET,WORK,PREF:a@b.de\r\n"))
    }

    @Test
    fun theReplyCarriesTheMainOneAndThePhoto() {
        val out = SicherungClient.korrekturenAus(
            200,
            """{"korrekturen":[{"id":"a","uid":"u","name":"N","aenderungen":[{"nr":0,"art":"foto","bild":"/9j/"},{"nr":1,"art":"telefon","vorher":"1","nachher":"1","vorher_haupt":true}]}]}""",
        )
        assertEquals(listOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()), out[0].aenderungen[0].bild?.toList())
        assertTrue(out[0].aenderungen[1].vorherHaupt && !out[0].aenderungen[1].nachherHaupt)
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

    private class Buch(val zeilen: Map<String, List<Zeile>>, val namen: Set<String> = emptySet()) : Kontaktbuch {
        val geschrieben = ArrayList<List<Schritt>>()
        override fun zeilen(uid: String, name: String) = zeilen[uid]
        override fun neu(name: String) = if (name in namen) null else listOf(Zeile(-1, Korrigieren.NEU, "", emptyMap()))
        override fun schreiben(schritte: List<Schritt>) { geschrieben += schritte }
    }

    /**
     * A new contact Homes asks for (owner 2026-11-09, a person newly named in Immich): one
     * raw contact made with its name row (first, last and display name in one) and its
     * photo, every change said written; one the phone has by that name already is said
     * written with nothing written, so a report that did not arrive makes no second.
     */
    @Test
    fun aNewContactIsMadeOnceWithItsNameAndFace() {
        val bild = byteArrayOf(-1, -40)
        homes(
            """{"korrekturen":[{"id":"n1","uid":"","name":"Yvonne Huber","aenderungen":[{"nr":0,"art":"vorname","nachher":"Yvonne"},""" +
                """{"nr":1,"art":"nachname","nachher":"Huber"},{"nr":2,"art":"foto","bild":"/9g="}]},""" +
                """{"id":"n2","uid":"","name":"Lia","aenderungen":[{"nr":0,"art":"vorname","nachher":"Lia"},{"nr":1,"art":"nachname"}]}]}""",
        )
        val c = SicherungClient(server.url("/").toString().trimEnd('/'), "homes_device_t", OkHttpClient())
        val buch = Buch(emptyMap(), namen = setOf("Lia"))
        assertEquals(Lauf.Ergebnis.FERTIG, KorrekturenLauf(c, buch).laufen())
        assertEquals(1, buch.geschrieben.size)
        val (name, foto) = buch.geschrieben[0]
        assertEquals(
            Schritt.Neu(Korrigieren.NEU, StructuredName.CONTENT_ITEM_TYPE, mapOf(StructuredName.GIVEN_NAME to "Yvonne", StructuredName.DISPLAY_NAME to "Yvonne Huber", StructuredName.FAMILY_NAME to "Huber")),
            name,
        )
        assertTrue(foto is Schritt.Neu && foto.roh == Korrigieren.NEU && foto.mime == Photo.CONTENT_ITEM_TYPE && (foto.werte[Photo.PHOTO] as ByteArray).contentEquals(bild))
        assertEquals("n1" to "[0,1,2]", erledigt[0].first to JSONObject(erledigt[0].second).getJSONArray("geschrieben").toString())
        assertEquals("n2" to "[0,1]", erledigt[1].first to JSONObject(erledigt[1].second).getJSONArray("geschrieben").toString())
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
        assertEquals(listOf(listOf(Schritt.Aendern(2, mapOf(Phone.NUMBER to "0711 445577")))), buch.geschrieben) // the kind unchanged: the phone's own TYPE stays
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
