package cloud.dopp.homes.sicherung

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import cloud.dopp.homes.data.SicherungClient
import cloud.dopp.homes.data.SicherungClient.Korrektur
import java.io.IOException

/** One data row of one of a contact's raw contacts, the phone's own (`ContactsContract.Data`), by column. */
data class Zeile(val id: Long, val roh: Long, val mime: String, val werte: Map<String, String?>) {
    fun wert(spalte: String): String = werte[spalte]?.trim().orEmpty()
}

/** One write into the phone's contact: a row changed, one added to a raw contact, one taken away. */
sealed class Schritt {
    data class Aendern(val zeile: Long, val werte: Map<String, Any?>) : Schritt()
    data class Neu(val roh: Long, val mime: String, val werte: Map<String, Any?>) : Schritt()
    data class Weg(val zeile: Long) : Schritt()
}

/** The phone's contacts as the correction needs them; on the phone [HandyKorrektur], in the tests a list. */
interface Kontaktbuch {
    /**
     * The rows of the phone's contact [uid] (its lookup key) that the app may
     * write: those of its writable raw contacts, never the Homes account's;
     * null when the phone has no such contact. [name] is the name the link
     * showed, for a contact sent by its id alone.
     */
    fun zeilen(uid: String, name: String): List<Zeile>?

    /** [schritte], all or none. */
    fun schreiben(schritte: List<Schritt>)
}

/**
 * Kontakt prüfen lassen (owner 2026-11-07): what the person took on Homes'
 * review of one of their own contacts, as writes into the phone's contact.
 * Each change is written only where the phone still has what the review
 * showed as before: the row of that number, address, e-mail or name, or for
 * a new one none of it yet. A field the phone changed meanwhile is left
 * alone (and so not reported written); one that holds what it should already
 * is reported written with nothing to write, so a report that did not reach
 * Homes is made good by the next run. New rows go into the raw contact
 * that holds the name, else the first writable one. Pure.
 */
object Korrigieren {

    /** The writes for [k] into [zeilen], and the numbers of the changes they carry. */
    fun plan(k: Korrektur, zeilen: List<Zeile>): Pair<List<Schritt>, List<Int>> {
        val ziel = (zeilen.firstOrNull { it.mime == StructuredName.CONTENT_ITEM_TYPE } ?: zeilen.firstOrNull())?.roh
            ?: return emptyList<Schritt>() to emptyList()
        val schritte = ArrayList<Schritt>()
        val geschrieben = ArrayList<Int>()
        var stand = zeilen
        for (a in k.aenderungen) {
            val s = schritt(a, stand, ziel) ?: continue
            geschrieben += a.nr
            s.firstOrNull()?.let { schritte += it }
            stand = nachher(stand, s.firstOrNull())
        }
        return schritte to geschrieben
    }

    /** The write for [a]: a list of one, empty when nothing is to write (there already), null when it cannot be. */
    private fun schritt(a: Korrektur.Aenderung, zeilen: List<Zeile>, ziel: Long): List<Schritt>? = when (a.art) {
        "vorname", "nachname" -> name(a, zeilen, ziel)
        "telefon" -> wert(a, zeilen, ziel, Feld(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER, gleich = ::ziffern) { n -> mapOf(Phone.NUMBER to n, Phone.TYPE to telefonTyp(a.nachherArt)) })
        "mail" -> wert(a, zeilen, ziel, Feld(Email.CONTENT_ITEM_TYPE, Email.ADDRESS, gleich = { it.lowercase() }) { n -> mapOf(Email.ADDRESS to n, Email.TYPE to Email.TYPE_OTHER) })
        "adresse" -> adresse(a, zeilen, ziel)
        "geburtstag" -> einzeln(a, zeilen, ziel, GEBURTSTAG)
        "firma" -> einzeln(a, zeilen, ziel, FIRMA)
        "position" -> einzeln(a, zeilen, ziel, POSITION)
        else -> null
    }

    /** A kind of row: its mime type, the column compared, how two values are one, which rows count, and a new value's columns. */
    private class Feld(
        val mime: String,
        val spalte: String,
        val gleich: (String) -> String = { it },
        val welche: (Zeile) -> Boolean = { true },
        val werte: (String) -> Map<String, Any?>,
    )

