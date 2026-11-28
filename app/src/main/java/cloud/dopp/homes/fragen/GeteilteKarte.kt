package cloud.dopp.homes.fragen

import cloud.dopp.homes.konto.VcardLeser
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * The card a contacts app shared (Daten erfragen, owner 2026-11-28): Samsung's
 * „Senden“ → „vCard-Datei (VCF)“ writes vCard 2.1 with QUOTED-PRINTABLE and a
 * CHARSET per line, Google's Contacts vCard 3.0. Read tolerantly: the first
 * card only, folds undone (a space or tab, and 2.1's soft line break `=` at a
 * quoted-printable line's end), its name (FN, else N's parts), its numbers
 * (TEL) and addresses (EMAIL), each once, in the card's order. Everything else
 * on the card (a photo, notes) is not looked at. Pure.
 */
object GeteilteKarte {

    data class Karte(val name: String, val telefon: List<String>, val mail: List<String>) {
        /** Nothing to look for: no name, no number, no address. */
        val leer: Boolean get() = name.isBlank() && telefon.isEmpty() && mail.isEmpty()
    }

    private const val QP = "QUOTED-PRINTABLE"
    private const val HEX = "0123456789ABCDEFabcdef"

    /** The first card in [text]; null when there is none. */
    fun lies(text: String): Karte? {
        val zeilen = ersteKarte(text) ?: return null
        var fn: String? = null
        var n: String? = null
        val telefon = LinkedHashSet<String>()
        val mail = LinkedHashSet<String>()
        for (roh in zeilen) {
            val z = VcardLeser.zeile(roh) ?: continue
            when (z.name) {
                "FN" -> if (fn == null) fn = VcardLeser.text(wert(z)).trim().ifEmpty { null }
                "N" -> if (n == null) n = name(wert(z))
                "TEL" -> VcardLeser.text(wert(z)).removePrefix("tel:").trim().ifEmpty { null }?.let(telefon::add)
                "EMAIL" -> VcardLeser.text(wert(z)).removePrefix("mailto:").trim().ifEmpty { null }?.let(mail::add)
            }
        }
        return Karte(fn ?: n.orEmpty(), telefon.toList(), mail.toList())
    }

    /** The content lines of the first BEGIN:VCARD … END:VCARD, unfolded; null without one. */
    private fun ersteKarte(text: String): List<String>? {
        var drin = false
        val out = ArrayList<String>()
        var weiterQp = false
        for (roh in text.split("\r\n", "\n", "\r")) {
            val z = roh.trim()
            when {
                !drin -> drin = z.equals("BEGIN:VCARD", ignoreCase = true)
                z.equals("END:VCARD", ignoreCase = true) -> return out
                weiterQp -> out[out.size - 1] = out.last().dropLast(1) + roh
                (roh.startsWith(" ") || roh.startsWith("\t")) && out.isNotEmpty() -> out[out.size - 1] = out.last() + roh.substring(1)
                z.isNotEmpty() -> out.add(roh)
            }
            weiterQp = drin && weichUmbrochen(out.lastOrNull())
        }
        return if (drin) out else null
    }

    /** A quoted-printable line (its head says so, `ENCODING=…` or 2.1's bare parameter) that ends in a soft break `=`. */
    private fun weichUmbrochen(zeile: String?): Boolean =
        zeile != null && zeile.endsWith("=") && zeile.substringBefore(':').uppercase().contains(QP)

    /** A line's value, quoted-printable decoded in its CHARSET (UTF-8 when it names none or one unknown). */
    private fun wert(z: VcardLeser.Zeile): String {
        val qp = (z.parameter["ENCODING"].orEmpty() + z.parameter["TYPE"].orEmpty()).any { it.equals(QP, ignoreCase = true) }
        if (!qp) return z.wert
        val charset = z.parameter["CHARSET"]?.firstOrNull()?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return String(qpBytes(z.wert), charset)
    }

    /** Quoted-printable's bytes: `=XX` one byte, a broken escape kept as it stands. */
    fun qpBytes(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val hex = if (s[i] == '=' && i + 2 < s.length) s.substring(i + 1, i + 3).takeIf { h -> h.all { it in HEX } }?.toInt(16) else null
            if (hex != null) {
                out.write(hex)
                i += 3
            } else {
                s[i].toString().toByteArray(Charsets.UTF_8).forEach { out.write(it.toInt()) }
                i++
            }
        }
        return out.toByteArray()
    }

    /** N's `Family;Given;Additional;Prefix;Suffix` as one name, given first; null when all are empty. */
    private fun name(wert: String): String? {
        val teile = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < wert.length) {
            val c = wert[i]
            when {
                c == '\\' && i + 1 < wert.length -> {
                    cur.append(c).append(wert[i + 1])
                    i++
                }
                c == ';' -> {
                    teile.add(cur.toString())
                    cur.clear()
                }
                else -> cur.append(c)
            }
            i++
        }
        teile.add(cur.toString())
        val t = teile.map { VcardLeser.text(it).trim() }
        return listOf(t.getOrNull(3), t.getOrNull(1), t.getOrNull(2), t.getOrNull(0), t.getOrNull(4))
            .filterNot { it.isNullOrEmpty() }.joinToString(" ").ifEmpty { null }
    }
}
