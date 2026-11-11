package cloud.dopp.homes

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.konto.HomesKonto
import cloud.dopp.homes.sicherung.HandyKontakte
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * No loop (#206): the Homes account's shared contacts never go with
 * „Kontakte übertragen“ into the person's archive. Against a contacts
 * provider that answers as Android's does — every account's rows in
 * `Data`, a joined contact's lookup key made of each raw contact's part —
 * the export keeps the person's own contacts as they were, a contact Android
 * joined from the person's own and a shared one with the person's data and
 * the UID it had before the join, and leaves out what is only shared, also
 * when a messenger joined its own rows to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomesKontoAusschlussTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** One row of `Data` as the provider joins it: its contact, the contact's key and name, its raw contact's account. */
    class Reihe(
        val kontakt: Long, val schluessel: String, val name: String, val konto: String,
        val mime: String, val data1: String?, val data2: String? = null, val foto: ByteArray? = null,
    )

    /** A raw contact: its contact, account type and SOURCE_ID. */
    class Roh(val kontakt: Long, val konto: String, val quelle: String?)

    class FakeKontakte : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val spalten = projection!!
            val c = MatrixCursor(spalten)
            when (uri.path) {
                ContactsContract.RawContacts.CONTENT_URI.path -> {
                    // what HandyKontakte asks: the raw contacts of one account type
                    assertTrue(selection, selection!!.contains(ContactsContract.RawContacts.ACCOUNT_TYPE))
                    roh.filter { it.konto == args!![0] }.forEach { r ->
                        c.addRow(spalten.map { s -> if (s == ContactsContract.RawContacts.CONTACT_ID) r.kontakt else r.quelle }.toTypedArray())
                    }
                }
                ContactsContract.Data.CONTENT_URI.path -> reihen.sortedBy { it.kontakt }.forEach { r ->
                    c.addRow(
                        spalten.map { s ->
                            when (s) {
                                ContactsContract.Data.CONTACT_ID -> r.kontakt
                                ContactsContract.Data.LOOKUP_KEY -> r.schluessel
                                ContactsContract.Data.DISPLAY_NAME -> r.name
                                ContactsContract.Data.MIMETYPE -> r.mime
                                ContactsContract.RawContacts.ACCOUNT_TYPE -> r.konto
                                ContactsContract.Data.DATA1 -> r.data1
                                ContactsContract.Data.DATA2 -> r.data2
                                ContactsContract.Data.RAW_CONTACT_ID -> r.kontakt
                                Photo.PHOTO -> r.foto
                                else -> null
                            }
                        }.toTypedArray(),
                    )
                }
            }
            return c
        }
    }

    @Test
    fun theSharedContactsStayOutOfThePersonsArchive() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        Robolectric.setupContentProvider(FakeKontakte::class.java, ContactsContract.AUTHORITY)

        val google = "com.google"
        val wa = "com.whatsapp"
        val homes = HomesKonto.TYP
        val omaQuelle = HomesKonto.quelle("urn:uuid:oma")
        val klempnerQuelle = HomesKonto.quelle("urn:uuid:klempner")
        val schuleQuelle = HomesKonto.quelle("urn:uuid:schule")
        val omaEigen = "1473i63fbb30c8fa4bc5d"
        // Oma: the person's own Google contact, joined by Android with the shared card; the shared name is the contact's
        val omaKey = "$omaEigen.${HomesKonto.schluesselTeil(omaQuelle)}"
        roh = listOf(
            Roh(1, google, "63fbb30c8fa4bc5d"), Roh(1, homes, omaQuelle),
            Roh(2, homes, klempnerQuelle), Roh(2, wa, "4917099@s.whatsapp.net"),
            Roh(3, homes, schuleQuelle),
            Roh(4, google, "ben"),
        )
        reihen = listOf(
            Reihe(1, omaKey, "Oma Erna Müller", google, StructuredName.CONTENT_ITEM_TYPE, "Oma"),
            Reihe(1, omaKey, "Oma Erna Müller", google, Phone.CONTENT_ITEM_TYPE, "+49 170 1111111", Phone.TYPE_MOBILE.toString()),
            Reihe(1, omaKey, "Oma Erna Müller", homes, StructuredName.CONTENT_ITEM_TYPE, "Oma Erna Müller"),
            Reihe(1, omaKey, "Oma Erna Müller", homes, Phone.CONTENT_ITEM_TYPE, "+49 89 2222222", Phone.TYPE_HOME.toString()),
            Reihe(1, omaKey, "Oma Erna Müller", homes, Email.CONTENT_ITEM_TYPE, "erna@homes.example"),
            // only shared, with WhatsApp's own rows joined: a name and its profile
            Reihe(2, "${HomesKonto.schluesselTeil(klempnerQuelle)}.2741i4917099", "Klempner Huber", homes, StructuredName.CONTENT_ITEM_TYPE, "Klempner Huber"),
            Reihe(2, "${HomesKonto.schluesselTeil(klempnerQuelle)}.2741i4917099", "Klempner Huber", homes, Phone.CONTENT_ITEM_TYPE, "+49 89 3333333"),
            Reihe(2, "${HomesKonto.schluesselTeil(klempnerQuelle)}.2741i4917099", "Klempner Huber", wa, StructuredName.CONTENT_ITEM_TYPE, "Klempner Huber"),
            Reihe(2, "${HomesKonto.schluesselTeil(klempnerQuelle)}.2741i4917099", "Klempner Huber", wa, "vnd.android.cursor.item/vnd.com.whatsapp.profile", "4917099@s.whatsapp.net"),
            // only shared
            Reihe(3, HomesKonto.schluesselTeil(schuleQuelle), "Schule", homes, StructuredName.CONTENT_ITEM_TYPE, "Schule"),
            Reihe(3, HomesKonto.schluesselTeil(schuleQuelle), "Schule", homes, Phone.CONTENT_ITEM_TYPE, "+49 89 4444444"),
            // the person's own, nothing shared: as before
            Reihe(4, "1473iben", "Ben Bauer", google, StructuredName.CONTENT_ITEM_TYPE, "Ben Bauer"),
            Reihe(4, "1473iben", "Ben Bauer", google, Phone.CONTENT_ITEM_TYPE, "+49 170 5555555", Phone.TYPE_MOBILE.toString()),
        )

        val datei = tmp.newFile("kontakte.vcf")
        val n = HandyKontakte(app).exportieren(datei)
        val text = datei.readText()
        assertEquals(text, 2, n)
        assertTrue(text, text.contains("UID:$omaEigen\r\n"))
        assertTrue(text, text.contains("FN:Oma\r\n"))
        assertTrue(text, text.contains("+49 170 1111111"))
        assertTrue(text, text.contains("UID:1473iben\r\n") && text.contains("FN:Ben Bauer\r\n"))
        listOf("Erna", "+49 89 2222222", "erna@homes.example", "Klempner", "3333333", "Schule", "4444444", HomesKonto.schluesselTeil(omaQuelle))
            .forEach { assertFalse("$it went into the archive:\n$text", text.contains(it)) }
    }

    /**
     * A contact's picture goes with it (homes#298): the person's own as its
     * PHOTO; the face Homes added to a joined one never; a picture a
     * messenger joined to a shared contact makes it no card.
     */
    @Test
    fun thePersonsOwnPictureGoesAlong() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        Robolectric.setupContentProvider(FakeKontakte::class.java, ContactsContract.AUTHORITY)
        val google = "com.google"
        val homes = HomesKonto.TYP
        val schule = HomesKonto.quelle("urn:uuid:schule")
        val ben = jpeg(0xff3366cc.toInt())
        val gesicht = jpeg(0xffcc6633.toInt())
        roh = listOf(Roh(4, google, "ben"), Roh(1, google, "oma"), Roh(1, homes, HomesKonto.quelle("urn:uuid:oma")), Roh(3, homes, schule), Roh(3, "org.thoughtcrime.securesms", "s"))
        reihen = listOf(
            Reihe(1, "1473ioma", "Oma", google, StructuredName.CONTENT_ITEM_TYPE, "Oma"),
            Reihe(1, "1473ioma", "Oma", google, Phone.CONTENT_ITEM_TYPE, "+49 170 1111111"),
            Reihe(1, "1473ioma", "Oma", homes, Photo.CONTENT_ITEM_TYPE, null, foto = gesicht),
            Reihe(3, HomesKonto.schluesselTeil(schule), "Schule", homes, StructuredName.CONTENT_ITEM_TYPE, "Schule"),
            Reihe(3, HomesKonto.schluesselTeil(schule), "Schule", "org.thoughtcrime.securesms", Photo.CONTENT_ITEM_TYPE, null, foto = jpeg(0xff00ff00.toInt())),
            Reihe(4, "1473iben", "Ben Bauer", google, StructuredName.CONTENT_ITEM_TYPE, "Ben Bauer"),
            Reihe(4, "1473iben", "Ben Bauer", google, Photo.CONTENT_ITEM_TYPE, null, foto = ben),
        )
        val datei = tmp.newFile("kontakte.vcf")
        val n = HandyKontakte(app).exportieren(datei)
        val text = datei.readText().replace("\r\n ", "")
        assertEquals(text, 2, n)
        assertTrue(text, text.contains("FN:Ben Bauer\r\nN:;;;;\r\nPHOTO;ENCODING=b;TYPE=JPEG:${Base64.encodeToString(ben, Base64.NO_WRAP)}\r\n"))
        assertEquals(text, 1, Regex("PHOTO").findAll(text).count())
        assertFalse(text, text.contains("Schule"))
    }

    /**
     * Android joins Google's, WhatsApp's and Signal's raw contacts into one contact, each with its
     * own row of the same number (homes#359: 394 of 530 cards carried one twice): the export sends
     * each number once, compared as Homes does (0171 … is +49 171 …), the line that says most; red before.
     */
    @Test
    fun aNumberGoesOncePerContact() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        Robolectric.setupContentProvider(FakeKontakte::class.java, ContactsContract.AUTHORITY)
        val mobil = Phone.TYPE_MOBILE.toString()
        roh = listOf(Roh(5, "com.google", "ben"), Roh(5, "com.whatsapp", "w"), Roh(5, "org.thoughtcrime.securesms", "s"))
        reihen = listOf(
            Reihe(5, "1473iben", "Ben Bauer", "com.google", StructuredName.CONTENT_ITEM_TYPE, "Ben Bauer"),
            Reihe(5, "1473iben", "Ben Bauer", "com.google", Phone.CONTENT_ITEM_TYPE, "0171 1234567", mobil),
            Reihe(5, "1473iben", "Ben Bauer", "com.google", Phone.CONTENT_ITEM_TYPE, "0711 123", Phone.TYPE_HOME.toString()),
            Reihe(5, "1473iben", "Ben Bauer", "com.whatsapp", Phone.CONTENT_ITEM_TYPE, "+49 171 1234567", mobil),
            Reihe(5, "1473iben", "Ben Bauer", "org.thoughtcrime.securesms", Phone.CONTENT_ITEM_TYPE, "+491711234567"),
            Reihe(5, "1473iben", "Ben Bauer", "org.thoughtcrime.securesms", Phone.CONTENT_ITEM_TYPE, "+43 171 1234567"),
        )
        val datei = tmp.newFile("kontakte.vcf")
        assertEquals(1, HandyKontakte(app).exportieren(datei))
        val tel = datei.readText().split("\r\n").filter { it.startsWith("TEL") }
        assertEquals(listOf("TEL;TYPE=HOME,VOICE:0711 123", "TEL;TYPE=CELL,VOICE:+49 171 1234567", "TEL;TYPE=VOICE:+43 171 1234567"), tel)
    }

    private fun jpeg(farbe: Int): ByteArray {
        val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(farbe) }
        return ByteArrayOutputStream().use { out ->
            b.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }

    companion object {
        @JvmStatic
        var roh: List<Roh> = emptyList()

        @JvmStatic
        var reihen: List<Reihe> = emptyList()
    }
}
