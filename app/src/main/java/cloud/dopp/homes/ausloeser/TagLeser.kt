package cloud.dopp.homes.ausloeser

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import java.io.IOException

/**
 * Reads a touched tag for [TagBefund] (owner 2026-11-28: „Mach das. Aber
 * zeige mir die Daten.“): an NDEF tag's message, really read; any other card
 * as far as it reads without keys — an Ultralight's or NTAG's pages, a MIFARE
 * Classic's sectors with the public default keys only (others „gesperrt“), an
 * ISO-DEP card's applications by name (the payment directory and SELECT of
 * known AIDs only: never a record, so never the card number), an NFC-V
 * card's blocks. Nothing is written, stored or logged here.
 */
internal object TagLeser {

    /** Blocks read at most from an NFC-V card. */
    private const val NFCV_HOECHSTENS = 64
    private const val ULTRALIGHT_SEITE = 4

    @Suppress("SwallowedException") // a card gone mid-read is the outcome: „nicht lesbar“
    fun befund(tag: Tag): Befund {
        val formatierbar = NdefFormatable.get(tag) != null
        Ndef.get(tag)?.let { ndef ->
            val n = TagBefund.nachricht(ndef.cachedNdefMessage) { ndef.use { it.connect(); it.ndefMessage } }
            return Befund(ndef = true, nachricht = n.getOrNull(), unlesbar = n.isFailure, formatierbar = formatierbar)
        }
        return try {
            karte(tag, formatierbar)
        } catch (e: IOException) {
            Befund(ndef = false, unlesbar = true, formatierbar = formatierbar)
        } catch (e: SecurityException) {
            Befund(ndef = false, unlesbar = true, formatierbar = formatierbar)
        }
    }

    private fun karte(tag: Tag, formatierbar: Boolean): Befund {
        val techs = tag.techList.map { it.substringAfterLast('.') }
        fun aus(k: Karte, leer: Boolean?) = Befund(ndef = false, formatierbar = formatierbar, speicherLeer = leer, karte = k)
        MifareUltralight.get(tag)?.let { u -> return ultralight(u, tag, techs).let { (k, leer) -> aus(k, leer) } }
        MifareClassic.get(tag)?.let { c -> return klassik(c, tag, techs).let { (k, leer) -> aus(k, leer) } }
        IsoDep.get(tag)?.let { i -> return aus(isoDep(i, tag, techs), false) }
        NfcV.get(tag)?.let { v -> return nfcV(v, tag, techs).let { (k, leer) -> aus(k, leer) } }
        NfcF.get(tag)?.let { return aus(Karte("FeliCa", tag.id, techs = techs), false) }
        val typ = if ("NfcB" in techs) "NFC-B" else "NFC-A"
        return aus(Karte(typ, tag.id, techs = techs), null)
    }

    private fun ultralight(u: MifareUltralight, tag: Tag, techs: List<String>): Pair<Karte, Boolean?> = u.use {
        it.connect()
        val version = runCatching { it.transceive(byteArrayOf(0x60)) }.getOrNull()
        if (version == null) { // a plain Ultralight halts on it: select it again
            it.close()
            it.connect()
        }
        val (typ, seiten) = TagBefund.ultralight(version)
        val bloecke = mutableListOf<Block>()
        var daten = ByteArray(0)
        for (p in 0 until seiten step ULTRALIGHT_SEITE) {
            val vier = runCatching { it.readPages(p) }.getOrNull() ?: break // a protected page ends what reads
            val n = minOf(ULTRALIGHT_SEITE, seiten - p)
            for (i in 0 until n) bloecke += Block("Seite ${p + i}", vier.copyOfRange(i * 4, i * 4 + 4))
            daten += vier.copyOf(n * 4)
        }
        val leer = if (daten.size >= seiten * 4) TagBefund.seitenLeer(daten.copyOfRange(16, daten.size)) else null
        Karte(typ, tag.id, seiten * 4, techs, bloecke) to leer
    }

    private fun klassik(c: MifareClassic, tag: Tag, techs: List<String>): Pair<Karte, Boolean?> = c.use {
        it.connect()
        val sektoren = (0 until it.sectorCount).map { s -> sektor(it, s) }
        val bloecke = sektoren.withIndex().flatMap { (s, b) ->
            b?.withIndex()?.map { (i, d) -> Block("S$s B$i", d) } ?: listOf(Block("Sektor $s", null))
        }
        Karte(TagBefund.klassikTyp(it.size), tag.id, it.size, techs, bloecke) to TagBefund.klassikLeer(sektoren)
    }

    /** A sector's blocks when one of the public keys opens it (as key A), else null; the card selected again after a refusal. */
    private fun sektor(c: MifareClassic, s: Int): List<ByteArray>? {
        for (k in TagBefund.SCHLUESSEL) {
            if (runCatching { c.authenticateSectorWithKeyA(s, k) }.getOrDefault(false)) {
                return runCatching { (0 until c.getBlockCountInSector(s)).map { c.readBlock(c.sectorToBlock(s) + it) } }.getOrNull()
            }
            c.close()
            c.connect()
        }
        return null
    }

    private fun isoDep(i: IsoDep, tag: Tag, techs: List<String>): Karte = i.use {
        it.connect()
        val ppse = runCatching { it.transceive(TagBefund.select(TagBefund.PPSE)) }.getOrNull()
        val aids = ppse?.let(TagBefund::ppse).orEmpty().ifEmpty {
            TagBefund.BEKANNTE.filter { aid -> TagBefund.ok(runCatching { it.transceive(TagBefund.select(aid)) }.getOrNull()) }
        }
        Karte("ISO-DEP", tag.id, techs = techs, anwendungen = aids.map(TagBefund::anwendung).distinct(), bank = aids.any(TagBefund::zahlung))
    }

    private fun nfcV(v: NfcV, tag: Tag, techs: List<String>): Pair<Karte, Boolean?> = v.use {
        it.connect()
        val groesse = TagBefund.nfcvGroesse(runCatching { it.transceive(byteArrayOf(0x02, 0x2B)) }.getOrNull())
        val bloecke = mutableListOf<Block>()
        for (n in 0 until minOf(groesse?.first ?: NFCV_HOECHSTENS, NFCV_HOECHSTENS)) {
            val b = TagBefund.nfcvBlock(runCatching { it.transceive(byteArrayOf(0x02, 0x20, n.toByte())) }.getOrNull()) ?: break
            bloecke += Block("Block $n", b)
        }
        val leer = bloecke.takeIf { b -> groesse != null && b.size == groesse.first }?.all { b -> b.daten!!.all { x -> x == 0.toByte() } }
        Karte("NFC-V (ISO 15693)", tag.id, groesse?.let { (n, s) -> n * s }, techs, bloecke) to leer
    }
}
