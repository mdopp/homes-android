package cloud.dopp.homes

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.Card
import cloud.dopp.homes.data.Napi.Quelle
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.einrichtung.EinrichtungStore
import cloud.dopp.homes.kann.KannStore
import cloud.dopp.homes.sicherung.SicherungStore
import cloud.dopp.homes.ui.HomeActivity
import cloud.dopp.homes.ui.Jetzt
import cloud.dopp.homes.ui.SicherungActivity
import cloud.dopp.homes.ui.StartDevices
import cloud.dopp.homes.ui.Telefon
import cloud.dopp.homes.ui.TelefonActivity
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

/**
 * The start screen as the owner approved it (2026-11-27: „Sonst ein gutes
 * Konzept. Bitte umsetzen“): „Jetzt“ only while something wants attention,
 * the favourites ordered by it, every area a tile with „neu“ for one never
 * opened, „Dieses Telefon“ in one line and on its page; the device widget
 * atop „Deine Geräte“ (owner 2026-11-28, HomeScreenTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartNeuTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private val tag = 24L * 60 * 60 * 1000

    @Before
    fun gekoppelt() {
        ServerStore.setBaseUrl(ctx(), "https://homes.example")
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_TESTTOKEN123")
    }

    @After
    fun zu() {
        ServerStore.clear(ctx())
        TokenStore.clear(ctx())
        StartDevices.clear(ctx())
        EinrichtungStore.clear(ctx())
        KannStore.clear(ctx())
        listOf("start_jetzt", "start_bereiche").forEach { ctx().getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    private fun karte(id: String, name: String, state: String?, text: String? = null, achtung: String? = null) =
        Card(id, name, "sensor", null, state, null, page = "/raeume/$id", text = text, achtung = achtung)

    // --- „Jetzt“ and the order ---------------------------------------------------------------

    @Test
    fun whatWantsAttentionComesFirstThenWhatIsOn() {
        val aus = karte("light.flur", "Flur", "off")
        val an = karte("light.kueche", "Küche", "on")
        val drucker = karte("sensor.drucker", "Drucker", "idle", "bereit · Papier leer · Schwarz 40 %", achtung = "Papier leer")
        val weg = karte("light.keller", "Keller", "unavailable", achtung = "nicht erreichbar")
        assertEquals(listOf(drucker, weg, an, aus), Jetzt.ordnen(listOf(aus, an, drucker, weg)))
        assertEquals("Drucker: Papier leer", Jetzt.geraet(drucker)?.text)
        assertEquals("Keller: nicht erreichbar", Jetzt.geraet(weg)?.text)
        assertNull("a printer printing wants nothing", Jetzt.geraet(karte("sensor.drucker", "Drucker", "printing", "druckt · Schwarz 42 %")))
        assertNull(
            "Homes decides: words that sound bad are no hint without its achtung",
            Jetzt.geraet(karte("sensor.drucker", "Drucker", "idle", "bereit · Papier leer · Schwarz 40 %")),
        )
        assertNull(Jetzt.geraet(an))
    }

    @Test
    fun aBackupQuietForMoreThanTwoDaysIsAHint() {
        val jetzt = 100 * tag
        assertNull(Jetzt.sicherung(Quelle.WHATSAPP, "WhatsApp", jetzt - tag, jetzt))
        assertNull("never sent: no hint", Jetzt.sicherung(Quelle.WHATSAPP, "WhatsApp", null, jetzt))
        assertEquals("WhatsApp: zuletzt vor 3 Tagen gesichert", Jetzt.sicherung(Quelle.WHATSAPP, "WhatsApp", jetzt - 3 * tag, jetzt)?.text)
    }

    @Test
    fun atMostTwoAndADismissedOneComesBackOnlyWhenItChanges() {
        val a = Jetzt.Hinweis("a", "1", "A", Jetzt.Ziel.Sicherung)
        val b = Jetzt.Hinweis("b", "1", "B", Jetzt.Ziel.Sicherung)
        val c = Jetzt.Hinweis("c", "1", "C", Jetzt.Ziel.Sicherung)
        assertEquals(listOf(a, b), Jetzt.zeigen(listOf(a, b, c), emptyMap()))
        Jetzt.wegtun(ctx(), a)
        assertEquals(listOf(b, c), Jetzt.zeigen(listOf(a, b, c), Jetzt.weg(ctx())))
        assertEquals("it says something else: back", "a", Jetzt.zeigen(listOf(a.copy(stand = "2")), Jetzt.weg(ctx())).single().id)
    }

    private fun start(): HomeActivity {
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        return a
    }

    private val drucker = """{"ok":true,"favorites":[
        {"entity_id":"light.kueche","name":"Küche","domain":"light","state":"on","brightness":153,"page":"/raeume/light.kueche"},
        {"entity_id":"sensor.drucker","name":"Drucker","domain":"sensor","state":"idle","text":"bereit · Papier leer · Schwarz 40 %","achtung":"Papier leer","page":"/raeume/sensor.drucker"}]}"""

    @Test
    fun noHintNoSection() {
        assertEquals(View.GONE, start().findViewById<View>(R.id.jetzt_section).visibility)
    }

    @Test
    fun aPrinterWithoutPaperIsAHintAndFirstAndXPutsItAway() {
        StartDevices.save(ctx(), drucker)
        val a = start()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.jetzt_section).visibility)
        val group = a.findViewById<ViewGroup>(R.id.jetzt_group)
        assertEquals(1, group.childCount)
        val h = group.getChildAt(0)
        assertEquals("Drucker: Papier leer", h.findViewById<TextView>(R.id.hinweis_text).text.toString())
        assertTrue("the printer is the first tile", Kacheln.of(a).first().contentDescription.startsWith("Drucker"))
        h.performClick()
        assertEquals("its page", Uri.parse("https://homes.example/raeume/sensor.drucker").path, shadowOf(a).nextStartedActivity?.data?.path)
        h.findViewById<View>(R.id.hinweis_weg).performClick()
        assertEquals(View.GONE, a.findViewById<View>(R.id.jetzt_section).visibility)
        assertEquals("still away on the next open", View.GONE, start().findViewById<View>(R.id.jetzt_section).visibility)
    }

    @Test
    fun anOverdueBackupIsAHintAndTheTelefonRowTurnsOrange() {
        val s = SicherungStore(ctx())
        s.setzeOrdner(Quelle.WHATSAPP, Uri.parse("content://x/tree/primary%3AWhatsApp"))
        s.merkeUebertragen(Quelle.WHATSAPP, System.currentTimeMillis() - 3 * tag, 2)
        val a = start()
        val h = a.findViewById<ViewGroup>(R.id.jetzt_group).getChildAt(0)
        assertEquals("WhatsApp: zuletzt vor 3 Tagen gesichert", h.findViewById<TextView>(R.id.hinweis_text).text.toString())
        h.performClick()
        assertEquals(SicherungActivity::class.java.name, shadowOf(a).nextStartedActivity?.component?.className)
        val stand = a.findViewById<TextView>(R.id.telefon_zeile_stand)
        assertTrue(stand.text.toString(), stand.text.startsWith("gesichert "))
        assertEquals(a.getColor(R.color.homes_accent), stand.currentTextColor)
    }

    // --- „Alles auf einen Blick“ -----------------------------------------------------------------

    private fun antwort(vararg module: String, bereiche: String = "{}") = """{"version":1,"offen":0,"module":[${module.joinToString(",") {
        """{"name":"$it","titel":"${it.replaceFirstChar(Char::uppercase)}","icon":"$it","oeffnen":true,"web":"https://homes.example/$it","nur_web":true}"""
    }}],"bereiche":$bereiche}"""

    private fun kacheln(a: HomeActivity): List<View> {
        val g = a.findViewById<ViewGroup>(R.id.ueberblick_group)
        return (0 until g.childCount).flatMap { r -> (g.getChildAt(r) as ViewGroup).let { z -> (0 until z.childCount).map { z.getChildAt(it) } } }
            .filter { it.findViewById<TextView>(R.id.bereich_name) != null }
    }

    private fun View.name() = findViewById<TextView>(R.id.bereich_name).text.toString()
    private fun View.neu() = findViewById<View>(R.id.bereich_neu).visibility == View.VISIBLE

    /** No „Dokumente-Adresse“ tile (owner 2026-11-28): Homes' Dokumente page copies it; the Dokumente tile stays. */
    @Test
    fun theDokumenteAdresseIsNoTileTheDokumenteTileStays() {
        EinrichtungStore.neu(ctx(), antwort("dokumente"))
        KannStore.neu(ctx(), """{"version":1,"module":[],"app":{"mehr":["dokumente-adresse"]},"dokumente_adresse":"anna+doc+@example.org"}""", null)
        val namen = kacheln(start()).map { it.name() }
        assertTrue(namen.toString(), "Dokumente" in namen)
        assertFalse(namen.toString(), "Dokumente-Adresse" in namen)
    }

    @Test
    fun aNewAreaWearsTheDotUntilOpened() {
        EinrichtungStore.neu(ctx(), antwort("dokumente", "musik"))
        val erst = start()
        assertTrue("the areas there at the first draw count as opened", kacheln(erst).none { it.neu() })
        EinrichtungStore.neu(ctx(), antwort("dokumente", "musik", "rezepte"))
        val dann = start()
        val rezepte = kacheln(dann).single { it.name() == "Rezepte" }
        assertTrue(rezepte.neu())
        assertEquals("neu", rezepte.findViewById<TextView>(R.id.bereich_stand).text.toString())
        assertFalse(kacheln(dann).single { it.name() == "Musik" }.neu())
        rezepte.performClick()
        assertEquals("https://homes.example/rezepte", shadowOf(dann).nextStartedActivity?.dataString)
        assertFalse("opened: no dot", kacheln(start()).single { it.name() == "Rezepte" }.neu())
    }

    @Test
    fun aTileSaysHomesLineAndWhatIsNewOnceUntilOpened() {
        EinrichtungStore.neu(ctx(), antwort("dokumente", "rezepte"))
        start() // the first draw: all seen
        EinrichtungStore.neu(
            ctx(),
            antwort(
                "dokumente", "rezepte",
                bereiche = """{"dokumente":{"stand":"3 diese Woche"},"rezepte":{"neu":"Rezepte an einem Ort"},
                    "ausloeser":{"stand":"3 Tags · zuletzt heute 23:14","neu":"Tags benennen und mit Automationen verbinden"}}""",
            ),
        )
        val a = start()
        val dok = kacheln(a).single { it.name() == "Dokumente" }
        assertFalse(dok.neu())
        assertEquals("3 diese Woche", dok.findViewById<TextView>(R.id.bereich_stand).text.toString())
        val rezepte = kacheln(a).single { it.name() == "Rezepte" }
        assertTrue("seen before, but Homes says something is new in it", rezepte.neu())
        assertEquals("neu: Rezepte an einem Ort", rezepte.findViewById<TextView>(R.id.bereich_stand).text.toString())
        val ausloeser = kacheln(a).single { it.name() == a.getString(R.string.ausloeser_title) }
        assertEquals("neu: Tags benennen und mit Automationen verbinden", ausloeser.findViewById<TextView>(R.id.bereich_stand).text.toString())
        ausloeser.performClick()
        val danach = kacheln(start()).single { it.name() == a.getString(R.string.ausloeser_title) }
        assertFalse("opened: what is new is seen", danach.neu())
        assertEquals("then its line", "3 Tags · zuletzt heute 23:14", danach.findViewById<TextView>(R.id.bereich_stand).text.toString())
        assertTrue("the other still new", kacheln(start()).single { it.name() == "Rezepte" }.neu())
    }

    private fun aufgabe(dringend: Boolean) = """{"version":1,"offen":1,"module":[{"name":"zugang","titel":"Zugang für Ben","icon":"zugang",
        "web":"https://homes.example/zugang","nur_web":true,"aufgabe":{"titel":"Zugang für Ben","erledigt":false,
        "einrichten":"https://homes.example/zugang","dringend":$dringend}}]}"""

    @Test
    fun theTaskCountIsGreyAndOrangeWhileOneIsUrgent() {
        fun farbe(a: HomeActivity) = (a.findViewById<View>(R.id.aufgaben_zahl).background as GradientDrawable).color?.defaultColor
        EinrichtungStore.neu(ctx(), aufgabe(false))
        val ruhig = start()
        assertEquals(ruhig.getColor(R.color.homes_text_muted), farbe(ruhig))
        EinrichtungStore.neu(ctx(), aufgabe(true))
        val dringend = start()
        assertEquals(dringend.getColor(R.color.homes_accent), farbe(dringend))
    }

    @Test
    fun threeTilesARowAndTheLastKeepsTheColumns() {
        EinrichtungStore.neu(ctx(), antwort("dokumente", "musik", "rezepte", "fotos"))
        val g = start().findViewById<ViewGroup>(R.id.ueberblick_group)
        // four modules, Auslöser (what the box can do unknown: as before) and Homes: two rows of three
        assertEquals(2, g.childCount)
        assertEquals(3, (g.getChildAt(1) as ViewGroup).childCount)
    }

    // --- „Dieses Telefon“ ------------------------------------------------------------------------

    @Test
    fun theTelefonRowNamesThePhone() {
        val a = start()
        assertEquals("Dieses Telefon · " + Telefon.name(ctx()), a.findViewById<TextView>(R.id.telefon_zeile_titel).text.toString())
        assertEquals("nothing set up: no line", View.GONE, a.findViewById<View>(R.id.telefon_zeile_stand).visibility)
    }

    @Test
    fun theVersionIsTheStartScreensLastLine() {
        val v = start().findViewById<TextView>(R.id.home_version)
        assertTrue(v.text.toString(), v.text.startsWith("Version ") && v.text.endsWith(" · gekoppelt mit homes.example"))
        TokenStore.clear(ctx())
        ServerStore.clear(ctx())
        val los = start().findViewById<TextView>(R.id.home_version).text.toString()
        assertTrue(los, los.startsWith("Version ") && los.endsWith(" · nicht gekoppelt"))
    }

    @Test
    fun thePagePairsAndShowsTheVersion() {
        val t = Robolectric.buildActivity(TelefonActivity::class.java).setup().get()
        assertEquals("Gekoppelt mit homes.example", t.findViewById<TextView>(R.id.status).text.toString())
        assertTrue(t.findViewById<TextView>(R.id.app_version).text.startsWith("Version "))
        assertEquals(Telefon.name(ctx()), t.findViewById<TextView>(R.id.telefon_name).text.toString())
    }
}
