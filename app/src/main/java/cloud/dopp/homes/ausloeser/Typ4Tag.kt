package cloud.dopp.homes.ausloeser

/**
 * An NFC Forum Type 4 Tag (T4T 2.0), read only, as the phone answers a
 * reader for „WLAN anbieten“: the NDEF application ([AID]), its Capability
 * Container (file E103) and one NDEF file (E104, its length and the
 * message). Every command is answered from [ndef] at that moment: while
 * nothing is offered, each one is 6A82 (not found), as if no tag were there.
 * One instance per reader session ([zuruecksetzen] between them).
 */
class Typ4Tag(private val ndef: () -> ByteArray?) {

    private var gewaehlt = false
    private var datei: ByteArray? = null

    /** The reader went: the next one starts from SELECT. */
    fun zuruecksetzen() {
        gewaehlt = false
        datei = null
    }

    /** The answer to one command APDU. */
    fun antwort(apdu: ByteArray): ByteArray {
        val n = ndef() ?: return NICHT_DA.also { zuruecksetzen() }
        if (apdu.size < KOPF) return FALSCHE_LAENGE
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF
        return when (apdu[1]) {
            SELECT -> waehle(apdu, p1, n)
            READ_BINARY -> lies(apdu, (p1 shl 8) or p2)
            UPDATE_BINARY -> NUR_LESEN
            else -> UNBEKANNT
        }
    }

    private fun waehle(apdu: ByteArray, p1: Int, n: ByteArray): ByteArray {
        val lc = apdu.getOrNull(KOPF)?.toInt()?.and(0xFF) ?: return FALSCHE_LAENGE
        if (apdu.size < KOPF + 1 + lc) return FALSCHE_LAENGE
        val wert = apdu.copyOfRange(KOPF + 1, KOPF + 1 + lc)
        return when {
            p1 == NACH_NAME && wert.contentEquals(AID) -> OK.also {
                gewaehlt = true
                datei = null
            }
            p1 == NACH_ID && gewaehlt -> datei(wert, n)?.let { d -> OK.also { datei = d } } ?: NICHT_DA
            else -> NICHT_DA
        }
    }

    /** The file [id] names: the CC, or the NDEF file (its length, then the message); null for any other. */
    private fun datei(id: ByteArray, n: ByteArray): ByteArray? = when {
        id.contentEquals(CC) -> cc(n.size)
        id.contentEquals(NDEF) -> zwei(n.size) + n
        else -> null
    }

    private fun lies(apdu: ByteArray, ab: Int): ByteArray {
        val d = datei ?: return NICHT_ERLAUBT
        if (ab > d.size) return FALSCHER_BEREICH
        val le = apdu.getOrNull(KOPF)?.toInt()?.and(0xFF)?.takeIf { it != 0 } ?: MAX_LE
        return d.copyOfRange(ab, minOf(d.size, ab + le)) + OK
    }

    companion object {
        /** The NDEF Tag Application's name (T4T 2.0). */
        val AID = bytes(0xD2, 0x76, 0x00, 0x00, 0x85, 0x01, 0x01)
        val CC = bytes(0xE1, 0x03)
        val NDEF = bytes(0xE1, 0x04)

        val OK = bytes(0x90, 0x00)
        val NICHT_DA = bytes(0x6A, 0x82)
        private val FALSCHE_LAENGE = bytes(0x67, 0x00)
        private val NUR_LESEN = bytes(0x69, 0x82)
        private val NICHT_ERLAUBT = bytes(0x69, 0x86)
        private val FALSCHER_BEREICH = bytes(0x6B, 0x00)
        private val UNBEKANNT = bytes(0x6D, 0x00)

        private const val SELECT: Byte = 0xA4.toByte()
        private const val READ_BINARY: Byte = 0xB0.toByte()
        private const val UPDATE_BINARY: Byte = 0xD6.toByte()
        private const val NACH_NAME = 0x04
        private const val NACH_ID = 0x00
        private const val KOPF = 4
        private const val MAX_LE = 256

        /** The most a reader may ask in one READ BINARY (MLe), and send in one UPDATE (MLc). */
        private const val MLE = 0x003B
        private const val MLC = 0x0034

        /**
         * The Capability Container: its length 15, mapping version 2.0, MLe, MLc, and the NDEF file's
         * control TLV — file E104, its size, read access free (00), write access never (FF).
         */
        fun cc(ndefGroesse: Int): ByteArray =
            zwei(0x000F) + bytes(0x20) + zwei(MLE) + zwei(MLC) + bytes(0x04, 0x06) + NDEF + zwei(ndefGroesse + 2) + bytes(0x00, 0xFF)

        private fun zwei(w: Int) = bytes(w shr 8 and 0xFF, w and 0xFF)

        private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    }
}