    private val GEBURTSTAG = Feld(Event.CONTENT_ITEM_TYPE, Event.START_DATE, welche = { it.typ(Event.TYPE) == Event.TYPE_BIRTHDAY }) {
        mapOf(Event.START_DATE to it, Event.TYPE to Event.TYPE_BIRTHDAY)
    }
    private val FIRMA = Feld(Organization.CONTENT_ITEM_TYPE, Organization.COMPANY) { mapOf(Organization.COMPANY to it) }
    private val POSITION = Feld(Organization.CONTENT_ITEM_TYPE, Organization.TITLE) { mapOf(Organization.TITLE to it) }

    private fun Zeile.typ(spalte: String): Int = wert(spalte).toIntOrNull() ?: 0

    /** A first or last name, with the display name made again from both. */
    private fun name(a: Korrektur.Aenderung, zeilen: List<Zeile>, ziel: Long): List<Schritt>? {
        val vor = a.art == "vorname"
        val spalte = if (vor) StructuredName.GIVEN_NAME else StructuredName.FAMILY_NAME
        val z = zeilen.firstOrNull { it.mime == StructuredName.CONTENT_ITEM_TYPE }
        if (z == null) {
            if (a.vorher.isNotBlank() || a.nachher.isBlank()) return null
            return listOf(Schritt.Neu(ziel, StructuredName.CONTENT_ITEM_TYPE, mapOf(spalte to a.nachher, StructuredName.DISPLAY_NAME to a.nachher)))
        }
        if (z.wert(spalte) == a.nachher.trim()) return emptyList() // written already (a report that did not arrive)
        if (z.wert(spalte) != a.vorher.trim()) return null
        val given = if (vor) a.nachher.trim() else z.wert(StructuredName.GIVEN_NAME)
        val family = if (vor) z.wert(StructuredName.FAMILY_NAME) else a.nachher.trim()
        val anzeige = listOf(given, family).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
        return listOf(Schritt.Aendern(z.id, mapOf(spalte to a.nachher.trim().ifEmpty { null }, StructuredName.DISPLAY_NAME to anzeige)))
    }

    /** A number or an e-mail address: the row of the value before, by [gleich], or a new row. */
    private fun wert(a: Korrektur.Aenderung, zeilen: List<Zeile>, ziel: Long, f: Feld): List<Schritt>? {
        val (mime, spalte, gleich) = Triple(f.mime, f.spalte, f.gleich)
        val werte = f.werte
        val rows = zeilen.filter { it.mime == mime }
        val neu = a.nachher.trim()
        if (a.vorher.isBlank()) {
            if (neu.isEmpty()) return null
            return if (rows.any { gleich(it.wert(spalte)) == gleich(neu) }) emptyList() else listOf(Schritt.Neu(ziel, mime, werte(neu)))
        }
        val z = rows.firstOrNull { gleich(it.wert(spalte)) == gleich(a.vorher) }
            ?: return if (neu.isEmpty() || rows.any { gleich(it.wert(spalte)) == gleich(neu) }) emptyList() else null // done already, or changed meanwhile
        if (neu.isEmpty()) return listOf(Schritt.Weg(z.id))
        val w = werte(neu).toMutableMap()
        if (mime == Phone.CONTENT_ITEM_TYPE && a.nachherArt.isEmpty()) w.remove(Phone.TYPE) // the kind as the phone had it
        if (mime == Email.CONTENT_ITEM_TYPE) w.remove(Email.TYPE)
        return listOf(Schritt.Aendern(z.id, w))
    }

    private val TEILE = listOf(StructuredPostal.STREET, StructuredPostal.POSTCODE, StructuredPostal.CITY, StructuredPostal.COUNTRY)

    private fun teile(a: Korrektur.Anschrift): List<String> = listOf(a.strasse, a.plz, a.ort, a.land).map { norm(it) }

    private fun norm(s: String): String = s.trim().split(Regex("\\s+")).joinToString(" ").lowercase()

