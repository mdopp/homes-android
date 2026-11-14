package cloud.dopp.homes

import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.ui.StartDevices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The start screen's favorites (owner, 2026-11-02): Homes answers
 * `{ok, favorites: [card]}` (internal/napi/home.go:198-242), the person's ☆
 * things in their order. The order is the person's and is kept; anything that
 * is not Homes' shape is no answer at all (null), not "no favorites".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiClientFavoritesTest {

    @Test
    fun theFavoritesInThePersonsOrder() {
        val cards = ApiClient.parseFavorites(
            """{"ok": true, "favorites": [
              {"entity_id": "switch.kaffee", "name": "Kaffee", "domain": "switch", "state": "off"},
              {"entity_id": "cover.garage", "name": "Garage", "domain": "cover", "device_class": "garage", "state": "open"},
              {"entity_id": "lock.haustuer", "name": "Haustür", "domain": "lock", "state": "locked", "supported_features": 1, "page": "/raeume/lock.haustuer"}
            ]}""",
        )!!
        assertEquals(listOf("switch.kaffee", "cover.garage", "lock.haustuer"), cards.map { it.entityId })
        assertEquals("garage", cards[1].deviceClass)
        assertEquals(1, cards[2].supportedFeatures)
        assertEquals("/raeume/lock.haustuer", cards[2].page)
    }

    @Test
    fun noneIsAnEmptyListAndNotHomesShapeIsNoAnswer() {
        assertTrue(ApiClient.parseFavorites("""{"ok": true, "favorites": []}""")!!.isEmpty())
        assertNull(ApiClient.parseFavorites("""[ {"entity_id": "switch.lamp"} ]"""))
        assertNull(ApiClient.parseFavorites("""{"ok": false, "error": "favorites_unreadable"}"""))
        assertNull(ApiClient.parseFavorites("kaputt"))
        val one = ApiClient.parseFavorites("""{"favorites": [ {"state": "on"}, {"entity_id": "light.flur", "state": "on"} ]}""")!!
        assertEquals("a row without an entity id is skipped", listOf("light.flur"), one.map { it.entityId })
        assertEquals("the domain falls back to the id's prefix", "light", one[0].domain)
    }

    /**
     * A printer (owner, 2026-11-14: „Bitte in der App und auf der Webseite gleich“): Homes sends its
     * words in `text` (mdopp/homes#370), and the tile says them, not HA's raw `idle`; without `text`
     * the app's own words stay, so a sensor still says its value.
     */
    @Test
    fun aPrinterSaysHomesWords() {
        val cards = ApiClient.parseFavorites(
            """{"ok": true, "favorites": [
              {"entity_id": "sensor.epson", "name": "EPSON WF-3540 Series", "domain": "sensor", "device_class": "enum", "state": "printing",
               "text": "druckt · Schwarz 42\u00a0%"},
              {"entity_id": "sensor.epson2", "name": "Drucker", "domain": "sensor", "state": "unavailable", "text": "nicht erreichbar"},
              {"entity_id": "sensor.temp", "name": "Temperatur", "domain": "sensor", "state": "21.5", "unit": "°C", "text": "  "}
            ]}""",
        )!!
        assertEquals("druckt · Schwarz 42\u00a0%", cards[0].text)
        assertEquals("druckt · Schwarz 42\u00a0%", StartDevices.stateText(cards[0]))
        assertEquals("nicht erreichbar", StartDevices.stateText(cards[1]))
        assertNull("a blank text is none", cards[2].text)
        assertEquals("21,5 °C", StartDevices.stateText(cards[2]))
    }
}
