package cloud.dopp.homes.archiv

import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile

/**
 * „Ins Archiv“ (owner, 2026-11-11): a Facebook or LinkedIn export, told by
 * its structure, never read further. A ZIP by its entries' names: Facebook's
 * friends list (`connections/friends/your_friends.json`, 2024;
 * `friends_and_followers/friends.json`, 2022; `friends/friends.json`
 * before), LinkedIn's `Connections.csv`; a single file by its first bytes.
 * Only that one file goes on, as its bytes: the person's archive reads it
 * (archiv `sozial.go`), the app and Homes never do.
 */
object Export {

    /** The two exports, as their name in the archive's inbox and the file's ending. */
    enum class Art(val wire: String, val endung: String) { FACEBOOK("facebook", "json"), LINKEDIN("linkedin", "csv") }

    sealed class Erkannt {
        /** An export of [art]; [eintrag] the ZIP entry that holds it, null: the file is it. */
        data class Ja(val art: Art, val eintrag: String?) : Erkannt()

        /** Facebook's export in its HTML format: the archive reads only JSON. */
        object FacebookHtml : Erkannt()

        /** Anything else. */
        object Fremd : Erkannt()
    }

    private val FREUNDE = listOf("connections/friends/your_friends.", "friends_and_followers/friends.", "friends/friends.", "friends/your_friends.")
    private const val KOPF = 64 * 1024

    /** Is [eintrag] (a ZIP entry's name) Facebook's friends list as [endung]? */
    private fun freunde(eintrag: String, endung: String): Boolean {
        val n = eintrag.lowercase(Locale.ROOT)
        return FREUNDE.any { n == it + endung || n.endsWith("/$it$endung") }
    }

    fun erkennen(datei: File): Erkannt {
        val namen = runCatching { ZipFile(datei).use { z -> z.entries().toList().map { it.name } } }.getOrNull()
            ?: return einzeln(datei)
        namen.firstOrNull { freunde(it, "json") }?.let { return Erkannt.Ja(Art.FACEBOOK, it) }
        namen.firstOrNull { it.substringAfterLast('/').equals("Connections.csv", ignoreCase = true) }
            ?.let { return Erkannt.Ja(Art.LINKEDIN, it) }
        return if (namen.any { freunde(it, "html") }) Erkannt.FacebookHtml else Erkannt.Fremd
    }

    /** A single file: Facebook's friends JSON, or a CSV with LinkedIn's header in its first lines. */
    private fun einzeln(datei: File): Erkannt {
        val kopf = runCatching { datei.inputStream().use { it.readNBytesCompat(KOPF) } }.getOrNull() ?: return Erkannt.Fremd
        val text = String(kopf, Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
        return when {
            text.startsWith("{") && (text.contains("\"friends_v2\"") || text.contains("\"friends\"")) -> Erkannt.Ja(Art.FACEBOOK, null)
            text.lineSequence().take(KOPFZEILEN).any { it.contains("First Name") && it.contains("Last Name") } -> Erkannt.Ja(Art.LINKEDIN, null)
            else -> Erkannt.Fremd
        }
    }

    private const val KOPFZEILEN = 20

    /** [mit] the bytes of the export's one file of [ja] in [datei], streamed. */
    fun <T> lesen(datei: File, ja: Erkannt.Ja, mit: (InputStream) -> T): T {
        val eintrag = ja.eintrag ?: return datei.inputStream().use(mit)
        return ZipFile(datei).use { z -> z.getInputStream(z.getEntry(eintrag)).use(mit) }
    }

    private fun InputStream.readNBytesCompat(n: Int): ByteArray {
        val b = ByteArray(n)
        var da = 0
        while (da < n) {
            val r = read(b, da, n - da)
            if (r < 0) break
            da += r
        }
        return b.copyOf(da)
    }
}
