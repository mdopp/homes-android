package cloud.dopp.homes.ausloeser

import android.nfc.FormatException
import android.nfc.NdefMessage
import java.io.IOException
import java.util.Locale

/**
 * What the phone found on a touched tag, before anything is decided (owner
 * 2026-11-28, a field case: an access card with data of its own was taken
 * for a blank sticker and offered for writing). Read by [TagLeser], decided
 * here, so the rule is the same on the Auslöser screen and in
 * [LeerTagActivity]:
 *
 * - an NDEF tag: what is on it after an actual read (Android's cached
 *   message is null on some phones although there is one); blank only with
 *   no message, no records or empty records;
 * - any other: a card with data of its own ([Karte], what can be read
 *   without keys) — never written or formatted — unless its user memory was
 *   read and is empty (a factory-new sticker); when that cannot be read, the
 *   person is asked first ([TagSchreiber.Ergebnis.Unklar]).
 *
 * Nothing of it is stored, sent or logged; the UID and the data are only on
 * the screen.
 */
data class Befund(
    val ndef: Boolean,
    /** The NDEF message after an actual read; null: none on the tag. */
    val nachricht: NdefMessage? = null,
    /** The read failed (the tag moved): neither blank nor full. */
    val unlesbar: Boolean = false,
    val formatierbar: Boolean = false,
    /** The user memory read: empty (true), with data (false), or it could not be read without keys (null). */
    val speicherLeer: Boolean? = null,
    /** A card that is no NDEF tag: what could be read of it. */
    val karte: Karte? = null,
)

/** One block, page or sector of a card; [daten] null: „gesperrt“ (no public key opens it, or it would not read). */
class Block(val name: String, val daten: ByteArray?)

/**
 * A card with data of its own as far as it reads without keys: its [typ], its [uid] (on the screen only), its
 * memory size, Android's tech list, its readable [bloecke], and an ISO-DEP card's [anwendungen] by name.
 */
class Karte(
    val typ: String,
    val uid: ByteArray?,
    val groesse: Int? = null,
    val techs: List<String> = emptyList(),
    val bloecke: List<Block> = emptyList(),
    val anwendungen: List<String> = emptyList(),
    val bank: Boolean = false,
) {
    override fun toString() = "Karte($typ)" // never the UID or the data
}

object TagBefund {

    /** What a touched tag is, for the screen and the writer. */
    fun einordnen(b: Befund, id: ByteArray?): TagSchreiber.Ergebnis {
        val k = b.karte
        return when {
            b.unlesbar -> TagSchreiber.Ergebnis.Unlesbar
            b.ndef -> b.nachricht?.takeUnless { TagSchreiber.leer(false, true, it) }
                ?.let { TagSchreiber.Ergebnis.Gelesen(it, id) } ?: TagSchreiber.Ergebnis.Leer
            k == null -> TagSchreiber.Ergebnis.Unlesbar
            b.formatierbar && b.speicherLeer == true -> TagSchreiber.Ergebnis.Leer
            b.formatierbar && b.speicherLeer == null && !k.bank && k.anwendungen.isEmpty() -> TagSchreiber.Ergebnis.Unklar(k, id)
            else -> TagSchreiber.Ergebnis.EigeneDaten(k)
        }
    }

    /** An NDEF tag's message: Android's cached one, else read from the tag; null when the read fails. */
    @Suppress("SwallowedException") // a failed read is the outcome: „nicht lesbar“
    fun nachricht(cached: NdefMessage?, lies: () -> NdefMessage?): Result<NdefMessage?> {
        cached?.let { return Result.success(it) }
        return try {
            Result.success(lies())
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: FormatException) {
            Result.failure(e)
        } catch (e: SecurityException) {
            Result.failure(e)
        }
    }

    // --- NTAG and Ultralight ------------------------------------------------------------

    /** The chip by its GET_VERSION answer (8 bytes, storage size at 6), its pages; null answer: plain Ultralight. */
    fun ultralight(version: ByteArray?): Pair<String, Int> = when (version?.takeIf { it.size >= 8 }?.get(6)?.toInt()?.and(0xFF)) {
        0x0F -> "NTAG213" to 45
        0x11 -> "NTAG215" to 135
        0x13 -> "NTAG216" to 231
        0x0B -> "MIFARE Ultralight EV1 (48 Bytes)" to 20
        0x0E -> "MIFARE Ultralight EV1 (128 Bytes)" to 41
        else -> "MIFARE Ultralight" to 16
    }

