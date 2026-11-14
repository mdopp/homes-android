package cloud.dopp.homes.sicherung

import cloud.dopp.homes.data.Napi.Quelle
import cloud.dopp.homes.data.SicherungClient
import cloud.dopp.homes.data.SicherungClient.LetzteDatei
import java.io.IOException
import java.io.OutputStream

/**
 * Where a restore writes (homes-android#197): a folder the person granted
 * with write access, by paths below it. On the phone it is [SafZiel], the
 * one writer in the app; in the tests a folder on disk.
 */
interface Ziel {
    /** The size of the file at [pfad], or null when there is none. */
    fun groesse(pfad: String): Long?

    /** Write [pfad] from byte [ab] on: what lies beyond is cut, a missing file and its folders are made. */
    fun schreiben(pfad: String, ab: Long): OutputStream
}

/**
 * Which files in a [Ziel] the restore wrote itself, by source and path, with
 * the box's ETag of the file they are a copy of: only those it goes on
 * writing without asking. On the phone it is [RueckStore].
 */
interface Rueckgedaechtnis {
    /** Ours, and how far: [Stand.LAEUFT] or [Stand.FERTIG], with the ETag; null when the file is not ours. */
    fun unser(quelle: Quelle, pfad: String): Pair<Stand, String>?

    /** Kept at once (commit): it must outlive a process killed in the next breath. */
    fun merke(quelle: Quelle, pfad: String, stand: Stand, etag: String)

    enum class Stand { LAEUFT, FERTIG }
}

/**
 * Sicherung zurückholen (homes-android#197, homes#209): the newest Signal and
 * WhatsApp backup the box keeps for the person, back onto a new phone,
 * resumable and as totals for the screen.
 *
 * - **Nothing is overwritten without asking.** A file already in the folder
 *   that the restore did not write is [Plan.konflikte] unless it has the
 *   box's size ([Plan.schon]); it is written only when the person said so
 *   ([laufen]'s `ersetzen`).
 * - **Resumable.** A file of ours goes on from its size with a Range; when the
 *   box's ETag changed meanwhile (the next night's backup), it starts over.
 * - **WhatsApp's media first, its database last**: the database is what
 *   WhatsApp looks for, so it lies there only once the rest does.
 * - Everything comes only while the person's archive is open (archiv#64: the
 *   box keeps each file encrypted for the archive). A closed archive (423) on
 *   a medium: the other media are not asked for one by one; on Signal's backup
 *   or WhatsApp's database (kept so since archiv#64): the run stops. Either
 *   way [Ausgang.medienSatz] carries Homes' sentence, and the screen offers
 *   „Archiv öffnen“.
 */
