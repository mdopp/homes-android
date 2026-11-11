package cloud.dopp.homes

import cloud.dopp.homes.archiv.Export
import cloud.dopp.homes.archiv.Export.Erkannt
import cloud.dopp.homes.archiv.InsArchiv
import cloud.dopp.homes.data.SicherungClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Share → „Ins Archiv“ (owner, 2026-11-11): a Facebook or LinkedIn export is
 * told by its structure, only its friends list or Connections.csv goes,
 * age-encrypted on the phone to the archive's key, as source `sozial`;
 * anything else never leaves the phone. Real-shaped exports: Facebook's
 * 2024 and 2022 layouts, LinkedIn's CSV with its Notes preamble.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InsArchivTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var homes: FakeHomes
    private lateinit var base: String
    private var offen = true

    @Before
    fun auf() {
        homes = FakeHomes()
        base = homes.base
    }

    @After
    fun zu() {
        if (offen) homes.schliessen()
    }

    private val identitaet = AgeLeser.identitaet("AGE-SECRET-KEY-1EGTZVFFV20835NWYV6270LXYVK2VKNX2MMDKWYKLMGR48UAWX40Q2P2LM0")

    private fun zip(vararg eintraege: Pair<String, String>): File {
        val f = tmp.newFile()
        ZipOutputStream(f.outputStream()).use { z ->
            eintraege.forEach { (name, inhalt) ->
                z.putNextEntry(ZipEntry(name))
                z.write(inhalt.toByteArray())
                z.closeEntry()
            }
        }
        return f
    }

    private fun datei(inhalt: String): File = tmp.newFile().apply { writeText(inhalt) }

    private fun ins(f: File) = InsArchiv(SicherungClient(base, FakeHomes.TOKEN, homes.http), File(tmp.root, "archiv"), { 1_762_862_400_000L }).senden(f)

    @Test
    fun theExportsAreToldByTheirStructure() {
        assertEquals(Erkannt.Ja(Export.Art.FACEBOOK, "connections/friends/your_friends.json"), Export.erkennen(zip(
            "your_facebook_activity/posts/your_posts.json" to "[]",
            "connections/friends/removed_friends.json" to "{}",
            "connections/friends/your_friends.json" to FREUNDE,
        )))
        assertEquals(Erkannt.Ja(Export.Art.FACEBOOK, "facebook-anna/friends_and_followers/friends.json"),
            Export.erkennen(zip("facebook-anna/friends_and_followers/friends.json" to FREUNDE)))
        assertEquals(Erkannt.Ja(Export.Art.LINKEDIN, "Connections.csv"),
            Export.erkennen(zip("Profile.csv" to "First Name,Last Name\n", "Connections.csv" to CONNECTIONS)))
        assertEquals(Erkannt.Ja(Export.Art.FACEBOOK, null), Export.erkennen(datei(FREUNDE)))
        assertEquals(Erkannt.Ja(Export.Art.LINKEDIN, null), Export.erkennen(datei("\uFEFF" + CONNECTIONS)))
        assertEquals(Erkannt.FacebookHtml, Export.erkennen(zip("connections/friends/your_friends.html" to "<html>")))
    }

    @Test
    fun otherFilesAreNoExport() {
        listOf(
            zip("Takeout/Standortverlauf/Records.json" to "{\"locations\":[]}"),
            zip("Album/01.mp3" to "ID3"),
            zip("connections/friends/removed_friends.json" to "{\"deleted_friends_v2\":[]}"),
            datei("{\"locations\":[]}"),
            datei("Name,Telefon\nAnna,123\n"),
            datei("Hallo"),
            tmp.newFile(),
        ).forEach { assertEquals(it.name, Erkannt.Fremd, Export.erkennen(it)) }
    }

    @Test
    fun onlyTheFriendsListGoesEncryptedAsSozial() {
        val ausgang = ins(zip("connections/friends/your_friends.json" to FREUNDE, "messages/inbox/x/message_1.json" to "{\"geheim\":1}"))
        assertEquals(InsArchiv.Ausgang.Fertig(Export.Art.FACEBOOK), ausgang)
        val (wo, bytes) = homes.fertig.entries.single()
        assertEquals("sozial|facebook-20251111T120000Z.json.age", wo)
        assertFalse("never plaintext on the wire", String(bytes).contains("Ostermann"))
        assertArrayEquals(FREUNDE.toByteArray(), AgeLeser.entschluesseln(identitaet, ByteArrayInputStream(bytes)))
        assertTrue("nothing left in the cache", File(tmp.root, "archiv").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun linkedInsCsvGoesAsItIs() {
        assertEquals(InsArchiv.Ausgang.Fertig(Export.Art.LINKEDIN), ins(datei(CONNECTIONS)))
        val bytes = homes.fertig.getValue("sozial|linkedin-20251111T120000Z.csv.age")
        assertArrayEquals(CONNECTIONS.toByteArray(), AgeLeser.entschluesseln(identitaet, ByteArrayInputStream(bytes)))
    }

    @Test
    fun aWrongFileOrHtmlNeverLeavesThePhone() {
        assertEquals(InsArchiv.Ausgang.Fremd, ins(datei("Hallo")))
        assertEquals(InsArchiv.Ausgang.FacebookHtml, ins(zip("connections/friends/your_friends.html" to "<html>")))
        assertTrue(homes.anfragen.toString(), homes.anfragen.isEmpty())
    }

    @Test
    fun homesSentencesComeBack() {
        homes.empfaenger = null
        val nein = ins(datei(FREUNDE))
        assertTrue("$nein", nein is InsArchiv.Ausgang.Nein && (nein as InsArchiv.Ausgang.Nein).satz != null)
        homes.empfaenger = AgeTest.EMPFAENGER
        homes.keinArchiv = true
        assertEquals(InsArchiv.Ausgang.Nein("Auf dieser Box ist noch kein Nachrichten-Archiv eingerichtet."), ins(datei(FREUNDE)))
        homes.schliessen()
        offen = false
        assertEquals(InsArchiv.Ausgang.Unerreichbar, ins(datei(FREUNDE)))
        assertTrue(homes.fertig.isEmpty())
    }

    private companion object {
        val FREUNDE = JSONObject().put("friends_v2", org.json.JSONArray().put(JSONObject().put("name", "Stefan Ostermann").put("timestamp", 1404158400))).toString()
        const val CONNECTIONS = "Notes:\n\"When exporting your connection data, you may notice that some of the email addresses are missing.\"\n\n" +
            "First Name,Last Name,URL,Email Address,Company,Position,Connected On\n" +
            "Stefan,Ostermann,https://www.linkedin.com/in/stefan-ostermann,s.ostermann@example.org,ACME GmbH,Entwickler,12 Mar 2019\n"
    }
}