    /**
     * An Ultralight's user memory from page 4 on: empty when all zero, or only the factory's empty NDEF TLV
     * (`03 00 FE`) before zeros.
     */
    fun seitenLeer(ab4: ByteArray): Boolean {
        val rest = if (ab4.size >= 3 && ab4.copyOf(3).contentEquals(LEERES_NDEF)) ab4.copyOfRange(3, ab4.size) else ab4
        return rest.all { it == 0.toByte() }
    }

    // --- MIFARE Classic -------------------------------------------------------------------

    /** The public default keys only (the factory's, the MAD's, NFC Forum's, zeros): no lists, no guessing. */
    val SCHLUESSEL: List<ByteArray> = listOf("FFFFFFFFFFFF", "A0A1A2A3A4A5", "D3F7D3F7D3F7", "000000000000").map(::hex)

    /**
     * Blank: every sector opened with a public key and its data blocks all zero — sector 0's block 0 (the
     * maker's) and each sector's last block (its keys) aside. A sector that stays shut has keys of its own.
     */
    fun klassikLeer(sektoren: List<List<ByteArray>?>): Boolean = sektoren.isNotEmpty() && sektoren.withIndex().all { (s, bloecke) ->
        bloecke != null && bloecke.dropLast(1).withIndex().all { (i, b) -> s == 0 && i == 0 || b.all { it == 0.toByte() } }
    }

    fun klassikTyp(groesse: Int): String = when (groesse) {
        KLASSIK_MINI -> "MIFARE Classic Mini"
        KLASSIK_1K -> "MIFARE Classic 1K"
        KLASSIK_2K -> "MIFARE Classic 2K"
        KLASSIK_4K -> "MIFARE Classic 4K"
        else -> "MIFARE Classic"
    }

    // --- ISO-DEP: the applications by name, never a record ----------------------------------

    /** SELECT by name: [aid] bytes. */
    fun select(aid: ByteArray): ByteArray = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid + byteArrayOf(0x00)

    /** The contactless payment directory's name, `2PAY.SYS.DDF01`. */
    val PPSE: ByteArray = "2PAY.SYS.DDF01".toByteArray(Charsets.US_ASCII)

    fun ok(antwort: ByteArray?): Boolean =
        antwort != null && antwort.size >= 2 && antwort[antwort.size - 2] == 0x90.toByte() && antwort[antwort.size - 1] == 0.toByte()

    /** The AIDs a PPSE answer lists (FCI 6F → A5 → BF0C → 61 → 4F). Only the directory: no record is ever read. */
    fun ppse(antwort: ByteArray): List<ByteArray> {
        if (!ok(antwort)) return emptyList()
        val out = mutableListOf<ByteArray>()
        fun gehe(b: ByteArray) {
            var i = 0
            while (i < b.size) {
                val (tag, nach) = tlvTag(b, i) ?: return
                val (laenge, ab) = tlvLaenge(b, nach) ?: return
                if (ab + laenge > b.size) return
                val wert = b.copyOfRange(ab, ab + laenge)
                if (tag == TAG_AID) out += wert else if (tag in KONSTRUIERT) gehe(wert)
                i = ab + laenge
            }
        }
        gehe(antwort.copyOfRange(0, antwort.size - 2))
        return out
    }

    /** Known applications, by AID prefix: the payment schemes and the German ID card. */
    private val ANWENDUNGEN = listOf(
        "A0000003591010028001" to "girocard", "D27600002545500100" to "girocard", "A0000000043060" to "Maestro",
        "A0000000041010" to "Mastercard", "A0000000031010" to "Visa", "A0000000032010" to "Visa Electron",
        "A0000000033010" to "V PAY", "A00000002501" to "American Express", "A0000001523010" to "Discover",
        "A0000000651010" to "JCB", "E80704007F00070302" to "Personalausweis (eID)",
    ).map { (aid, name) -> hex(aid) to name }

    /** The AIDs tried one by one when a card has no payment directory. */
    val BEKANNTE: List<ByteArray> = ANWENDUNGEN.map { it.first }

    /** An application's name, or its AID in hex. */
    fun anwendung(aid: ByteArray): String = ANWENDUNGEN.firstOrNull { (p, _) -> aid.size >= p.size && aid.copyOf(p.size).contentEquals(p) }?.second
        ?: hexText(aid, "")

