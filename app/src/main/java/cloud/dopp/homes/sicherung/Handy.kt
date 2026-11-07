package cloud.dopp.homes.sicherung

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName as Name
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal as Postal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.Telephony
import android.util.Base64
import androidx.core.content.ContextCompat
import cloud.dopp.homes.konto.HomesKonto
import cloud.dopp.homes.konto.KontakteAbgleich
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/** The permissions behind the two switches (laib ADR 0029): each switch asks for its own. */
object Erlaubnis {
    val KONTAKTE = arrayOf(Manifest.permission.READ_CONTACTS)

    /** Kontakt prüfen lassen (owner 2026-11-07): the corrections the person took go into their own contacts. */
    val KONTAKTE_AENDERN = arrayOf(Manifest.permission.WRITE_CONTACTS)

    /** What the contacts' switch asks for: reading them for the archive, and changing them by an accepted correction. */
    val KONTAKTE_SCHALTER = KONTAKTE + KONTAKTE_AENDERN
    val SMS = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG)

    fun hat(ctx: Context, rechte: Array<String>): Boolean =
        rechte.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
}

/**
 * The phone's address book through ContactsContract (READ_CONTACTS): every
 * row of `ContactsContract.Data` in one pass, grouped by contact, each
 * contact written as a vCard 3.0 of the app's own ([Vcard.karte]) with
 * `UID:<lookup key>`, one after the other into the file. Not Android's own
 * vCard export: that is 2.1, which the box's Radicale may refuse (homes#210).
 * Read only. A contact's picture goes as its PHOTO (homes#298: Homes asks
 * Immich whose face it is): Android's large one, else the thumbnail, at most
 * 256 px a side; a picture alone makes no shared contact the person's.
 *
 * The Homes account's contacts are not the person's (#206): they come from
 * Homes and never go back to the archive. Their rows are left out; a contact
 * Android joined from the person's own and a shared one keeps the UID it
 * has without the shared one ([HomesKonto.ohneHomes]) and the person's own
 * name and numbers only; one that holds nothing of the person's (a shared
 * contact with only a messenger's rows joined to it, a name at most) is
 * no card at all.
 */
class HandyKontakte(private val ctx: Context) : Adressbuch {

