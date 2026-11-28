package cloud.dopp.homes.ausloeser

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import java.io.ByteArrayOutputStream

/**
 * A WLAN tag (the owner, 2026-11-27: „Ja WLAN schreiben“): one NDEF record
 * of MIME type `application/vnd.wfa.wsc`, a Wi-Fi Simple Configuration
 * credential — what Android's own NFC service reads to offer joining a
 * network, before any app is asked. Built here byte by byte; nothing of it
 * goes to Homes, is stored or is logged: the password stays on the phone
 * and the tag.
 *
 * The credential (WSC 2.0 attributes, each type, 2-byte length, value):
 * Credential 0x100E around Network Index 0x1026 (1), SSID 0x1045,
 * Authentication Type 0x1003, Encryption Type 0x100F, Network Key 0x1027
 * (without a password: none), MAC Address 0x1020 (FF:FF:FF:FF:FF:FF, any).
 * WSC knows no WPA3: a network with a password is written as WPA2-Personal
 * with AES, which a FRITZ!Box's „WPA2 + WPA3“ takes; WSC has no field for a
 * hidden network either, so the form offers none.
 */
object WlanTag {

    const val MIME = "application/vnd.wfa.wsc"

    private const val CREDENTIAL = 0x100E
    private const val NETWORK_INDEX = 0x1026
    private const val SSID = 0x1045
    private const val AUTH_TYPE = 0x1003
    private const val ENCRYPTION_TYPE = 0x100F
    private const val NETWORK_KEY = 0x1027
    private const val MAC_ADDRESS = 0x1020

    /** How the network is protected, as WSC says it: authentication and encryption type. */
    enum class Schutz(val authentifizierung: Int, val verschluesselung: Int) {
        /** WPA2-Personal, AES — also a WPA2/WPA3 network's. */
        WPA2(0x0020, 0x0008),

        /** Open: no password. */
        OFFEN(0x0001, 0x0001),
    }

    /** The longest SSID WLAN allows, in bytes. */
    private const val SSID_MAX = 32

    /** A WPA passphrase: 8 to 63 printable ASCII characters, or 64 hex digits. */
    private val PASSPHRASE = Regex("""[\x20-\x7E]{8,63}""")
    private val HEX_KEY = Regex("""[0-9A-Fa-f]{64}""")

    /** What is wrong with the form, as one German sentence; null when it can be written. */
    fun pruefen(ssid: String, passwort: String, schutz: Schutz): String? = when {
        ssid.isEmpty() -> "Gib den Namen des WLANs ein, so wie die FRITZ!Box ihn zeigt."
        ssid.toByteArray(Charsets.UTF_8).size > SSID_MAX -> "Der WLAN-Name ist zu lang: höchstens 32 Zeichen."
        schutz == Schutz.WPA2 && !(PASSPHRASE.matches(passwort) || HEX_KEY.matches(passwort)) ->
            "Das WLAN-Passwort hat 8 bis 63 Zeichen, ohne Umlaute. Bitte prüfen."
        else -> null
    }

    /** The credential's bytes, the record's payload. */
    fun nutzlast(ssid: String, passwort: String, schutz: Schutz): ByteArray {
        val innen = ByteArrayOutputStream().apply {
            feld(NETWORK_INDEX, byteArrayOf(1))
            feld(SSID, ssid.toByteArray(Charsets.UTF_8))
            feld(AUTH_TYPE, zwei(schutz.authentifizierung))
            feld(ENCRYPTION_TYPE, zwei(schutz.verschluesselung))
            if (schutz != Schutz.OFFEN) feld(NETWORK_KEY, passwort.toByteArray(Charsets.US_ASCII))
            feld(MAC_ADDRESS, ByteArray(6) { 0xFF.toByte() })
        }.toByteArray()
        return ByteArrayOutputStream().apply { feld(CREDENTIAL, innen) }.toByteArray()
    }

    /**
     * A network read from a WLAN tag: its [ssid], WSC's authentication bits [auth] and the [passwort] (empty
     * when open). Its text never carries the password, so it cannot end up in a log by accident.
     */
    class Netz(val ssid: String, val auth: Int, val passwort: String) {
        /** How the form writes it again: open, or WPA2 for any network with a password. */
        val schutz: Schutz get() = if (auth == Schutz.OFFEN.authentifizierung) Schutz.OFFEN else Schutz.WPA2

        /** Protected by a password alone (WPA or WPA2 Personal), as Android takes one. */
        val psk: Boolean get() = (auth and (Schutz.WPA2.authentifizierung or WPA_PERSONAL)) != 0

        /** How it is protected, in plain words. */
        val art: String get() = when {
            auth == Schutz.OFFEN.authentifizierung -> "offen, ohne Passwort"
            (auth and (WPA2_ENTERPRISE or WPA_ENTERPRISE)) != 0 -> "WPA-Enterprise (mit Anmeldung)"
            (auth and Schutz.WPA2.authentifizierung) != 0 -> "WPA2"
            (auth and WPA_PERSONAL) != 0 -> "WPA"
            (auth and SHARED) != 0 -> "WEP (veraltet)"
            else -> "unbekannt"
        }

        override fun toString() = "Netz($ssid, $art)"
        override fun equals(other: Any?) = other is Netz && other.ssid == ssid && other.auth == auth && other.passwort == passwort
        override fun hashCode() = ssid.hashCode() * 31 + auth
    }

    /** The network in a WLAN record's [payload]: the first credential's SSID, authentication and key; null when none. */
    fun lesen(payload: ByteArray): Netz? {
        val cred = felder(payload)[CREDENTIAL] ?: return null
        val innen = felder(cred)
        val ssid = innen[SSID]?.toString(Charsets.UTF_8) ?: return null
        val auth = innen[AUTH_TYPE]?.takeIf { it.size == 2 }?.let { (it[0].toInt() and 0xFF shl 8) or (it[1].toInt() and 0xFF) } ?: 0
        return Netz(ssid, auth, innen[NETWORK_KEY]?.toString(Charsets.UTF_8).orEmpty())
    }

    /** WSC's attributes (type, 2-byte length, value), the first of each type; a cut-off one ends the list. */
    private fun felder(b: ByteArray): Map<Int, ByteArray> {
        val out = mutableMapOf<Int, ByteArray>()
        var i = 0
        while (i + 4 <= b.size) {
            val typ = (b[i].toInt() and 0xFF shl 8) or (b[i + 1].toInt() and 0xFF)
            val laenge = (b[i + 2].toInt() and 0xFF shl 8) or (b[i + 3].toInt() and 0xFF)
            if (i + 4 + laenge > b.size) break
            if (typ !in out) out[typ] = b.copyOfRange(i + 4, i + 4 + laenge)
            i += 4 + laenge
        }
        return out
    }

    /** WSC's other authentication types, read only. */
    private const val WPA_PERSONAL = 0x0002
    private const val SHARED = 0x0004
    private const val WPA_ENTERPRISE = 0x0008
    private const val WPA2_ENTERPRISE = 0x0010

    /** The tag's one record. */
    fun nachricht(ssid: String, passwort: String, schutz: Schutz): NdefMessage =
        NdefMessage(arrayOf(NdefRecord.createMime(MIME, nutzlast(ssid, passwort, schutz))))

    private fun zwei(wert: Int) = byteArrayOf((wert shr 8).toByte(), wert.toByte())

    private fun ByteArrayOutputStream.feld(typ: Int, wert: ByteArray) {
        write(zwei(typ))
        write(zwei(wert.size))
        write(wert)
    }
}