    /** A postal address, matched by street, code, place and country. */
    private fun adresse(a: Korrektur.Aenderung, zeilen: List<Zeile>, ziel: Long): List<Schritt>? {
        val postal = zeilen.filter { it.mime == StructuredPostal.CONTENT_ITEM_TYPE }
        fun von(x: Korrektur.Anschrift?) = x?.let { w -> postal.firstOrNull { z -> TEILE.map { norm(z.wert(it)) } == teile(w) } }
        val neu = a.nachherAdresse?.let { n -> TEILE.zip(listOf(n.strasse, n.plz, n.ort, n.land)).toMap<String, Any?>() + (StructuredPostal.FORMATTED_ADDRESS to null) }
        val z = von(a.vorherAdresse)
        return when {
            z != null -> listOf(if (neu == null) Schritt.Weg(z.id) else Schritt.Aendern(z.id, neu))
            neu == null -> if (a.vorherAdresse == null) null else emptyList() // taken away already
            von(a.nachherAdresse) != null -> emptyList() // written already
            a.vorherAdresse != null -> null // changed on the phone meanwhile
            else -> listOf(Schritt.Neu(ziel, StructuredPostal.CONTENT_ITEM_TYPE, neu + (StructuredPostal.TYPE to StructuredPostal.TYPE_HOME)))
        }
    }

    /** A field the contact has once: its row when it holds the value before (none when that is ""). */
    private fun einzeln(a: Korrektur.Aenderung, zeilen: List<Zeile>, ziel: Long, f: Feld): List<Schritt>? {
        val z = zeilen.firstOrNull { it.mime == f.mime && f.welche(it) }
        val neu = a.nachher.trim()
        val ist = z?.wert(f.spalte).orEmpty()
        return when {
            ist == neu -> emptyList() // written already, or taken away already
            ist != a.vorher.trim() -> null // changed on the phone meanwhile
            z == null -> listOf(Schritt.Neu(ziel, f.mime, f.werte(neu)))
            neu.isNotEmpty() -> listOf(Schritt.Aendern(z.id, f.werte(neu)))
            f.mime == Organization.CONTENT_ITEM_TYPE -> listOf(Schritt.Aendern(z.id, mapOf(f.spalte to null)))
            else -> listOf(Schritt.Weg(z.id))
        }
    }

    /** [zeilen] as [s] leaves them, for the next change of the same correction. */
    private fun nachher(zeilen: List<Zeile>, s: Schritt?): List<Zeile> = when (s) {
        is Schritt.Aendern -> zeilen.map { if (it.id == s.zeile) it.copy(werte = it.werte + s.werte.mapValues { (_, v) -> v?.toString() }) else it }
        is Schritt.Weg -> zeilen.filterNot { it.id == s.zeile }
        is Schritt.Neu -> zeilen + Zeile(-1L - zeilen.size, s.roh, s.mime, s.werte.mapValues { (_, v) -> v?.toString() })
        null -> zeilen
    }

    private fun ziffern(s: String): String = s.trim().filterIndexed { i, c -> c.isDigit() || (c == '+' && i == 0) }

    /** A number's kind as Android's Phone.TYPE. */
    fun telefonTyp(art: String): Int = when (art) {
        "mobil" -> Phone.TYPE_MOBILE
        "privat" -> Phone.TYPE_HOME
        "geschaeftlich" -> Phone.TYPE_WORK
        else -> Phone.TYPE_OTHER
    }
}

/**
 * The corrections' part of the backup run (owner 2026-11-07): before the
 * contacts go up, the accepted corrections come down, are written into the
 * phone's contacts ([Kontaktbuch]) and reported, so the copy that follows
 * already carries them. A contact the phone does not have is reported with
 * nothing written. Nothing is written without a correction.
 */
class KorrekturenLauf(private val client: SicherungClient, private val buch: Kontaktbuch) {
    @Suppress("SwallowedException") // Homes not answering is said by the run's result; the next run asks again
    fun laufen(): Lauf.Ergebnis {
        val alle = try {
            client.korrekturen()
        } catch (e: SicherungClient.Abgelehnt) {
            return if (e.code >= 500) Lauf.Ergebnis.UNERREICHBAR else Lauf.Ergebnis.FERTIG
        } catch (e: IOException) {
            return Lauf.Ergebnis.UNERREICHBAR
        }
        for (k in alle) {
            val zeilen = buch.zeilen(k.uid, k.name)
            val (schritte, geschrieben) = if (zeilen == null) emptyList<Schritt>() to emptyList() else Korrigieren.plan(k, zeilen)
            if (schritte.isNotEmpty()) buch.schreiben(schritte)
            try {
                client.erledigt(k.id, geschrieben)
            } catch (e: IOException) {
                return Lauf.Ergebnis.UNERREICHBAR // written and not said: the next run finds each field as written, writes nothing and says so
            }
        }
        return Lauf.Ergebnis.FERTIG
    }
}