    override fun exportieren(datei: File): Int {
        if (!Erlaubnis.hat(ctx, Erlaubnis.KONTAKTE)) throw SecurityException("READ_CONTACTS")
        val geteilt = homesQuellen()
        var n = 0
        OutputStreamWriter(FileOutputStream(datei), Charsets.UTF_8).buffered().use { w ->
            ctx.contentResolver.query(
                ContactsContract.Data.CONTENT_URI, SPALTEN, null, null, "${ContactsContract.Data.CONTACT_ID} ASC",
            )?.use { c ->
                var id = -1L
                var k: Kontakt? = null
                var mitHomes = false
                var eigen = false
                fun fertig() {
                    val karte = k ?: return
                    if (!mitHomes || eigen) {
                        w.write(Vcard.karte(karte))
                        n++
                    }
                }
                while (c.moveToNext()) {
                    val kid = c.getLong(c.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID))
                    if (kid != id || k == null) {
                        fertig()
                        id = kid
                        val quellen = geteilt[kid].orEmpty()
                        mitHomes = quellen.isNotEmpty()
                        eigen = false
                        val schluessel = text(c, ContactsContract.Data.LOOKUP_KEY)?.let { HomesKonto.ohneHomes(it, quellen) }
                        val uid = schluessel?.ifEmpty { null } ?: kid.toString()
                        // a joined contact's display name may be the shared card's: the person's own name row says it
                        k = Kontakt(uid = uid, anzeigename = if (mitHomes) null else text(c, ContactsContract.Data.DISPLAY_NAME))
                    }
                    if (text(c, ContactsContract.RawContacts.ACCOUNT_TYPE) == HomesKonto.TYP) continue
                    if (text(c, ContactsContract.Data.MIMETYPE) == Photo.CONTENT_ITEM_TYPE) {
                        k = k!!.copy(foto = k!!.foto ?: bild(c))
                        continue
                    }
                    val vorher = k!!
                    k = dazu(vorher, c)
                    // a name alone is not the person's: a messenger joins its own name row to every contact it knows
                    if (k != vorher && text(c, ContactsContract.Data.MIMETYPE) != Name.CONTENT_ITEM_TYPE) eigen = true
                }
                fertig()
            }
        }
        return n
    }

    /** The picture of photo row [c]'s raw contact, as it goes to Homes: base64; null when it is none. */
    private fun bild(c: Cursor): String? {
        val roh = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, c.getLong(c.getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID)))
        val gross = runCatching {
            ctx.contentResolver.openAssetFileDescriptor(Uri.withAppendedPath(roh, ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY), "r")
                ?.createInputStream()?.use { it.readBytes() }
        }.getOrNull()
        val jpeg = (gross ?: c.getBlob(c.getColumnIndexOrThrow(Photo.PHOTO)))?.let { KontakteAbgleich.verkleinert(it) } ?: return null
        return Base64.encodeToString(jpeg, Base64.NO_WRAP)
    }

    /** The Homes account's raw contacts, by the contact Android joined each into: their SOURCE_IDs. */
    private fun homesQuellen(): Map<Long, List<String>> {
        val out = HashMap<Long, MutableList<String>>()
        ctx.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID, ContactsContract.RawContacts.SOURCE_ID),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
            arrayOf(HomesKonto.TYP), null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.isNull(0)) continue // one made on the phone has its SOURCE_ID once it went to Homes
                out.getOrPut(c.getLong(0)) { ArrayList() }.add(c.getString(1).orEmpty())
            }
        }
        return out
    }

    companion object {
        /** [k] with what the data row [c] adds. */
        fun dazu(k: Kontakt, c: Cursor): Kontakt {
            fun t(spalte: String) = text(c, spalte)
            fun typ(spalte: String) = c.getColumnIndex(spalte).let { if (it < 0 || c.isNull(it)) 0 else c.getInt(it) }
            return when (t(ContactsContract.Data.MIMETYPE)) {
                Name.CONTENT_ITEM_TYPE -> k.copy(
                    anzeigename = k.anzeigename ?: t(Name.DISPLAY_NAME),
                    nachname = t(Name.FAMILY_NAME), vorname = t(Name.GIVEN_NAME), weitere = t(Name.MIDDLE_NAME),
                    praefix = t(Name.PREFIX), suffix = t(Name.SUFFIX),
                )
                Phone.CONTENT_ITEM_TYPE -> t(Phone.NUMBER)?.let { k.copy(telefone = k.telefone + Eintrag(it, telefonTypen(typ(Phone.TYPE)))) } ?: k
                Email.CONTENT_ITEM_TYPE -> t(Email.ADDRESS)?.let { k.copy(mails = k.mails + Eintrag(it, ortTyp(typ(Email.TYPE)))) } ?: k
                Postal.CONTENT_ITEM_TYPE -> k.copy(
                    adressen = k.adressen + Adresse(
                        typen = ortTyp(typ(Postal.TYPE)), postfach = t(Postal.POBOX), zusatz = t(Postal.NEIGHBORHOOD),
                        strasse = t(Postal.STREET), ort = t(Postal.CITY), region = t(Postal.REGION), plz = t(Postal.POSTCODE),
                        land = t(Postal.COUNTRY),
                    ),
                )
                Organization.CONTENT_ITEM_TYPE -> k.copy(
                    firma = k.firma ?: t(Organization.COMPANY), abteilung = k.abteilung ?: t(Organization.DEPARTMENT),
                    titel = k.titel ?: t(Organization.TITLE),
                )
                Event.CONTENT_ITEM_TYPE ->
                    if (typ(Event.TYPE) == Event.TYPE_BIRTHDAY) k.copy(geburtstag = k.geburtstag ?: t(Event.START_DATE)) else k
                Note.CONTENT_ITEM_TYPE -> t(Note.NOTE)?.let { k.copy(notiz = listOfNotNull(k.notiz, it).joinToString("\n")) } ?: k
                Website.CONTENT_ITEM_TYPE -> t(Website.URL)?.let { u ->
                    k.copy(webseiten = k.webseiten + Eintrag(u, label = t(Website.LABEL), gruppe = t(ContactsContract.Data.SYNC1)))
                } ?: k
                else -> k
            }
        }

        private fun text(c: Cursor, spalte: String): String? {
            val i = c.getColumnIndex(spalte)
            return if (i < 0 || c.isNull(i)) null else c.getString(i)?.takeIf { it.isNotBlank() }
        }

        private val SPALTEN = arrayOf(
            ContactsContract.Data.CONTACT_ID, ContactsContract.Data.LOOKUP_KEY, ContactsContract.Data.DISPLAY_NAME,
            ContactsContract.Data.MIMETYPE, ContactsContract.RawContacts.ACCOUNT_TYPE,
            ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3, ContactsContract.Data.DATA4,
            ContactsContract.Data.DATA5, ContactsContract.Data.DATA6, ContactsContract.Data.DATA7, ContactsContract.Data.DATA8,
            ContactsContract.Data.DATA9, ContactsContract.Data.DATA10, ContactsContract.Data.RAW_CONTACT_ID, Photo.PHOTO,
        )

        /** Android's phone type as vCard 3.0 TYPE values. */
        fun telefonTypen(t: Int): List<String> = when (t) {
            Phone.TYPE_MOBILE -> listOf("cell", "voice")
            Phone.TYPE_HOME -> listOf("home", "voice")
            Phone.TYPE_WORK -> listOf("work", "voice")
            Phone.TYPE_WORK_MOBILE -> listOf("work", "cell", "voice")
            Phone.TYPE_FAX_HOME -> listOf("home", "fax")
            Phone.TYPE_FAX_WORK -> listOf("work", "fax")
            Phone.TYPE_PAGER, Phone.TYPE_WORK_PAGER -> listOf("pager")
            Phone.TYPE_MAIN -> listOf("pref", "voice")
            else -> listOf("voice")
        }

        /** Home or work, for e-mail and addresses; other kinds carry no type. */
        fun ortTyp(t: Int): List<String> = when (t) {
            1 -> listOf("home") // TYPE_HOME of Email and StructuredPostal
            2 -> listOf("work") // TYPE_WORK of both
            else -> emptyList()
        }
    }
}

