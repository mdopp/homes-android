package cloud.dopp.homes.sicherung

import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.Napi.Quelle
import cloud.dopp.homes.data.SicherungClient
import java.io.IOException

/**
 * What a run remembers between runs, and what the screen shows: the backup
 * sent last per source, each source's last word, the upload ids ([Uploader.Merker]).
 * On the phone it is [SicherungStore]; in the tests a map.
 */
interface Gedaechtnis : Uploader.Merker {
    fun gesendet(quelle: Quelle): Gesendet?
    fun merkeGesendet(quelle: Quelle, g: Gesendet)

    /** When a file of [quelle] last reached the box whole. */
    fun merkeUebertragen(quelle: Quelle, zeit: Long, dateien: Int)

    fun melde(quelle: Quelle, meldung: Meldung)
}

/** A source's last word, for the screen: a German sentence with the next step, or none when all went well. */
data class Meldung(val zeit: Long, val art: Art, val satz: String? = null) {
    enum class Art {
        /** Everything there is on the box. */
        OK,

        /** The folder cannot be read: the tap opens the folder picker again. */
        ORDNER,

        /** The folder holds no backup: the guide says how to switch it on. */
        KEINE,

        /** Not done yet; the app tries again by itself. */
        SPAETER,

        /** Homes said no: the box is full, has no archive, or the device is no longer paired. */
        HOMES,
    }

    /** As the store keeps it: `zeit|ART|satz`. */
    fun text() = "$zeit|${art.name}|${satz.orEmpty()}"

    companion object {
        /** A kept [text], or null when there is none or it does not read. */
        fun aus(s: String?): Meldung? {
            val t = s?.split('|', limit = 3)?.takeIf { it.size == 3 } ?: return null
            val art = runCatching { Art.valueOf(t[1]) }.getOrNull() ?: return null
            return Meldung(t[0].toLongOrNull() ?: return null, art, t[2].ifBlank { null })
        }
    }
}

/**
 * A run under way, for the screen, as totals over the files of [quelle] it
 * sends (owner, 2026-11-01: „3809 von 18 017 Dateien, 1,2 von 9,8 GB“, not
 * the bytes of one file): [fertig] of [von] files are whole, [bytes] of
 * [gesamt] bytes are on the box, the file under way counted as far as it got.
 * [name] is that file's.
 */
data class Fortschritt(
    val quelle: Quelle,
    val name: String,
    val fertig: Int,
    val von: Int,
    val bytes: Long,
    val gesamt: Long,
)

/**
 * One run of Daten sichern (#194): WhatsApp's newest database backup
 * and the media Homes lacks, Signal's newest backup if it is newer than the
 * one sent last. Only reads the folders. Stops cleanly between two pieces
 * when [halt] says so or [frist] is up; the next run goes on.
 *
 * Without a granted folder it makes no request at all.
 */
