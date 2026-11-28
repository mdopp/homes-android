package cloud.dopp.homes.ausloeser

import android.nfc.NdefMessage
import android.nfc.NdefRecord

/**
 * What is on a written tag held to the Auslöser screen (owner 2026-11-27:
 * „Bau alles“), read before anything is offered: one of Homes' tags (its id,
 * from Homes' address, the scheme form or HA's), a WLAN tag ([WlanTag]'s
 * record), or something else in plain words. Nothing of it leaves the phone;
 * only a Homes tag's id is looked up in the list the screen already has.
 */
sealed class TagInhalt {

    /** One of Homes' (or HA's) tags: [id] as HA knows it. */
    data class Homes(val id: String) : TagInhalt()

    /** A WLAN tag: the network on it. */
    data class Wlan(val netz: WlanTag.Netz) : TagInhalt()

    /** Anything else: one line per record, what kind and what it says. */
    data class Fremd(val zeilen: List<String>) : TagInhalt()

    companion object {
        /** A record's text is cut here: the screen says what it is, not all of it. */
        private const val HOECHSTENS = 120

        private const val VCARD = "text/vcard"
        private const val VCARD_ALT = "text/x-vcard"
        private const val APP = "android.com:pkg"
        private const val KARTE = "Eine Visitenkarte"

        /** Read [n] as the first record that is not empty decides; [homes] the paired server's host. */
        fun lesen(n: NdefMessage, homes: String?): TagInhalt {
            val records = n.records.filter { it.tnf != NdefRecord.TNF_EMPTY }
            val erstes = records.firstOrNull() ?: return Fremd(listOf("Nur leere Einträge"))
            Ausloeser.tagAus(uri(erstes), homes)?.let { return Homes(it) }
            if (erstes.tnf == NdefRecord.TNF_MIME_MEDIA && mime(erstes) == WlanTag.MIME) {
                WlanTag.lesen(erstes.payload)?.let { return Wlan(it) }
            }
            return Fremd(records.map(::zeile))
        }

        /** One record in plain words: „Eine Internetadresse: https://…“. */
        private fun zeile(r: NdefRecord): String = when {
            r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT) -> "Ein Text: „${kurz(text(r.payload))}“"
            r.tnf == NdefRecord.TNF_MIME_MEDIA -> medien(mime(r), r.payload)
            r.tnf == NdefRecord.TNF_EXTERNAL_TYPE -> app(String(r.type, Charsets.US_ASCII), r.payload)
            else -> uri(r)?.let(::adresse) ?: unbekannt(r)
        }

        /** An address record's (URI, smart poster, absolute URI); null for any other (an external type's is no address). */
        private fun uri(r: NdefRecord): String? =
            if (r.tnf == NdefRecord.TNF_WELL_KNOWN || r.tnf == NdefRecord.TNF_ABSOLUTE_URI) r.toUri()?.toString() else null

        private fun app(typ: String, payload: ByteArray): String =
            if (typ == APP) "Öffnet die App ${kurz(String(payload, Charsets.UTF_8))}" else "Daten einer App ($typ, ${payload.size} Bytes)"

        /** Addresses that are something else, said as what they are; the rest is a web or other address. */
        private val ARTEN = mapOf(
            "tel" to "Eine Telefonnummer", "mailto" to "Eine E-Mail-Adresse", "sms" to "Eine SMS an", "smsto" to "Eine SMS an",
            "geo" to "Ein Ort auf der Karte",
        )

        private fun adresse(uri: String): String {
            val schema = uri.substringBefore(':').lowercase()
            ARTEN[schema]?.let { return "$it: ${kurz(uri.substringAfter(':'))}" }
            return (if (schema == "http" || schema == "https") "Eine Internetadresse: " else "Eine Adresse: ") + kurz(uri)
        }

        private fun medien(typ: String, payload: ByteArray): String = when {
            typ == VCARD || typ == VCARD_ALT -> name(String(payload, Charsets.UTF_8))?.let { "$KARTE: ${kurz(it)}" } ?: KARTE
            typ.startsWith("text/") -> "Ein Text ($typ): „${kurz(String(payload, Charsets.UTF_8))}“"
            else -> "Daten der Art $typ (${payload.size} Bytes)"
        }

        private fun unbekannt(r: NdefRecord) = "Unbekannte Daten (${r.payload.size} Bytes)"

        private fun mime(r: NdefRecord) = String(r.type, Charsets.US_ASCII).lowercase().substringBefore(';').trim()

        /** A vCard's name: its FN line. */
        private fun name(vcard: String): String? = vcard.lineSequence()
            .firstOrNull { it.startsWith("FN:", true) || it.startsWith("FN;", true) }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

        /** An NDEF text record's text: status byte (bit 7 UTF-16, the language's length), the language, the text. */
        private fun text(p: ByteArray): String {
            if (p.isEmpty()) return ""
            val sprache = p[0].toInt() and 0x3F
            val zeichen = if ((p[0].toInt() and 0x80) != 0) Charsets.UTF_16 else Charsets.UTF_8
            return if (1 + sprache > p.size) "" else String(p, 1 + sprache, p.size - 1 - sprache, zeichen)
        }

        private fun kurz(t: String): String {
            val eine = t.trim().replace(Regex("\\s+"), " ")
            return if (eine.length > HOECHSTENS) eine.take(HOECHSTENS) + "…" else eine
        }
    }
}