/**
 * The phone's SMS, MMS (Telephony, READ_SMS) and call log (CallLog,
 * READ_CALL_LOG), from the marks on (the marks included), oldest first, as SyncTech XML
 * ([SmsXml]). Read only. The contact's name comes from the address book when
 * „Kontakte übertragen“ may read it, else it is SyncTech's `(Unknown)`.
 */
class HandyTelefon(private val ctx: Context) : Telefon {

    private val cr: ContentResolver = ctx.contentResolver
    private val namen = HashMap<String, String?>()

    override fun nachrichten(datei: File, seitSms: Long, seitMms: Long): Auszug? {
        if (!Erlaubnis.hat(ctx, arrayOf(Manifest.permission.READ_SMS))) throw SecurityException("READ_SMS")
        // From the mark on, the mark included (>=): a message that came in the same
        // millisecond (an MMS: the same second) as the last one sent is not missed.
        // The boundary message goes again; the archive's msgvault import keeps one
        // of each message (stable IDs), so it adds nothing there.
        // MMS keep their date in seconds.
        val smsC = cr.query(Telephony.Sms.CONTENT_URI, null, "${Telephony.Sms.DATE} >= ?", arrayOf(seitSms.toString()), "${Telephony.Sms.DATE} ASC")
        val mmsC = cr.query(Telephony.Mms.CONTENT_URI, null, "${Telephony.Mms.DATE} >= ?", arrayOf((seitMms / 1000).toString()), "${Telephony.Mms.DATE} ASC")
        try {
            val anzahl = (smsC?.count ?: 0) + (mmsC?.count ?: 0)
            if (anzahl == 0) return null
            var hochSms = seitSms
            var hochMms = seitMms
            var neuSms = 0
            var neuMms = 0
            schreibe(datei) { x ->
                x.nachrichtenAnfang(anzahl, System.currentTimeMillis())
                smsC?.let { c ->
                    val spalten = SMS_SPALTEN.filter { c.getColumnIndex(it) >= 0 }
                    while (c.moveToNext()) {
                        val datum = lang(c, Telephony.Sms.DATE)
                        hochSms = maxOf(hochSms, datum)
                        if (datum > seitSms) neuSms++
                        x.sms(spalten.map { it to wert(c, it) }, datum, name(wert(c, Telephony.Sms.ADDRESS)))
                    }
                }
                mmsC?.let { c ->
                    val spalten = MMS_SPALTEN.filter { c.getColumnIndex(it) >= 0 }
                    while (c.moveToNext()) {
                        val id = lang(c, "_id")
                        val datum = lang(c, Telephony.Mms.DATE) * 1000
                        hochMms = maxOf(hochMms, datum)
                        if (datum > seitMms) neuMms++
                        val werte = spalten.map { s ->
                            s to when (s) {
                                Telephony.Mms.DATE -> datum.toString()
                                Telephony.Mms.DATE_SENT -> (lang(c, s) * 1000).toString()
                                else -> wert(c, s)
                            }
                        }
                        val adressen = adressen(id)
                        val nummern = adressen.mapNotNull { a -> a.first { it.first == "address" }.second }
                            .filter { it.isNotBlank() && it != "insert-address-token" }.distinct()
                        val kontakt = nummern.mapNotNull { name(it) }.distinct().joinToString(", ").ifBlank { null }
                        x.mmsAnfang(werte, nummern.joinToString("~").ifBlank { SmsXml.NULL }, datum, kontakt)
                        teile(id, x)
                        x.adressen(adressen)
                        x.mmsEnde()
                    }
                }
                x.nachrichtenEnde()
            }
            return Auszug(anzahl, mapOf(Art.SMS to hochSms, Art.MMS to hochMms), mapOf(Art.SMS to neuSms, Art.MMS to neuMms))
        } finally {
            smsC?.close()
            mmsC?.close()
        }
    }

