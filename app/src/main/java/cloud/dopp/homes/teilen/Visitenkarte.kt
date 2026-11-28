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

    enum class Feld { NAME, TELEFON, MAIL, ADRESSE, FIRMA }

    /** What a contact has, each field one value or none. */
    data class Kontakt(val name: String?, val telefon: String?, val mail: String?, val adresse: String?, val firma: String?)

    const val MIME = "text/vcard"

    /** A contact from its data rows (mimetype, DATA1), the first of each kind. */
    fun kontakt(zeilen: List<Pair<String, String>>): Kontakt {
        fun erstes(mime: String) = zeilen.firstOrNull { it.first == mime && it.second.isNotBlank() }?.second
        return Kontakt(
            erstes(CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE), erstes(CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
            erstes(CommonDataKinds.Email.CONTENT_ITEM_TYPE), erstes(CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE),
            erstes(CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
        )
    }

    /** The vCard of [k] with only the fields in [felder]; its FN is the name, or the first field there is. */
    fun vcard(k: Kontakt, felder: Set<Feld>): String {
        fun hat(f: Feld, w: String?) = w?.trim()?.takeIf { f in felder && it.isNotEmpty() }
        val name = hat(Feld.NAME, k.name)
        val zeilen = mutableListOf("BEGIN:VCARD", "VERSION:3.0")
        zeilen += "FN:" + esc(name ?: hat(Feld.FIRMA, k.firma).orEmpty())
        name?.let { zeilen += "N:" + esc(it.substringAfterLast(' ')) + ";" + esc(it.substringBeforeLast(' ', "")) + ";;;" }
        hat(Feld.TELEFON, k.telefon)?.let { zeilen += "TEL;TYPE=CELL:" + esc(it) }
        hat(Feld.MAIL, k.mail)?.let { zeilen += "EMAIL;TYPE=INTERNET:" + esc(it) }
        hat(Feld.ADRESSE, k.adresse)?.let { zeilen += "ADR;TYPE=HOME:;;" + esc(it.replace('\n', ' ')) + ";;;;" }
        hat(Feld.FIRMA, k.firma)?.let { zeilen += "ORG:" + esc(it) }
        zeilen += "END:VCARD"
        return zeilen.joinToString("\r\n") + "\r\n"
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

    private fun esc(s: String) = s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")

    private fun qr(s: String) = s.replace(Regex("""([\\;,:"])"""), """\\$1""")
}
