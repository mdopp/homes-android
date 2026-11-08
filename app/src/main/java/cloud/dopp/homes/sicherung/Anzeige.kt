package cloud.dopp.homes.sicherung

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** How „Daten sichern“ writes sizes and times: German, pure. */
object Anzeige {

    /** `01.11.2026, 03:12` in the phone's time zone. */
    fun zeit(ms: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.GERMANY).apply { timeZone = zone }.format(Date(ms))

    /**
     * A granted folder as a person finds it in the Files app, from its tree
     * document id: `primary:Android/media/com.whatsapp/WhatsApp` →
     * `Interner Speicher › Android › media › com.whatsapp › WhatsApp`; another
     * volume (`1A2B-3C4D:Signal`) is the memory card; anything else as it is.
     */
    fun ordner(dokumentId: String): String {
        val (volume, pfad) = dokumentId.split(':', limit = 2).let { if (it.size == 2) it[0] to it[1] else return dokumentId }
        val wurzel = when {
            volume == "primary" -> "Interner Speicher"
            volume == "raw" -> return pfad
            Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}").matches(volume) -> "Speicherkarte"
            else -> return dokumentId
        }
        val teile = pfad.split('/').filter { it.isNotEmpty() }
        return (listOf(wurzel) + teile).joinToString(" › ")
    }

    /** `3,4 GB`, `120 MB`, `512 KB`, `12 B`: decimal units, as a person reads a disk. */
    fun groesse(bytes: Long): String {
        val einheiten = listOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1000 && i < einheiten.lastIndex) {
            v /= 1000
            i++
        }
        return if (i == 0 || v >= 100) {
            String.format(Locale.GERMANY, "%.0f %s", v, einheiten[i])
        } else {
            String.format(Locale.GERMANY, "%.1f %s", v, einheiten[i])
        }
    }

    /**
     * `1,2 von 9,8 GB`: [bytes] of [gesamt] in [gesamt]'s unit, so the two
     * read side by side; `0 von 120 MB` before the first byte.
     */
    fun anteil(bytes: Long, gesamt: Long): String {
        val einheiten = listOf("B", "KB", "MB", "GB", "TB")
        var teiler = 1.0
        var i = 0
        while (gesamt / teiler >= 1000 && i < einheiten.lastIndex) {
            teiler *= 1000
            i++
        }
        fun zahl(v: Double) = if (i == 0 || gesamt / teiler >= 100 || v == 0.0) {
            String.format(Locale.GERMANY, "%.0f", v)
        } else {
            String.format(Locale.GERMANY, "%.1f", v)
        }
        return "${zahl(bytes.coerceIn(0, gesamt) / teiler)} von ${zahl(gesamt / teiler)} ${einheiten[i]}"
    }

    /** `1.234`: a count as a person reads it in German. */
    fun zahl(n: Int): String = java.text.NumberFormat.getIntegerInstance(Locale.GERMANY).format(n)

    /**
     * What Homes' mirror of the contacts came to: `412 Kontakte, 3 neu,
     * 1 geändert`, with `2 entfernt` when any were; a change of none is left out.
     */
    fun kontakte(kontakte: Int, neu: Int, geaendert: Int, entfernt: Int): String =
        (listOf("${zahl(kontakte)} ${if (kontakte == 1) "Kontakt" else "Kontakte"}") +
            listOfNotNull(
                neu.takeIf { it > 0 }?.let { "${zahl(it)} neu" },
                geaendert.takeIf { it > 0 }?.let { "${zahl(it)} geändert" },
                entfernt.takeIf { it > 0 }?.let { "${zahl(it)} entfernt" },
            )).joinToString(", ")

    /** What the last transfer of SMS and calls carried: `1.234 SMS, 56 MMS, 789 Anrufe`; a kind with none is left out. */
    fun nachrichten(zahlen: Map<Art, Int>): String = listOfNotNull(
        zahlen[Art.SMS]?.takeIf { it > 0 }?.let { "${zahl(it)} SMS" },
        zahlen[Art.MMS]?.takeIf { it > 0 }?.let { "${zahl(it)} MMS" },
        zahlen[Art.ANRUFE]?.takeIf { it > 0 }?.let { "${zahl(it)} ${if (it == 1) "Anruf" else "Anrufe"}" },
    ).joinToString(", ")
}