    override fun anrufe(datei: File, seit: Long): Auszug? {
        if (!Erlaubnis.hat(ctx, arrayOf(Manifest.permission.READ_CALL_LOG))) throw SecurityException("READ_CALL_LOG")
        // >= the mark, as for the messages: msgvault keeps one of each call, so the boundary call adds nothing
        cr.query(CallLog.Calls.CONTENT_URI, null, "${CallLog.Calls.DATE} >= ?", arrayOf(seit.toString()), "${CallLog.Calls.DATE} ASC")?.use { c ->
            if (c.count == 0) return null
            var hoch = seit
            var neu = 0
            val spalten = ANRUF_SPALTEN.filter { c.getColumnIndex(it) >= 0 }
            schreibe(datei) { x ->
                x.anrufeAnfang(c.count, System.currentTimeMillis())
                while (c.moveToNext()) {
                    val datum = lang(c, CallLog.Calls.DATE)
                    hoch = maxOf(hoch, datum)
                    if (datum > seit) neu++
                    val nummer = wert(c, CallLog.Calls.NUMBER)
                    val kontakt = wert(c, CallLog.Calls.CACHED_NAME)?.ifBlank { null } ?: name(nummer)
                    x.anruf(spalten.map { it to wert(c, it) }, datum, kontakt)
                }
                x.anrufeEnde()
            }
            return Auszug(c.count, mapOf(Art.ANRUFE to hoch), mapOf(Art.ANRUFE to neu))
        }
        return null
    }

