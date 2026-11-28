package cloud.dopp.homes

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.ausloeser.AusloeserActivity
import cloud.dopp.homes.ausloeser.Karte
import cloud.dopp.homes.ausloeser.NfcSchreiber
import cloud.dopp.homes.ausloeser.TagBefund
import cloud.dopp.homes.ausloeser.TagSchreiber
import cloud.dopp.homes.ausloeser.Typ4Tag
import cloud.dopp.homes.ausloeser.WlanAnbietenActivity
import cloud.dopp.homes.ausloeser.WlanAnbietenDienst
import cloud.dopp.homes.ausloeser.WlanAngebot
import cloud.dopp.homes.ausloeser.WlanTag
import cloud.dopp.homes.teilen.Behalten
import cloud.dopp.homes.teilen.TeilenActivity
import cloud.dopp.homes.teilen.Teilen
import cloud.dopp.homes.teilen.Tresor
import cloud.dopp.homes.teilen.Visitenkarte
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.time.Duration
import javax.crypto.KeyGenerator

/**
 * „Teilen per Berührung“ (owner approved 2026-11-28: „Ja, aber auch Karten
 * die ich ausgelesen habe“): nothing is answered without a choice, a choice
 * answers one reader within two minutes, the question comes at most every
 * 30 s, the card is a vCard 3.0, and what is kept is encrypted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TeilenTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()
    private val hex = TagBefund::hex
    private val select = hex("00A4040007D276000085010100")
    private val ok = hex("9000")
    private val nichtDa = hex("6A82")
    private val uri = NdefMessage(arrayOf(NdefRecord.createUri("https://example.org/speisekarte")))
    private val schluessel = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val vorher = Tresor.schluessel

    @Before
    fun an() {
        Tresor.schluessel = { schluessel } // Robolectric has no Keystore: the same AES-GCM with a key in memory
        WlanAnbietenActivity.warum = { null }
    }

    @After
    fun zu() {
        Tresor.schluessel = vorher
        WlanAngebot.vergessen()
        AusloeserActivity.schreiber = NfcSchreiber
        ctx().getSharedPreferences("tresor", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // --- the answer -------------------------------------------------------------------------------

    /** A reader: SELECT, the NDEF file, its length and the message in steps of MLe. */
    private fun lies(t: Typ4Tag): ByteArray? {
        if (!t.antwort(select).contentEquals(ok)) return null
        t.antwort(hex("00A4000C02E104"))
        val nlen = t.antwort(hex("00B0000002"))
        val laenge = (nlen[0].toInt() and 0xFF shl 8) or (nlen[1].toInt() and 0xFF)
        var ab = 2
        var out = ByteArray(0)
        while (ab < laenge + 2) {
            val a = t.antwort(byteArrayOf(0, 0xB0.toByte(), (ab shr 8).toByte(), ab.toByte(), 0x3B))
            out += a.copyOf(a.size - 2)
            ab += a.size - 2
        }
        return out
    }

    private fun tag() = Typ4Tag(WlanAngebot::gelesen) { WlanAngebot.nachricht(T0 + 1_000) }

    @Test
    fun nothingIsAnsweredWithoutAChoice() {
        assertArrayEquals(nichtDa, tag().antwort(select))
        assertNull(lies(tag()))
    }

    @Test
    fun aChoiceAnswersOneReaderThenNothing() {
        WlanAngebot.vorbereiten("Speisekarte", uri, nurEinmal = true)
        WlanAngebot.anfangen(T0)
        assertArrayEquals("the first reader gets it byte for byte", uri.toByteArray(), lies(tag()))
        assertTrue(WlanAngebot.geteilt)
        assertNull("then it is over", WlanAngebot.nachricht(T0 + 2_000))
        assertArrayEquals("the next reader gets nothing", nichtDa, tag().antwort(select))
    }

    @Test
    fun theWlanOfferStaysForMoreReaders() {
        WlanAngebot.vorbereiten("Zuhause", WlanTag.nachricht("Zuhause", "geheim123", WlanTag.Schutz.WPA2))
        WlanAngebot.anfangen(T0)
        assertNotNull(lies(tag()))
        assertFalse(WlanAngebot.geteilt)
        assertNotNull("not „einmal“: still offered", lies(tag()))
    }

    @Test
    fun aChoiceExpiresAfterTwoMinutes() {
        WlanAngebot.vorbereiten("Speisekarte", uri, nurEinmal = true)
        WlanAngebot.anfangen(T0)
        assertNull(WlanAngebot.nachricht(T0 + WlanAngebot.DAUER_MS))
    }

    @Test
    fun theScreenSaysGeteiltOrAbgelaufen() {
        WlanAnbietenActivity.oeffnen(ctx(), "Speisekarte", uri, einmal = true)
        val a = Robolectric.buildActivity(WlanAnbietenActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Was teilen?", a.findViewById<TextView>(R.id.anbieten_titel).text.toString())
        assertEquals("Speisekarte", a.findViewById<TextView>(R.id.anbieten_netz).text.toString())
        WlanAngebot.gelesen()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals("Geteilt.", a.findViewById<TextView>(R.id.anbieten_stand).text.toString())
        WlanAnbietenActivity.oeffnen(ctx(), "Speisekarte", uri, einmal = true)
        val b = Robolectric.buildActivity(WlanAnbietenActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(121))
        assertEquals("Nicht geteilt – abgelaufen.", b.findViewById<TextView>(R.id.anbieten_stand).text.toString())
        assertNull(WlanAngebot.nachricht())
    }

    // --- the question -----------------------------------------------------------------------------

    @Test
    fun theQuestionComesAtMostEvery30Seconds() {
        assertTrue(Teilen.fragt(1_000, 0))
        assertFalse(Teilen.fragt(30_999, 1_000))
        assertTrue(Teilen.fragt(31_000, 1_000))
        val nm = shadowOf(ctx().getSystemService(NotificationManager::class.java))
        Teilen.anfrage(ctx(), 50_000_000)
        assertEquals(1, nm.allNotifications.size)
        ctx().getSystemService(NotificationManager::class.java).cancelAll()
        Teilen.anfrage(ctx(), 50_010_000)
        assertEquals("10 s later: not again", 0, nm.size())
        Teilen.anfrage(ctx(), 50_030_000)
        assertEquals("30 s later: again", 1, nm.size())
        val n = nm.allNotifications.single()
        assertEquals("Ein Telefon möchte etwas von dir. Was teilen?", shadowOf(n).contentTitle.toString())
        assertEquals(TeilenActivity::class.java.name, shadowOf(n.contentIntent).savedIntent.component?.className)
    }

    @Test
    fun aReaderAskingWithNothingChosenPostsTheQuestionAndGetsNothing() {
        val dienst = Robolectric.setupService(WlanAnbietenDienst::class.java)
        assertArrayEquals(nichtDa, dienst.processCommandApdu(select, null))
        assertTrue(Typ4Tag.waehltNdef(select))
        assertFalse(Typ4Tag.waehltNdef(hex("00A4000C02E104")))
    }

    // --- what is shared -----------------------------------------------------------------------------

    private val anna = Visitenkarte.Kontakt("Anna Muster", "+49 30 1234", "anna@example.org", "Hauptstr. 1, 10115 Berlin", "Muster; GmbH")

    @Test
    fun theCardIsAVcard30OfTheTickedFields() {
        val v = Visitenkarte.vcard(anna, setOf(Visitenkarte.Feld.NAME, Visitenkarte.Feld.TELEFON, Visitenkarte.Feld.FIRMA))
        assertEquals(
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Anna Muster\r\nN:Muster;Anna;;;\r\nTEL;TYPE=CELL:+49 30 1234\r\nORG:Muster\\; GmbH\r\nEND:VCARD\r\n",
            v,
        )
        assertFalse("not ticked: not in it", v.contains("anna@example.org") || v.contains("Hauptstr"))
        val r = Visitenkarte.ndef(v).records.single()
        assertEquals(NdefRecord.TNF_MIME_MEDIA, r.tnf)
        assertEquals("text/vcard", String(r.type))
        assertArrayEquals(v.toByteArray(), r.payload)
    }

    @Test
    fun aContactFromItsRows() {
        val k = Visitenkarte.kontakt(
            listOf(
                "vnd.android.cursor.item/name" to "Anna Muster", "vnd.android.cursor.item/phone_v2" to "+49 30 1234",
                "vnd.android.cursor.item/phone_v2" to "+49 30 9999", "vnd.android.cursor.item/email_v2" to "anna@example.org",
            ),
        )
        assertEquals(Visitenkarte.Kontakt("Anna Muster", "+49 30 1234", "anna@example.org", null, null), k)
    }

    @Test
    fun aWlanForTheIphoneCamera() {
        val n = WlanTag.lesen(WlanTag.nutzlast("Gast;1", "ge:heim12", WlanTag.Schutz.WPA2))!!
        assertEquals("WIFI:T:WPA;S:Gast\\;1;P:ge\\:heim12;;", Visitenkarte.wifi(n))
        val offen = WlanTag.lesen(WlanTag.nutzlast("Gast", "", WlanTag.Schutz.OFFEN))!!
        assertEquals("WIFI:T:nopass;S:Gast;;", Visitenkarte.wifi(offen))
        assertEquals(200, Visitenkarte.qrBild(Visitenkarte.wifi(n), 200).width)
    }

    // --- what is kept -----------------------------------------------------------------------------------

    @Test
    fun theTresorKeepsItEncryptedAndOpensItAgain() {
        Tresor.schreiben(ctx(), "x", "geheim123")
        val roh = ctx().getSharedPreferences("tresor", Context.MODE_PRIVATE).getString("x", null)!!
        assertFalse("never in clear", roh.contains("geheim123"))
        assertEquals("geheim123", Tresor.lesen(ctx(), "x"))
        assertFalse("a fresh IV each time", Tresor.verschluesseln("a".toByteArray()) == Tresor.verschluesseln("a".toByteArray()))
        Tresor.schluessel = { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        assertNull("another key opens nothing", Tresor.lesen(ctx(), "x"))
    }

    @Test
    fun keptTagsKeepTheirBytesAndGo() {
        Behalten.behalten(ctx(), "Speisekarte", uri)
        val wlan = WlanTag.nachricht("Gast", "geheim123", WlanTag.Schutz.WPA2)
        Behalten.behalten(ctx(), "Gast-WLAN", wlan)
        val alle = Behalten.alle(ctx())
        assertEquals(listOf("Speisekarte", "Gast-WLAN"), alle.map { it.name })
        assertArrayEquals(wlan.toByteArray(), alle[1].nachricht.toByteArray())
        assertFalse("its text names it, not its content", alle[1].toString().contains("geheim"))
        Behalten.weg(ctx(), 0)
        assertEquals(listOf("Gast-WLAN"), Behalten.alle(ctx()).map { it.name })
    }

    @Test
    fun theChoiceListsTheCardTheKeptTagsAndNichts() {
        Behalten.setzeVisitenkarte(ctx(), Visitenkarte.vcard(anna, setOf(Visitenkarte.Feld.NAME)))
        Behalten.behalten(ctx(), "Gast-WLAN", WlanTag.nachricht("Gast", "geheim123", WlanTag.Schutz.WPA2))
        val a = Robolectric.buildActivity(TeilenActivity::class.java).setup().get()
        val liste = a.findViewById<ViewGroup>(R.id.teilen_liste)
        val titel = (0 until liste.childCount).map { liste.getChildAt(it).findViewById<TextView>(R.id.einr_titel).text.toString() }
        assertEquals(listOf("Meine Kontaktdaten", "QR-Code: Meine Kontaktdaten", "Gast-WLAN", "QR-Code: Gast-WLAN", "Nichts"), titel)
        liste.getChildAt(2).performClick()
        assertEquals(WlanAnbietenActivity::class.java.name, shadowOf(a).nextStartedActivity.component?.className)
        assertTrue("one answer", WlanAngebot.einmal)
        assertEquals("Gast-WLAN", WlanAngebot.ssid)
    }

    // --- „Im Telefon behalten“ on the Tag-Inhalt card -----------------------------------------------------

    private class Schreiber : TagSchreiber {
        var ergebnis: ((TagSchreiber.Ergebnis) -> Unit)? = null
        override fun bereit(ctx: Context) = TagSchreiber.Bereit.JA
        override fun an(activity: Activity, uri: () -> String?, ergebnis: (TagSchreiber.Ergebnis) -> Unit) {
            this.ergebnis = ergebnis
        }
        override fun aus(activity: Activity) = Unit
    }

    private fun halte(e: TagSchreiber.Ergebnis): AusloeserActivity {
        val s = Schreiber()
        AusloeserActivity.schreiber = s
        val a = Robolectric.buildActivity(AusloeserActivity::class.java).setup().get()
        s.ergebnis!!(e)
        shadowOf(Looper.getMainLooper()).idle()
        return a
    }

    @Test
    fun anNdefTagIsKeptUnderAName() {
        val a = halte(TagSchreiber.Ergebnis.Gelesen(uri, byteArrayOf(1)))
        val behalten = a.findViewById<Button>(R.id.inhalt_drei)
        assertEquals(View.VISIBLE, behalten.visibility)
        assertEquals("Im Telefon behalten", behalten.text.toString())
        behalten.performClick()
        val d = ShadowDialog.getLatestDialog() as AlertDialog
        (shadowOf(d).view as EditText).setText("Speisekarte")
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Speisekarte", Behalten.alle(ctx()).single().name)
        assertEquals("„Speisekarte“ ist im Telefon behalten.", a.findViewById<TextView>(R.id.inhalt_stand).text.toString())
    }

    @Test
    fun aDataCardCannotBeReplayed() {
        val a = halte(TagSchreiber.Ergebnis.EigeneDaten(Karte("MIFARE Classic 1K", byteArrayOf(4, 1, 2, 3))))
        assertTrue(a.findViewById<TextView>(R.id.inhalt_text).text.contains("Diese Karte kann ein Telefon nicht nachspielen."))
        assertEquals("no keep button", View.GONE, a.findViewById<Button>(R.id.inhalt_drei).visibility)
    }

    private companion object {
        const val T0 = 1_000_000L
    }
}
