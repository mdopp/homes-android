package cloud.dopp.homes

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.ausloeser.AusloeserActivity
import cloud.dopp.homes.ausloeser.Befund
import cloud.dopp.homes.ausloeser.Block
import cloud.dopp.homes.ausloeser.Karte
import cloud.dopp.homes.ausloeser.NfcSchreiber
import cloud.dopp.homes.ausloeser.TagBefund
import cloud.dopp.homes.ausloeser.TagSchreiber
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
import org.robolectric.shadows.ShadowDialog
import java.io.IOException

/**
 * A card with data of its own is never written (owner 2026-11-28, a field
 * case: an access card was offered as „Neuer Tag erkannt“), and what reads
 * of it without keys is shown on the screen (owner: „zeige mir die Daten“).
 * Each kind's bytes are parsed here from fakes; a written tag whose cached
 * message Android left empty is read before it counts as blank.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TagKarteTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()
    private val hex = TagBefund::hex
    private val id = byteArrayOf(4, 0x11, 0x22, 0x33)
    private val uri = NdefMessage(arrayOf(NdefRecord.createUri("https://www.dopp.cloud/tag/homes-bb1a0caf51c6")))

    // --- a written tag is read before it counts as blank -------------------------------------------

    @Test
    fun aCachedNullButReadableTagIsReadNotBlank() {
        val n = TagBefund.nachricht(null) { uri }
        assertEquals(uri, n.getOrNull())
        val e = TagBefund.einordnen(Befund(ndef = true, nachricht = n.getOrNull()), id)
        assertTrue("its content, not „Neuer Tag erkannt“: $e", e is TagSchreiber.Ergebnis.Gelesen)
        assertEquals("the cached one when Android kept it", uri, TagBefund.nachricht(uri) { error("not read again") }.getOrNull())
    }

    @Test
    fun blankOnlyAfterARealRead() {
        assertEquals(TagSchreiber.Ergebnis.Leer, TagBefund.einordnen(Befund(ndef = true, nachricht = TagBefund.nachricht(null) { null }.getOrNull()), id))
        val leer = NdefMessage(arrayOf(NdefRecord(NdefRecord.TNF_EMPTY, null, null, null)))
        assertEquals("one empty record", TagSchreiber.Ergebnis.Leer, TagBefund.einordnen(Befund(ndef = true, nachricht = leer), id))
        val weg = TagBefund.nachricht(null) { throw IOException("tag lost") }
        assertTrue(weg.isFailure)
        assertEquals(TagSchreiber.Ergebnis.Unlesbar, TagBefund.einordnen(Befund(ndef = true, unlesbar = weg.isFailure), id))
    }

    // --- what each kind is ---------------------------------------------------------------------------

    private fun karte(typ: String) = Karte(typ, id)

    @Test
    fun aCardWithDataOfItsOwnIsNeverWritable() {
        val faelle = listOf(
            Befund(ndef = false, formatierbar = true, speicherLeer = false, karte = karte("MIFARE Classic 1K")),
            Befund(ndef = false, formatierbar = false, speicherLeer = false, karte = Karte("ISO-DEP", id, anwendungen = listOf("girocard"), bank = true)),
            Befund(ndef = false, formatierbar = true, karte = Karte("ISO-DEP", id, anwendungen = listOf("Personalausweis (eID)"))),
            Befund(ndef = false, formatierbar = false, speicherLeer = false, karte = karte("FeliCa")),
            Befund(ndef = false, formatierbar = false, karte = karte("NFC-B")),
            Befund(ndef = false, formatierbar = true, speicherLeer = false, karte = karte("NTAG213")),
        )
        faelle.forEach { b ->
            val e = TagBefund.einordnen(b, id)
            assertTrue("${b.karte}: $e", e is TagSchreiber.Ergebnis.EigeneDaten)
            assertFalse("${b.karte} never written, even after a yes", TagSchreiber.darf(e, id, id))
        }
    }

    @Test
    fun aFactoryNewStickerIsBlankAndAnUnreadableOneIsAskedFor() {
        assertEquals(
            TagSchreiber.Ergebnis.Leer,
            TagBefund.einordnen(Befund(ndef = false, formatierbar = true, speicherLeer = true, karte = karte("NTAG213")), id),
        )
        val unklar = TagBefund.einordnen(Befund(ndef = false, formatierbar = true, speicherLeer = null, karte = karte("NFC-A")), id)
        assertTrue(unklar is TagSchreiber.Ergebnis.Unklar)
        assertFalse("not before the yes", TagSchreiber.darf(unklar, id, null))
        assertTrue("after „Ja, leerer Aufkleber“", TagSchreiber.darf(unklar, id, id))
    }

    @Test
    fun ultralightAndNtagByTheirVersion() {
        assertEquals("NTAG213" to 45, TagBefund.ultralight(hex("0004040201000F03")))
        assertEquals("NTAG215" to 135, TagBefund.ultralight(hex("0004040201001103")))
        assertEquals("NTAG216" to 231, TagBefund.ultralight(hex("0004040201001303")))
        assertEquals("MIFARE Ultralight EV1 (48 Bytes)" to 20, TagBefund.ultralight(hex("0004030101000B03")))
        assertEquals("MIFARE Ultralight" to 16, TagBefund.ultralight(null))
        assertTrue("all zero", TagBefund.seitenLeer(ByteArray(32)))
        assertTrue("the factory's empty NDEF TLV", TagBefund.seitenLeer(hex("0300FE00") + ByteArray(28)))
        assertFalse("data", TagBefund.seitenLeer(hex("00000000DEADBEEF") + ByteArray(24)))
    }

    @Test
    fun mifareClassicOpenedOnlyWithThePublicKeys() {
        assertEquals(listOf("FFFFFFFFFFFF", "A0A1A2A3A4A5", "D3F7D3F7D3F7", "000000000000"), TagBefund.SCHLUESSEL.map { TagBefund.hexText(it, "") })
        val hersteller = hex("04112233440804006263646566676869")
        val trailer = hex("FFFFFFFFFFFFFF078069FFFFFFFFFFFF")
        val leer = listOf(listOf(hersteller, ByteArray(16), ByteArray(16), trailer), listOf(ByteArray(16), ByteArray(16), ByteArray(16), trailer))
        assertTrue("open and zero, the maker's block and the keys aside", TagBefund.klassikLeer(leer))
        assertFalse("a sector with keys of its own", TagBefund.klassikLeer(listOf(leer[0], null)))
        val daten = listOf(leer[0], listOf(hex("000000010000000000000000000000FF"), ByteArray(16), ByteArray(16), trailer))
        assertFalse("data in a sector", TagBefund.klassikLeer(daten))
        assertEquals("MIFARE Classic 1K", TagBefund.klassikTyp(1024))
        assertEquals("MIFARE Classic 4K", TagBefund.klassikTyp(4096))
    }

    @Test
    fun isoDepNamesTheApplicationsAndNeverAsksForARecord() {
        assertEquals("00A404000E325041592E5359532E444446303100", TagBefund.hexText(TagBefund.select(TagBefund.PPSE), ""))
        // a PPSE answer listing girocard and Maestro
        val fci = hex(
            "6F2E840E325041592E5359532E4444463031A51CBF0C19" +
                "610C4F0AA0000003591010028001" + "61094F07A0000000043060" + "9000",
        )
        val aids = TagBefund.ppse(fci)
        assertEquals(listOf("girocard", "Maestro"), aids.map(TagBefund::anwendung))
        assertTrue(aids.all(TagBefund::zahlung))
        assertTrue("refused: none", TagBefund.ppse(hex("6A82")).isEmpty())
        assertEquals("Personalausweis (eID)", TagBefund.anwendung(hex("E80704007F00070302")))
        assertFalse(TagBefund.zahlung(hex("E80704007F00070302")))
        val text = TagBefund.text(Karte("ISO-DEP", id, anwendungen = listOf("girocard", "Maestro"), bank = true))
        assertTrue(text, text.contains("Bankkarte: girocard, Maestro. Die Kartennummer liest Homes nicht."))
    }

    @Test
    fun nfcVBlocksAndSize() {
        // flags 0x0F: DSFID, AFI, memory size (28 blocks of 4), IC reference
        val info = hex("000F" + "E004010012345678" + "00" + "00" + "1B03" + "01")
        assertEquals(28 to 4, TagBefund.nfcvGroesse(info))
        assertNull("refused", TagBefund.nfcvGroesse(hex("0101")))
        assertEquals(TagBefund.hexText(hex("41424344"), ""), TagBefund.hexText(TagBefund.nfcvBlock(hex("0041424344"))!!, ""))
        assertNull(TagBefund.nfcvBlock(hex("0110")))
    }

    @Test
    fun theReportShowsTypeUidSizeTechsAndHexWithAscii() {
        val k = Karte(
            "MIFARE Classic 1K", hex("04112233"), 1024, listOf("NfcA", "MifareClassic", "NdefFormatable"),
            listOf(Block("S1 B0", "Homes!".toByteArray() + ByteArray(10)), Block("Sektor 2", null)),
        )
        val t = TagBefund.text(k)
        assertTrue(t, t.contains("Typ: MIFARE Classic 1K"))
        assertTrue(t, t.contains("UID: 04:11:22:33"))
        assertTrue(t, t.contains("Speicher: 1024 Bytes"))
        assertTrue(t, t.contains("Techniken: NfcA, MifareClassic, NdefFormatable"))
        assertTrue(t, t.contains("48 6F 6D 65 73 21 00 00 00 00 00 00 00 00 00 00  Homes!.........."))
        assertTrue(t, t.contains("Sektor 2     gesperrt"))
        assertEquals("never the UID or data in its text", "Karte(MIFARE Classic 1K)", k.toString())
        assertEquals("S1 B0: 48 6F 6D 65 73 21 00 00 00 00 00 00 00 00 00 00", TagBefund.hexAlles(k))
    }

    // --- on the Auslöser screen ---------------------------------------------------------------------

    private class Schreiber : TagSchreiber {
        var ergebnis: ((TagSchreiber.Ergebnis) -> Unit)? = null
        var erlaubt: ByteArray? = null
        override fun bereit(ctx: Context) = TagSchreiber.Bereit.JA
        override fun an(activity: Activity, uri: () -> String?, ergebnis: (TagSchreiber.Ergebnis) -> Unit) {
            this.ergebnis = ergebnis
        }
        override fun aus(activity: Activity) = Unit
        override fun erlauben(id: ByteArray?) {
            erlaubt = id
        }
    }

    private val schreiber = Schreiber()

    @Before
    fun an() {
        AusloeserActivity.schreiber = schreiber
    }

    @After
    fun zu() {
        AusloeserActivity.schreiber = NfcSchreiber
    }

    private fun halte(e: TagSchreiber.Ergebnis): AusloeserActivity {
        val a = Robolectric.buildActivity(AusloeserActivity::class.java).setup().get()
        schreiber.ergebnis!!(e)
        shadowOf(Looper.getMainLooper()).idle()
        return a
    }

    private val zugang = Karte(
        "MIFARE Classic 1K", hex("04112233"), 1024, listOf("NfcA", "MifareClassic"),
        listOf(Block("S0 B1", hex("000102030405060708090A0B0C0D0E0F")), Block("Sektor 1", null)),
    )

    @Test
    fun aDataCardShowsItsDataAndOffersNoWrite() {
        val a = halte(TagSchreiber.Ergebnis.EigeneDaten(zugang))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.ausloeser_inhalt).visibility)
        assertEquals("Karte mit eigenen Daten", a.findViewById<TextView>(R.id.inhalt_titel).text.toString())
        assertEquals(
            "Karte mit eigenen Daten (MIFARE Classic 1K) – vermutlich eine Zugangs-, Bank- oder Fahrkarte. Homes beschreibt sie nicht." +
                "\nDiese Karte kann ein Telefon nicht nachspielen.",
            a.findViewById<TextView>(R.id.inhalt_text).text.toString(),
        )
        val daten = a.findViewById<TextView>(R.id.inhalt_daten).text.toString()
        assertTrue(daten, daten.contains("UID: 04:11:22:33") && daten.contains("gesperrt"))
        assertEquals(View.GONE, a.findViewById<View>(R.id.ausloeser_formular).visibility)
        val knoepfe = listOf(R.id.inhalt_eins, R.id.inhalt_zwei).map { a.findViewById<Button>(it) }.filter { it.visibility == View.VISIBLE }
        assertEquals("only „Kopieren“", listOf("Kopieren"), knoepfe.map { it.text.toString() })
        knoepfe.single().performClick()
        val clip = (ctx().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!
        assertEquals("S0 B1: 00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F", clip.getItemAt(0).text.toString())
        assertEquals("Kopiert.", a.findViewById<TextView>(R.id.inhalt_stand).text.toString())
        assertNull("nothing allowed", schreiber.erlaubt)
    }

    @Test
    fun anUnclearCardIsWrittenOnlyAfterTheQuestion() {
        val a = halte(TagSchreiber.Ergebnis.Unklar(Karte("NFC-A", id, techs = listOf("NfcA", "NdefFormatable")), id))
        assertEquals("Inhalt nicht erkennbar", a.findViewById<TextView>(R.id.inhalt_titel).text.toString())
        assertEquals(View.GONE, a.findViewById<View>(R.id.ausloeser_formular).visibility)
        val frage = a.findViewById<Button>(R.id.inhalt_eins)
        assertEquals("Ist ein leerer Aufkleber …", frage.text.toString())
        frage.performClick()
        val d = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(
            "Falls das eine Zugangskarte ist, gehen ihre Daten verloren. Ist es wirklich ein leerer Aufkleber?",
            d.findViewById<TextView>(R.id.ad_message)!!.text.toString(),
        )
        d.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("Abbrechen: nothing", schreiber.erlaubt)
        assertEquals(View.GONE, a.findViewById<View>(R.id.ausloeser_formular).visibility)
        frage.performClick()
        val stapel = (ShadowDialog.getLatestDialog() as AlertDialog).findViewById<LinearLayout>(R.id.ad_items)!!
        (0 until stapel.childCount).map { stapel.getChildAt(it) }
            .first { it.findViewById<TextView>(R.id.ar_label)?.text?.toString() == "Ja, leerer Aufkleber" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(id.contentEquals(schreiber.erlaubt))
        assertEquals("Neuer Tag erkannt – wie soll er heißen?", a.findViewById<TextView>(R.id.ausloeser_name_titel).text.toString())
    }

    @Test
    fun aTagThatWouldNotReadSaysSoNotNeuerTag() {
        val a = halte(TagSchreiber.Ergebnis.Unlesbar)
        assertEquals("Tag nicht lesbar – halte ihn ruhig noch einmal ans Telefon.", a.findViewById<TextView>(R.id.ausloeser_stand).text.toString())
        assertEquals(View.GONE, a.findViewById<View>(R.id.ausloeser_formular).visibility)
    }
}
