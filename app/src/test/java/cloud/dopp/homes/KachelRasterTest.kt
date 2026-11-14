package cloud.dopp.homes

import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.ui.FavoritenAnsicht
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tiles by the room there is (owner, 2026-11-04: a foldable and a
 * tablet too, and „zu unruhig“ mixed): below two cells a phone's rows, as
 * approved; wider a grid of equal cells, each at least a cover's one line,
 * the favourites in their order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KachelRasterTest {

    @Test
    fun theCellsFollowTheRoom() {
        // the section's width on a 360, 411 and 600 dp screen: a phone's rows
        assertEquals(1, FavoritenAnsicht.spalten(312))
        assertEquals(1, FavoritenAnsicht.spalten(363))
        assertEquals(1, FavoritenAnsicht.spalten(552))
        // two cells from 632 dp of room on; a foldable's 900 dp, a tablet's 1280 dp
        assertEquals(1, FavoritenAnsicht.spalten(631))
        assertEquals(2, FavoritenAnsicht.spalten(632))
        assertEquals(2, FavoritenAnsicht.spalten(852))
        assertEquals(3, FavoritenAnsicht.spalten(1232))
    }

    private companion object {
        const val FAVORITES = """{"ok":true,"favorites":[
            {"entity_id":"light.buero","name":"Bürolicht","domain":"light","state":"off"},
            {"entity_id":"cover.esszimmer","name":"Esszimmerjalousien","domain":"cover","state":"open"},
            {"entity_id":"cover.wohnzimmer","name":"Wohnzimmerjalousien","domain":"cover","state":"open"},
            {"entity_id":"cover.garage","name":"Garagentor Garage","domain":"cover","state":"closed"},
            {"entity_id":"light.kueche","name":"Küchenlicht","domain":"light","state":"on"},
            {"entity_id":"lock.haustuer","name":"Haustür","domain":"lock","state":"locked"}]}"""
    }
}