class Lauf(
    private val client: SicherungClient,
    private val gedaechtnis: Gedaechtnis,
    /** The granted folder of a source, or null when none was granted. */
    private val ordner: (Quelle) -> Ordner?,
    halt: () -> Boolean = { false },
    private val melde: (Fortschritt) -> Unit = {},
    private val uhr: () -> Long = System::currentTimeMillis,
    frist: Long = FRIST_MS,
    teil: Int = Uploader.TEIL,
) {
    enum class Ergebnis {
        /** Nothing granted: nothing done, nothing asked. */
        NICHTS,

        /** Everything there is, is on the box. */
        FERTIG,

        /** Not all of it yet: interrupted, time up, or a backup still being written. Go on soon. */
        WEITER,

        /** Homes did not answer, or the connection dropped: again soon, with backoff. */
        UNERREICHBAR,

        /** Homes is busy (429: at most four pieces at once across the household): again soon, with backoff. */
        BESCHAEFTIGT,

        /** Homes said no for now (full, no archive, not paired): the next daily run tries again. */
        HALT,
    }

    /** How one file came out. */
    private enum class Versand { JA, SPAETER, ANGEHALTEN, UNERREICHBAR, BESCHAEFTIGT }

    private val start = uhr()
    private val halt: () -> Boolean = { halt() || uhr() - start > frist }
    private val uploader = Uploader(client, gedaechtnis, teil)

    /** Homes' sentence for a file it refused in this run, shown instead of the plain "later". */
    private var abgelehnt: String? = null

    /** A source's run ends early with [ergebnis]. */
    private class Ende(val ergebnis: Ergebnis) : Exception()

    fun laufen(): Ergebnis {
        val quellen = QUELLEN.mapNotNull { q -> ordner(q)?.let { q to it } }
        if (quellen.isEmpty()) return Ergebnis.NICHTS
        var weiter = false
        for ((q, o) in quellen) {
            abgelehnt = null
            val e = try {
                when (q) {
                    Quelle.WHATSAPP -> whatsapp(o)
                    Quelle.SIGNAL -> signal(o)
                    // SMS and calls have their own run (NachrichtenLauf), an export its share (archiv/InsArchiv)
                    Quelle.SMS, Quelle.SOZIAL -> Ergebnis.FERTIG
                }
            } catch (e: Ende) {
                schluss(q, 0, e.ergebnis)
            }
            when (e) {
                Ergebnis.HALT, Ergebnis.UNERREICHBAR, Ergebnis.BESCHAEFTIGT -> return e
                Ergebnis.WEITER -> weiter = true
                else -> Unit
            }
            if (halt()) return Ergebnis.WEITER
        }
        return if (weiter) Ergebnis.WEITER else Ergebnis.FERTIG
    }

    private fun whatsapp(o: Ordner): Ergebnis {
        val q = Quelle.WHATSAPP
        val db = o.dateien(Auswahl.DATENBANKEN) ?: return ordnerWeg(q)
        val alle = o.dateien(Auswahl.MEDIEN, tief = true) ?: return ordnerWeg(q)
        val jetzt = uhr()
        val wahl = Auswahl.msgstore(db, gedaechtnis.gesendet(q), jetzt)
        // Auswahl leaves out every path with a dot-name at any level: Homes refuses a whole `fehlend` for one.
        val medien = Auswahl.medien(alle, jetzt)
        val fehlend = medien.chunked(Napi.FEHLEND_MAX).flatMap { stapel ->
            Auswahl.fehlende(stapel, frage(q) { client.fehlend(q, stapel.map { it.pfad to it.groesse }) })
        }
        val datenbank = (wahl as? Auswahl.Wahl.Senden)?.datei
        val liste = listOfNotNull(datenbank) + fehlend
        var gesendet = 0
        var spaeter = wahl is Auswahl.Wahl.Wartet
        val summe = Summe(liste.size, liste.sumOf { it.groesse })
        liste.forEachIndexed { i, d ->
            val pfad = if (d === datenbank) Auswahl.MSGSTORE_PFAD else d.pfad
            val v = sende(q, o, d, pfad, summe)
            summe.weiter(i + 1, d.groesse)
            when (v) {
                Versand.JA -> {
                    gesendet++
                    if (d === datenbank) gedaechtnis.merkeGesendet(q, Gesendet.von(d))
                }
                Versand.SPAETER -> spaeter = true
                Versand.ANGEHALTEN -> return schluss(q, gesendet, Ergebnis.WEITER)
                Versand.UNERREICHBAR -> return schluss(q, gesendet, Ergebnis.UNERREICHBAR)
                Versand.BESCHAEFTIGT -> return schluss(q, gesendet, Ergebnis.BESCHAEFTIGT)
            }
        }
        val e = schluss(q, gesendet, if (spaeter) Ergebnis.WEITER else Ergebnis.FERTIG)
        // Media alone are no backup of the chats: without msgstore.db.crypt15 the guide's first step is missing.
        if (e == Ergebnis.FERTIG && wahl == Auswahl.Wahl.Keine) {
            gedaechtnis.melde(q, Meldung(uhr(), Meldung.Art.KEINE, KEINE_WHATSAPP))
        }
        return e
    }

    private fun signal(o: Ordner): Ergebnis {
        val q = Quelle.SIGNAL
        val dateien = o.dateien() ?: return ordnerWeg(q)
        val jetzt = uhr()
        return when (val wahl = Auswahl.signal(dateien, gedaechtnis.gesendet(q), jetzt)) {
            Auswahl.Wahl.Keine -> {
                gedaechtnis.melde(q, Meldung(jetzt, Meldung.Art.KEINE, KEINE_SIGNAL))
                Ergebnis.FERTIG
            }
            Auswahl.Wahl.Schon -> schluss(q, 0, Ergebnis.FERTIG)
            is Auswahl.Wahl.Wartet -> schluss(q, 0, Ergebnis.WEITER)
            is Auswahl.Wahl.Senden -> when (sende(q, o, wahl.datei, wahl.datei.name, Summe(1, wahl.datei.groesse))) {
                Versand.JA -> {
                    gedaechtnis.merkeGesendet(q, Gesendet.von(wahl.datei))
                    schluss(q, 1, Ergebnis.FERTIG)
                }
                Versand.SPAETER, Versand.ANGEHALTEN -> schluss(q, 0, Ergebnis.WEITER)
                Versand.UNERREICHBAR -> schluss(q, 0, Ergebnis.UNERREICHBAR)
                Versand.BESCHAEFTIGT -> schluss(q, 0, Ergebnis.BESCHAEFTIGT)
            }
        }
    }

    /**
     * One file. Homes' "no" for every file (full, no archive, not paired)
     * ends the run; a "no" for this one file (a 4xx) leaves it to a later run
     * while the others go.
     */
    private fun sende(q: Quelle, o: Ordner, d: Datei, pfad: String, summe: Summe): Versand {
        val a = uploader.hochladen(q, o, d, pfad, halt) { n -> melde(summe.fortschritt(q, d.name, n)) }
        return when (a) {
            Uploader.Ausgang.Fertig -> Versand.JA
            Uploader.Ausgang.Angehalten -> Versand.ANGEHALTEN
            Uploader.Ausgang.Veraendert -> Versand.SPAETER
            is Uploader.Ausgang.Voll -> throw homes(q, a.satz)
            is Uploader.Ausgang.Fehler -> when {
                a.code in HOMES_NEIN -> throw homes(q, satzFuer(a.code, a.satz))
                a.code == 429 -> Versand.BESCHAEFTIGT
                a.code == 0 || a.code >= 500 -> Versand.UNERREICHBAR
                a.code == 404 || a.code == 409 -> Versand.SPAETER // no progress on this upload: a fresh start next run
                else -> {
                    abgelehnt = a.satz ?: "Homes hat „${d.name}“ nicht angenommen (${a.code}); die App versucht es beim nächsten Mal wieder."
                    Versand.SPAETER
                }
            }
        }
    }

    /** The totals of one source's files: [fertig] files and [bytes] behind, of [von] and [gesamt]. */
    private class Summe(val von: Int, val gesamt: Long) {
        var fertig = 0
            private set
        var bytes = 0L
            private set

        /** [nr] files are behind, the last one [groesse] bytes: sent, or left for a later run. */
        fun weiter(nr: Int, groesse: Long) {
            fertig = nr
            bytes += groesse
        }

        /** The file under way has [n] bytes on the box. */
        fun fortschritt(q: Quelle, name: String, n: Long) = Fortschritt(q, name, fertig, von, bytes + n, gesamt)
    }

    /** A call outside an upload: Homes' "no" ends the run, no answer waits for the next one. */
    private fun <T> frage(q: Quelle, f: () -> T): T = try {
        f()
    } catch (e: SicherungClient.Abgelehnt) {
        if (e.code in HOMES_NEIN || e.code == 507) throw homes(q, satzFuer(e.code, e.satz))
        if (e.code == 429) throw Ende(Ergebnis.BESCHAEFTIGT)
        if (e.code in 400..499) abgelehnt = e.satz
        throw Ende(if (e.code in 400..499) Ergebnis.WEITER else Ergebnis.UNERREICHBAR)
    } catch (e: IOException) {
        throw Ende(Ergebnis.UNERREICHBAR)
    }

    private fun homes(q: Quelle, satz: String): Ende {
        gedaechtnis.melde(q, Meldung(uhr(), Meldung.Art.HOMES, satz))
        return Ende(Ergebnis.HALT)
    }

    private fun ordnerWeg(q: Quelle): Ergebnis {
        gedaechtnis.melde(q, Meldung(uhr(), Meldung.Art.ORDNER, if (q == Quelle.SIGNAL) ORDNER_SIGNAL else ORDNER_WHATSAPP))
        return Ergebnis.FERTIG
    }

    /** The source's last word after [gesendet] files. HALT's word was said already. */
    private fun schluss(q: Quelle, gesendet: Int, e: Ergebnis): Ergebnis {
        val jetzt = uhr()
        if (gesendet > 0) gedaechtnis.merkeUebertragen(q, jetzt, gesendet)
        when (e) {
            Ergebnis.FERTIG -> gedaechtnis.melde(q, Meldung(jetzt, Meldung.Art.OK))
            Ergebnis.WEITER -> gedaechtnis.melde(q, Meldung(jetzt, Meldung.Art.SPAETER, abgelehnt ?: SPAETER))
            Ergebnis.UNERREICHBAR -> gedaechtnis.melde(q, Meldung(jetzt, Meldung.Art.SPAETER, UNERREICHBAR))
            Ergebnis.BESCHAEFTIGT -> gedaechtnis.melde(q, Meldung(jetzt, Meldung.Art.SPAETER, BESCHAEFTIGT))
            else -> Unit
        }
        return e
    }

    companion object {
        /** The sources the phone backs up, in this order. */
        val QUELLEN = listOf(Quelle.WHATSAPP, Quelle.SIGNAL)

        /**
         * Homes' "no" for every file of this device: not paired (401), a device
         * of nobody (403), no archive on the box (503). The run stops with the sentence.
         */
        val HOMES_NEIN = setOf(401, 403, 503)

        /** Below Android's ten minutes for a background job: the run ends between two pieces and goes on. */
        const val FRIST_MS = 9 * 60 * 1000L

        const val ORDNER_WHATSAPP = "WhatsApp-Ordner nicht mehr freigegeben."
        const val ORDNER_SIGNAL = "Signal-Ordner nicht mehr freigegeben."
        const val KEINE_WHATSAPP =
            "Im WhatsApp-Ordner liegt keine Sicherung: schalte in WhatsApp unter Chats → Chat-Backup das Ende-zu-Ende-verschlüsselte Backup ein."
        const val KEINE_SIGNAL =
            "Im Signal-Ordner liegt keine Sicherung: schalte in Signal unter Chats die Chat-Backups ein und wähle dort diesen Ordner."
        const val SPAETER = "Noch nicht alles übertragen: die App macht beim nächsten Mal im WLAN weiter."
        const val UNERREICHBAR = "Homes war nicht erreichbar: die App versucht es bald von selbst wieder."
        const val BESCHAEFTIGT = "Die Box ist gerade beschäftigt: die App versucht es bald von selbst wieder."
        const val NICHT_GEKOPPELT = "Die App ist nicht mehr mit Homes gekoppelt: koppel sie auf dem Startbildschirm neu."

        /** Homes' sentence, or ours for a status without one. */
        fun satzFuer(code: Int, satz: String?): String {
            if (code == 401) return NICHT_GEKOPPELT // Homes' 401 body is no sentence for a person
            return satz ?: when (code) {
                403 -> "Dieses Gerät gehört zu keiner Person: kopple die Homes-App neu, angemeldet mit deinem Namen."
            503 -> "Auf der Box ist noch kein Archiv eingerichtet: frag die Person, die sich um die Box kümmert."
                507 -> "Auf der Box ist kein Platz mehr für die Sicherung: frag die Person, die sich um die Box kümmert."
                else -> "Homes hat die Sicherung nicht angenommen ($code)."
            }
        }
    }
}