class Rueckholung(
    private val client: SicherungClient,
    private val gedaechtnis: Rueckgedaechtnis,
    private val halt: () -> Boolean = { false },
    private val melde: (Fortschritt) -> Unit = {},
    private val puffer: Int = PUFFER,
) {
    /** One file to bring back: [datei] of [quelle], written at [ziel] in the folder. */
    data class Eintrag(val quelle: Quelle, val datei: LetzteDatei, val ziel: String)

    /** What lies in the folder already, per file. */
    data class Plan(
        /** To fetch: missing, or ours and not whole. */
        val holen: List<Eintrag>,
        /** There already with the box's size, or ours and whole. */
        val schon: List<Eintrag>,
        /** There already, not ours, another size: only when the person says so. */
        val konflikte: List<Eintrag>,
    ) {
        val alle: List<Eintrag> get() = schon + holen + konflikte
    }

    enum class Art {
        /** Everything asked for lies in the folder. */
        FERTIG,

        /** Stopped by the person or the screen going away: the next start goes on. */
        ANGEHALTEN,

        /** Homes did not answer or the line dropped: the next start goes on. */
        UNERREICHBAR,

        /** A file is no longer on the box (a newer backup came): load the list again. */
        VERALTET,

        /** Homes said no for all of it: [Ausgang.satz]. */
        HOMES,

        /** The folder took no more (full, or its grant gone): [Ausgang.satz]; choose it again and go on. */
        ORDNER,
    }

    /** How a run ended; [geholt] files came whole in it; [medienSatz] when the media could not come. */
    data class Ausgang(val art: Art, val geholt: Int, val satz: String? = null, val medienSatz: String? = null)

    /** Look into [ziel] for each of [eintraege]: what to fetch, what is there, what would be overwritten. */
    fun planen(eintraege: List<Eintrag>, ziel: Ziel): Plan {
        val holen = ArrayList<Eintrag>()
        val schon = ArrayList<Eintrag>()
        val konflikte = ArrayList<Eintrag>()
        for (e in reihenfolge(eintraege)) {
            val da = ziel.groesse(e.ziel)
            val unser = gedaechtnis.unser(e.quelle, e.ziel)
            when {
                da == null -> holen += e
                unser?.first == Rueckgedaechtnis.Stand.FERTIG && da == e.datei.groesse -> schon += e
                unser != null -> holen += e
                da == e.datei.groesse -> schon += e
                else -> konflikte += e
            }
        }
        return Plan(holen, schon, konflikte)
    }

    /**
     * Fetch [plan]'s files into [ziel], and of its conflicts those in
     * [ersetzen] (by [Eintrag.ziel]). The totals count every file of the plan,
     * those already there as done.
     */
    fun laufen(plan: Plan, ziel: Ziel, ersetzen: Set<String> = emptySet()): Ausgang {
        val ersetzt = plan.konflikte.filter { it.ziel in ersetzen }
        val liste = reihenfolge(plan.holen + ersetzt)
        val von = plan.schon.size + liste.size
        val gesamt = (plan.schon + liste).sumOf { it.datei.groesse }
        var fertig = plan.schon.size
        var bytes = plan.schon.sumOf { it.datei.groesse }
        var geholt = 0
        var medienSatz: String? = null
        // a replaced file is ours from its first byte: the restore owns it from here on
        val neu = ersetzt.map { it.ziel }.toMutableSet()
        for (e in liste) {
            if (halt()) return Ausgang(Art.ANGEHALTEN, geholt, medienSatz = medienSatz)
            if (medienSatz != null && istMedium(e)) {
                fertig++
                bytes += e.datei.groesse
                continue
            }
            val ergebnis = try {
                hole(e, ziel, e.ziel in neu) { n -> melde(Fortschritt(e.quelle, name(e), fertig, von, bytes + n, gesamt)) }
            } catch (a: SicherungClient.Abgelehnt) {
                when {
                    a.code == 423 && istMedium(e) || a.code == 503 && istMedium(e) -> {
                        medienSatz = a.satz ?: MEDIEN_ZU
                        null
                    }
                    a.code == 423 -> return Ausgang(Art.HOMES, geholt, medienSatz = a.satz ?: MEDIEN_ZU)
                    a.code == 404 -> return Ausgang(Art.VERALTET, geholt, VERALTET, medienSatz)
                    a.code == 401 -> return Ausgang(Art.HOMES, geholt, Lauf.NICHT_GEKOPPELT, medienSatz)
                    a.code == 429 || a.code >= 500 -> return Ausgang(Art.UNERREICHBAR, geholt, UNERREICHBAR, medienSatz)
                    else -> return Ausgang(Art.HOMES, geholt, a.satz ?: "Homes hat „${name(e)}“ nicht herausgegeben (${a.code}).", medienSatz)
                }
            } catch (x: ZielFehler) {
                return Ausgang(Art.ORDNER, geholt, ORDNER_FEHLER, medienSatz)
            } catch (x: Veraltet) {
                return Ausgang(Art.VERALTET, geholt, VERALTET, medienSatz)
            } catch (x: IOException) {
                return Ausgang(if (halt()) Art.ANGEHALTEN else Art.UNERREICHBAR, geholt, if (halt()) null else UNERREICHBAR, medienSatz)
            }
            neu.remove(e.ziel)
            if (ergebnis == Holen.ANGEHALTEN) return Ausgang(Art.ANGEHALTEN, geholt, medienSatz = medienSatz)
            if (ergebnis == Holen.GEHOLT) geholt++
            fertig++
            bytes += e.datei.groesse
            melde(Fortschritt(e.quelle, name(e), fertig, von, bytes, gesamt))
        }
        return Ausgang(Art.FERTIG, geholt, medienSatz = medienSatz)
    }

    private enum class Holen { GEHOLT, ANGEHALTEN }

    /** Writing into the folder failed: not the box's fault, so not „nicht erreichbar“. */
    private class ZielFehler(cause: IOException) : RuntimeException(cause)

    /** The file the box sends is not the one it listed. */
    private class Veraltet : IOException("the box has another file now")

    /**
     * One file, from where ours stopped ([ersetzen]: from 0, over what is
     * there). The box's ETag decides whether what is there is a piece of the
     * same file; another one starts it over.
     */
    private fun hole(e: Eintrag, ziel: Ziel, ersetzen: Boolean, fortschritt: (Long) -> Unit): Holen {
        val unser = gedaechtnis.unser(e.quelle, e.ziel)
        var ab = if (ersetzen || unser == null) 0L else (ziel.groesse(e.ziel) ?: 0L).coerceAtMost(e.datei.groesse)
        if (unser?.first == Rueckgedaechtnis.Stand.FERTIG && ab == e.datei.groesse) return Holen.GEHOLT
        repeat(2) {
            val neuStart = client.holen(e.quelle, e.datei.pfad, ab) { strom ->
                val etag = strom.etag.orEmpty()
                if (ab > 0 && unser != null && unser.second.isNotEmpty() && etag.isNotEmpty() && etag != unser.second) {
                    return@holen true // a piece of another file: from the start
                }
                gedaechtnis.merke(e.quelle, e.ziel, Rueckgedaechtnis.Stand.LAEUFT, etag)
                var n = strom.start
                fortschritt(n)
                val aus = try {
                    ziel.schreiben(e.ziel, n)
                } catch (x: IOException) {
                    throw ZielFehler(x)
                }
                aus.use {
                    val buf = ByteArray(puffer)
                    while (true) {
                        if (halt()) return@holen null
                        val r = strom.ein.read(buf)
                        if (r < 0) break
                        try {
                            aus.write(buf, 0, r)
                        } catch (x: IOException) {
                            throw ZielFehler(x)
                        }
                        n += r
                        fortschritt(n)
                    }
                }
                if (n != e.datei.groesse) throw Veraltet() // the box's file is not the one listed: a newer backup
                gedaechtnis.merke(e.quelle, e.ziel, Rueckgedaechtnis.Stand.FERTIG, etag)
                false
            }
            when (neuStart) {
                null -> return Holen.ANGEHALTEN
                false -> return Holen.GEHOLT
                true -> ab = 0
            }
        }
        throw Veraltet()
    }

    companion object {
        /** One read and write: big enough for a 3 GB file, small enough to stop at once. */
        const val PUFFER = 64 * 1024

        const val MEDIEN_ZU = "Öffne dein Archiv, dann kommen auch die Fotos und Sprachnachrichten zurück."
        const val VERALTET = "Auf der Box liegt inzwischen eine neuere Sicherung: tippe auf „Zurückholen“, dann holt die App sie."
        const val ORDNER_FEHLER =
            "In den Ordner ließ sich nicht schreiben: ist das Handy voll, oder war die Freigabe weg? Wähle den Ordner neu, dann geht es weiter."
        const val UNERREICHBAR = "Homes war nicht erreichbar: tippe auf „Weiter“, dann macht die App dort weiter."

        /** The WhatsApp media are `Media/…` on the box and in WhatsApp's folder. */
        fun istMedium(e: Eintrag): Boolean = e.quelle == Quelle.WHATSAPP && e.datei.pfad.startsWith(MEDIA)

        private const val MEDIA = "Media/"

        /** Media first, WhatsApp's database last; otherwise as the box listed them. */
        fun reihenfolge(eintraege: List<Eintrag>): List<Eintrag> =
            eintraege.sortedBy { if (it.quelle == Quelle.WHATSAPP && !istMedium(it)) 1 else 0 }

        private fun name(e: Eintrag) = e.datei.pfad.substringAfterLast('/')

        /**
         * WhatsApp's files for the folder the person granted, by its tree
         * document id: `…Android/media/com.whatsapp/WhatsApp` takes them as they
         * are, `…Android/media/com.whatsapp` below `WhatsApp/`, `…Android/media`
         * below `com.whatsapp/WhatsApp/` (on a new phone it may not be there yet).
         * Null for any other folder.
         */
        fun whatsappUnter(dokumentId: String): String? {
            val pfad = dokumentId.substringAfter(':', "").trim('/')
            return when {
                pfad.equals("Android/media/com.whatsapp/WhatsApp", ignoreCase = true) -> ""
                pfad.equals("Android/media/com.whatsapp", ignoreCase = true) -> "WhatsApp/"
                pfad.equals("Android/media", ignoreCase = true) -> "com.whatsapp/WhatsApp/"
                else -> null
            }
        }

        /**
         * The person's archive beside Homes: the server's first label swapped
         * for `archiv` (laib's archive template, one name for every person's
         * instance), whatever that label is — `https://www.<domain>` (Homes since
         * 2026-11-07) and `https://homes.<domain>` both → `https://archiv.<domain>/`. Null for
         * http, a host of one label or an IP address.
         */
        fun archivAdresse(homesBase: String): String? {
            val m = Regex("""^https://([^./?#:]+)\.([^/?#]+).*$""", RegexOption.IGNORE_CASE).matchEntire(homesBase.trim()) ?: return null
            val rest = m.groupValues[2]
            if (rest.substringBefore(':').split('.').all { it.isNotEmpty() && it.all(Char::isDigit) }) return null
            return "https://archiv.$rest/"
        }

        /** The files to bring back, at their place in the granted folder ([whatsappUnter]'s prefix; Signal's in the folder itself). */
        fun eintraege(quelle: Quelle, dateien: List<LetzteDatei>, unter: String = ""): List<Eintrag> = dateien.map {
            Eintrag(quelle, it, if (quelle == Quelle.SIGNAL) it.pfad.substringAfterLast('/') else unter + it.pfad)
        }
    }
}
