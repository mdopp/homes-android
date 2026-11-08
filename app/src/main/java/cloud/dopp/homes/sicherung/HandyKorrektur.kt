package cloud.dopp.homes.sicherung

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import cloud.dopp.homes.konto.HomesKonto

/**
 * The phone's contacts for Kontakt prüfen lassen (owner 2026-11-07): the one
 * place the app writes a person's own contact, and only the data rows a
 * correction the person accepted on Homes names ([Korrigieren]).
 *
 * The UID is the one „Kontakte übertragen“ sent ([HandyKontakte]): the
 * contact's lookup key without the Homes account's part, which Android
 * resolves even after it joined or split raw contacts
 * (`Contacts.lookupContact`); a contact that had no key went by its id,
 * which Android may give another contact later, so that one is taken only
 * when its name is still the one the link showed. Its raw contacts that may
 * be written are those not of the Homes account, not deleted and not read
 * only (a messenger's). The writes go as the app, not as a sync adapter, so
 * the account they belong to (Google) syncs them as the person's own edit.
 */
class HandyKorrektur(private val ctx: Context) : Kontaktbuch {

    override fun zeilen(uid: String, name: String): List<Zeile>? {
        val r = ctx.contentResolver
        val kontakt = if (uid.all { it.isDigit() }) {
            uid.toLongOrNull()?.takeIf { anzeigename(it)?.trim() == name.trim() }
        } else {
            ContactsContract.Contacts.lookupContact(r, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, Uri.encode(uid)))
                ?.let { ContentUris.parseId(it) }
        } ?: return null
        val roh = ArrayList<Long>()
        r.query(
            ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0 AND " +
                "${ContactsContract.RawContacts.RAW_CONTACT_IS_READ_ONLY} = 0 AND " +
                "(${ContactsContract.RawContacts.ACCOUNT_TYPE} IS NULL OR ${ContactsContract.RawContacts.ACCOUNT_TYPE} != ?)",
            arrayOf(kontakt.toString(), HomesKonto.TYP), null,
        )?.use { c -> while (c.moveToNext()) roh += c.getLong(0) }
        if (roh.isEmpty()) return null
        val out = ArrayList<Zeile>()
        val spalten = arrayOf(ContactsContract.Data._ID, ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.Data.MIMETYPE) + DATEN
        r.query(
            ContactsContract.Data.CONTENT_URI, spalten,
            "${ContactsContract.Data.RAW_CONTACT_ID} IN (${roh.joinToString(",") { "?" }}) AND ${ContactsContract.Data.MIMETYPE} IN (${MIMES.joinToString(",") { "?" }})",
            (roh.map { it.toString() } + MIMES).toTypedArray(), "${ContactsContract.Data.RAW_CONTACT_ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val werte = DATEN.associateWith { s -> c.getColumnIndex(s).let { i -> if (i < 0 || c.isNull(i)) null else c.getString(i) } }
                out += Zeile(c.getLong(0), c.getLong(1), c.getString(2), werte)
            }
        }
        // a raw contact without any of these rows still takes new ones: the first of them
        return out.ifEmpty { listOf(Zeile(-1, roh.first(), "", emptyMap())) }
    }

    private fun anzeigename(id: Long): String? =
        ctx.contentResolver.query(
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id), arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    override fun schreiben(schritte: List<Schritt>) {
        val ops = ArrayList<ContentProviderOperation>()
        for (s in schritte) {
            ops += when (s) {
                is Schritt.Aendern -> ContentProviderOperation.newUpdate(zeile(s.zeile)).also { b -> s.werte.forEach { (k, v) -> b.withValue(k, v) } }.build()
                is Schritt.Neu -> ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, s.roh).withValue(ContactsContract.Data.MIMETYPE, s.mime)
                    .also { b -> s.werte.forEach { (k, v) -> b.withValue(k, v) } }.build()
                is Schritt.Weg -> ContentProviderOperation.newDelete(zeile(s.zeile)).build()
            }
        }
        if (ops.isNotEmpty()) ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    private fun zeile(id: Long): Uri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id)

    private companion object {
        val DATEN = (1..10).map { "data$it" }.toTypedArray()
        val MIMES = listOf(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
        )
    }
}