    private inline fun schreibe(datei: File, f: (SmsXml) -> Unit) {
        OutputStreamWriter(FileOutputStream(datei), Charsets.UTF_8).buffered(64 * 1024).use { w -> f(SmsXml(w)) }
    }

    /** An MMS's addresses: `address`, `type`, `charset`. */
    private fun adressen(id: Long): List<List<Pair<String, String?>>> {
        val out = ArrayList<List<Pair<String, String?>>>()
        cr.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type", "charset"), null, null, null)?.use { c ->
            while (c.moveToNext()) out += listOf("address" to c.getString(0), "type" to c.getString(1), "charset" to c.getString(2))
        }
        return out
    }

    /** An MMS's parts: text as `text`, media from the provider's stream as `data`. */
    private fun teile(id: Long, x: SmsXml) {
        cr.query(Uri.parse("content://mms/part"), null, "mid = ?", arrayOf(id.toString()), "seq ASC")?.use { c ->
            val spalten = TEIL_SPALTEN.filter { c.getColumnIndex(it) >= 0 }
            while (c.moveToNext()) {
                val teilId = lang(c, "_id")
                val werte = spalten.map { it to wert(c, it) }
                val text = wert(c, "text")
                val daten = if (text == null) {
                    runCatching { cr.openInputStream(Uri.parse("content://mms/part/$teilId")) }.getOrNull()
                } else {
                    null
                }
                daten.use { x.teil(werte, it) }
            }
        }
    }

    /** The address book's name for [nummer], when „Kontakte übertragen“ may read it. */
    private fun name(nummer: String?): String? {
        if (nummer.isNullOrBlank()) return null
        if (!Erlaubnis.hat(ctx, Erlaubnis.KONTAKTE)) return null
        return namen.getOrPut(nummer) {
            runCatching {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(nummer))
                cr.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
        }
    }

    private fun wert(c: Cursor, spalte: String): String? {
        val i = c.getColumnIndex(spalte)
        return if (i < 0 || c.isNull(i)) null else c.getString(i)
    }

    private fun lang(c: Cursor, spalte: String): Long {
        val i = c.getColumnIndex(spalte)
        return if (i < 0 || c.isNull(i)) 0L else c.getLong(i)
    }

    companion object {
        /** SyncTech's `<sms>` attributes, by the provider's column names. */
        val SMS_SPALTEN = listOf(
            "protocol", "address", "date", "type", "subject", "body", "toa", "sc_toa", "service_center",
            "read", "status", "locked", "date_sent", "sub_id",
        )

        /** SyncTech's `<mms>` attributes, by the provider's column names. */
        val MMS_SPALTEN = listOf(
            "date", "ct_t", "msg_box", "rr", "sub", "read_status", "seen", "m_id", "sub_cs", "resp_st", "retr_st",
            "d_tm", "text_only", "exp", "locked", "m_cls", "retr_txt", "rpt_a", "pri", "sub_id", "tr_id",
            "resp_txt", "ct_cls", "d_rpt", "v", "m_size", "read", "m_type", "date_sent", "st", "ct_l", "retr_txt_cs",
        )

        /** SyncTech's `<part>` attributes. */
        val TEIL_SPALTEN = listOf("seq", "ct", "name", "chset", "cd", "fn", "cid", "cl", "ctt_s", "ctt_t", "text")

        /** SyncTech's `<call>` attributes. */
        val ANRUF_SPALTEN = listOf(
            "number", "duration", "date", "type", "presentation", "subscription_id", "post_dial_digits",
            "subscription_component_name",
        )
    }
}