    /** A payment application (an EMV scheme's RID): the card number is never read. */
    fun zahlung(aid: ByteArray): Boolean = aid.size >= 5 && (aid[0] == 0xA0.toByte() || hexText(aid.copyOf(6), "") == "D27600002545") &&
        anwendung(aid) != "Personalausweis (eID)"

    // --- NFC-V (ISO 15693) -----------------------------------------------------------------

    /** GET SYSTEM INFO's answer: the blocks and their size, when it says them. */
    fun nfcvGroesse(info: ByteArray?): Pair<Int, Int>? {
        if (info == null || info.size < 10 || info[0] != 0.toByte()) return null
        val flags = info[1].toInt()
        val i = 10 + (if ((flags and 0x01) != 0) 1 else 0) + (if ((flags and 0x02) != 0) 1 else 0)
        if ((flags and 0x04) == 0 || i + 1 >= info.size) return null
        return ((info[i].toInt() and 0xFF) + 1) to ((info[i + 1].toInt() and 0x1F) + 1)
    }

    /** READ SINGLE BLOCK's answer: the block, or null when the card refused. */
    fun nfcvBlock(antwort: ByteArray?): ByteArray? = antwort?.takeIf { it.size > 1 && it[0] == 0.toByte() }?.copyOfRange(1, antwort.size)

    // --- on the screen ---------------------------------------------------------------------

    /** The card as text, for a monospace view: type, UID, size, techs, applications, then each block as hex and ASCII. */
    fun text(k: Karte): String = buildString {
        appendLine("Typ: ${k.typ}")
        k.uid?.let { appendLine("UID: ${hexText(it, ":")}") }
        k.groesse?.let { appendLine("Speicher: $it Bytes") }
        if (k.techs.isNotEmpty()) appendLine("Techniken: ${k.techs.joinToString(", ")}")
        if (k.bank) appendLine("Bankkarte: ${k.anwendungen.joinToString(", ")}. Die Kartennummer liest Homes nicht.")
        else if (k.anwendungen.isNotEmpty()) appendLine("Anwendungen: ${k.anwendungen.joinToString(", ")}")
        k.bloecke.forEach { b -> appendLine(b.daten?.let { "${b.name.padEnd(NAME)} ${zeile(it)}" } ?: "${b.name.padEnd(NAME)} gesperrt") }
    }.trimEnd()

    /** The readable data as hex only, for „Kopieren“. */
    fun hexAlles(k: Karte): String = k.bloecke.mapNotNull { b -> b.daten?.let { "${b.name}: ${hexText(it, " ")}" } }.joinToString("\n")

    /** Hex and printable ASCII side by side. */
    fun zeile(b: ByteArray): String = hexText(b, " ") + "  " + String(CharArray(b.size) { i -> b[i].toInt().let { c -> if (c in DRUCKBAR) c.toChar() else '.' } })

    fun hexText(b: ByteArray, trenner: String): String = b.joinToString(trenner) { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) }

    fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun tlvTag(b: ByteArray, i: Int): Pair<Int, Int>? {
        if (i >= b.size) return null
        val erst = b[i].toInt() and 0xFF
        if ((erst and 0x1F) != 0x1F) return erst to i + 1
        if (i + 1 >= b.size) return null
        return (erst shl 8 or (b[i + 1].toInt() and 0xFF)) to i + 2
    }

    private fun tlvLaenge(b: ByteArray, i: Int): Pair<Int, Int>? {
        if (i >= b.size) return null
        val l = b[i].toInt() and 0xFF
        return when {
            l < 0x80 -> l to i + 1
            l == 0x81 && i + 1 < b.size -> (b[i + 1].toInt() and 0xFF) to i + 2
            l == 0x82 && i + 2 < b.size -> ((b[i + 1].toInt() and 0xFF) shl 8 or (b[i + 2].toInt() and 0xFF)) to i + 3
            else -> null
        }
    }

    /** The factory's empty NDEF TLV and its terminator: `03 00 FE`. */
    private val LEERES_NDEF = byteArrayOf(0x03, 0x00, 0xFE.toByte())
    private const val TAG_AID = 0x4F
    private val KONSTRUIERT = setOf(0x6F, 0xA5, 0xBF0C, 0x61)
    private val DRUCKBAR = 0x20..0x7E
    private const val NAME = 12
    private const val KLASSIK_MINI = 320
    private const val KLASSIK_1K = 1024
    private const val KLASSIK_2K = 2048
    private const val KLASSIK_4K = 4096
}
