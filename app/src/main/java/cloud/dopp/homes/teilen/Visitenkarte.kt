package cloud.dopp.homes.teilen

import android.graphics.Bitmap
import android.graphics.Color
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.provider.ContactsContract.CommonDataKinds
import cloud.dopp.homes.ausloeser.WlanTag
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * „Meine Kontaktdaten“ (owner 2026-11-28): the person's own contact as a
 * vCard 3.0 with the fields they ticked, shared as one NDEF MIME record
 * `text/vcard`; and for an iPhone, which reads no WLAN record, the same as a
 * QR code — the contact as vCard, a WLAN as `WIFI:`.
 */
object Visitenkarte {

    enum class Feld { NAME, MOBIL, TELEFON, MAIL, ADRESSE, GEBURTSTAG, FIRMA }

    /** What a contact has, each field one value or none. */
    data class Kontakt(
        val name: String?,
        val telefon: String?,
        val mail: String?,
        val adresse: String?,
        val firma: String?,
        val mobil: String? = null,
        val geburtstag: String? = null,
    ) {
        /** Each field's value, by [Feld]. */
        fun wert(f: Feld): String? = when (f) {
            Feld.NAME -> name
            Feld.MOBIL -> mobil
            Feld.TELEFON -> telefon
            Feld.MAIL -> mail
            Feld.ADRESSE -> adresse
            Feld.GEBURTSTAG -> geburtstag
            Feld.FIRMA -> firma
        }
    }

    /** One data row of a contact: its mimetype, DATA1, and DATA2 (a phone's or an event's type). */
    data class Zeile(val mime: String, val wert: String, val art: Int = 0)

    const val MIME = "text/vcard"

    /** A contact from its data rows, the first of each kind: a mobile number apart from the others, the birthday. */
    fun kontakt(zeilen: List<Zeile>): Kontakt {
        fun erstes(mime: String, art: (Int) -> Boolean = { true }) = zeilen.firstOrNull { it.mime == mime && it.wert.isNotBlank() && art(it.art) }?.wert
        val tel = CommonDataKinds.Phone.CONTENT_ITEM_TYPE
        return Kontakt(
            erstes(CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE), erstes(tel) { it != CommonDataKinds.Phone.TYPE_MOBILE },
            erstes(CommonDataKinds.Email.CONTENT_ITEM_TYPE), erstes(CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE),
            erstes(CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
            mobil = erstes(tel) { it == CommonDataKinds.Phone.TYPE_MOBILE },
            geburtstag = erstes(CommonDataKinds.Event.CONTENT_ITEM_TYPE) { it == CommonDataKinds.Event.TYPE_BIRTHDAY },
        )
    }

    /** The vCard of [k] with only the fields in [felder]; its FN is the name, or the first field there is. */
    fun vcard(k: Kontakt, felder: Set<Feld>): String {
        fun hat(f: Feld) = k.wert(f)?.trim()?.takeIf { f in felder && it.isNotEmpty() }
        val name = hat(Feld.NAME)
        val zeilen = mutableListOf("BEGIN:VCARD", "VERSION:3.0")
        zeilen += "FN:" + esc(name ?: hat(Feld.FIRMA).orEmpty())
        name?.let { zeilen += "N:" + esc(it.substringAfterLast(' ')) + ";" + esc(it.substringBeforeLast(' ', "")) + ";;;" }
        hat(Feld.MOBIL)?.let { zeilen += "TEL;TYPE=CELL:" + esc(it) }
        hat(Feld.TELEFON)?.let { zeilen += "TEL;TYPE=VOICE:" + esc(it) }
        hat(Feld.MAIL)?.let { zeilen += "EMAIL;TYPE=INTERNET:" + esc(it) }
        hat(Feld.ADRESSE)?.let { zeilen += "ADR;TYPE=HOME:;;" + esc(it.replace('\n', ' ')) + ";;;;" }
        hat(Feld.GEBURTSTAG)?.let { zeilen += "BDAY:$it" }
        hat(Feld.FIRMA)?.let { zeilen += "ORG:" + esc(it) }
        zeilen += "END:VCARD"
        return zeilen.joinToString("\r\n") + "\r\n"
    }

    /**
     * What a card written by [vcard] holds, for „Kontakt austauschen“: the first name apart ([Feld.NAME] is the
     * whole name), each field unescaped; an older card's one TEL;TYPE=CELL is the mobile number.
     */
    fun lesen(vcard: String): Pair<String?, Map<Feld, String>> {
        val out = mutableMapOf<Feld, String>()
        var vorname: String? = null
        vcard.split("\r\n", "\n").forEach { z ->
            val kopf = z.substringBefore(':')
            val wert = z.substringAfter(':', "")
            when {
                kopf == "N" -> vorname = unesc(wert.split(';').getOrNull(1).orEmpty()).ifBlank { null }
                kopf == "FN" -> unesc(wert).ifBlank { null }?.let { out[Feld.NAME] = it }
                kopf == "TEL;TYPE=CELL" -> out[Feld.MOBIL] = unesc(wert)
                kopf.startsWith("TEL") -> out[Feld.TELEFON] = unesc(wert)
                kopf.startsWith("EMAIL") -> out[Feld.MAIL] = unesc(wert)
                kopf.startsWith("ADR") -> out[Feld.ADRESSE] = unesc(wert.split(';').getOrNull(2).orEmpty())
                kopf == "BDAY" -> out[Feld.GEBURTSTAG] = wert
                kopf == "ORG" -> out[Feld.FIRMA] = unesc(wert)
            }
        }
        if (vorname == null && !vcard.contains("\nN:")) out.remove(Feld.NAME) // FN is the firm's: no name
        return (vorname ?: out[Feld.NAME]?.substringBefore(' ')) to out.filterValues { it.isNotBlank() }
    }

    /** The card as the one record a reader gets. */
    fun ndef(vcard: String): NdefMessage = NdefMessage(arrayOf(NdefRecord.createMime(MIME, vcard.toByteArray(Charsets.UTF_8))))

    /** A WLAN as the camera app of an iPhone reads it. */
    fun wifi(n: WlanTag.Netz): String =
        if (n.schutz == WlanTag.Schutz.OFFEN) "WIFI:T:nopass;S:${qr(n.ssid)};;" else "WIFI:T:WPA;S:${qr(n.ssid)};P:${qr(n.passwort)};;"

    /** A kept tag's QR code: its WLAN as `WIFI:`, a card as its vCard, a link as itself; null when it has none. */
    fun qrText(n: NdefMessage): String? {
        val r = n.records.firstOrNull() ?: return null
        return when (r.toMimeType()) {
            WlanTag.MIME -> WlanTag.lesen(r.payload)?.let(::wifi)
            MIME -> String(r.payload, Charsets.UTF_8)
            else -> r.toUri()?.toString()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }
    }

    /** [text] as a QR code, black on white, [px] square. */
    fun qrBild(text: String, px: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 1))
        val pixel = IntArray(px * px) { i -> if (m.get(i % px, i / px)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixel, px, px, Bitmap.Config.ARGB_8888)
    }

    private fun unesc(s: String) = s.replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    private fun esc(s: String) = s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")

    private fun qr(s: String) = s.replace(Regex("""([\\;,:"])"""), """\\$1""")
}
