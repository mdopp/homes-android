package cloud.dopp.homes.sicherung

import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.SicherungClient
import java.io.IOException
import java.io.InputStream

/**
 * A folder the person granted, read only: what is in it and a file's bytes.
 * On the phone it is the storage access framework ([SafOrdner]); in the tests
 * a directory.
 */
interface Ordner {
    /**
     * The files directly in [unter] (`""` is the folder itself), or with [tief]
     * every file below it, each with its path below the granted folder. A
     * dot-name is neither listed nor entered. A missing [unter] is empty; null
     * when the folder cannot be read any more (the grant was taken back, or
     * the folder is gone).
     */
    fun dateien(unter: String = "", tief: Boolean = false): List<Datei>?

    /** [pfad]'s bytes from [ab] on. */
    fun oeffnen(pfad: String, ab: Long): InputStream
}

/**
 * One file to Homes over the resumable upload (#194, [Stueckweise]): after any
 * interruption it goes on where Homes stands instead of starting over. The
 * upload's id is remembered ([Merker]) until the file is whole; the next run
 * asks Homes for the offset (HEAD) and continues from it. A cut ends this run.
 * Homes keeps a finished upload known for seven days: when the last answer was
 * lost, HEAD says offset = size, and an offset at the size — after HEAD or
 * straight from the creation (a partial already complete) — is a finished file.
 *
 * With an age recipient (archiv#64: WhatsApp's and Signal's backups) the file
 * goes up encrypted to the person's archive as it is read ([Age.ab]); its
 * [Age.Umschlag] is kept with the upload's id, so every piece after a cut is
 * the same bytes. A part on the box this phone has no Umschlag for (its memory
 * was lost) cannot be gone on with: [Ausgang.Rest] waits until Homes drops it.
 */
class Uploader(
    private val client: SicherungClient,
    private val merker: Merker,
    private val teil: Int = TEIL,
    /** The person's archive key: every file goes up age-encrypted to it; null: as it is (already encrypted). */
    private val an: ByteArray? = null,
) {

    /** Where an unfinished upload's id is kept, per source and path, with the file's size and time. */
    interface Merker {
        fun upload(quelle: Napi.Quelle, pfad: String): String?
        fun merkeUpload(quelle: Napi.Quelle, pfad: String, zeile: String)
        fun vergissUpload(quelle: Napi.Quelle, pfad: String)
    }

    sealed class Ausgang {
        /** The file is whole on the box. */
        object Fertig : Ausgang()

        /** Asked to stop (constraints lost, time up): the next run goes on. */
        object Angehalten : Ausgang()

        /** The box is full (507): stop, say [satz]. */
        data class Voll(val satz: String) : Ausgang()

        /** The file is no longer what was listed (shorter): leave it to the next listing. */
        object Veraendert : Ausgang()

        /** Homes refused ([code], its [satz]), or no answer came (code 0). */
        data class Fehler(val code: Int, val satz: String?) : Ausgang()

        /** Homes holds part of this file encrypted in a way this phone no longer knows: later. */
        object Rest : Ausgang()
    }

    /** A part of another encryption on the box. */
    private class FremderRest : IOException()

    /** What goes up for a file: its bytes, or its bytes encrypted to [an] with the current [umschlag]. */
    private class Inhalt(val ordner: Ordner, val datei: Datei, val an: ByteArray?) {
        var umschlag: Age.Umschlag? = null
        val groesse: Long = if (an == null) datei.groesse else Age.groesse(datei.groesse)

        /** Never the plain bytes of a file meant to go encrypted. */
        fun oeffnen(ab: Long): InputStream {
            if (an == null) return ordner.oeffnen(datei.pfad, ab)
            val u = umschlag ?: throw IOException("no Umschlag for an encrypted upload")
            return Age.ab(an, u, datei.groesse, ab) { ordner.oeffnen(datei.pfad, it) }
        }
    }

    /** [datei] of [ordner] as [quelle]'s file at [pfad]; [halt] is asked before every piece. */
    fun hochladen(
        quelle: Napi.Quelle,
        ordner: Ordner,
        datei: Datei,
        pfad: String = datei.pfad,
        halt: () -> Boolean = { false },
        fortschritt: (Long) -> Unit = {},
    ): Ausgang = try {
        val inhalt = Inhalt(ordner, datei, an)
        val ruf = object : Stueckweise.Ruf {
            override fun anlegen(): Pair<String, Long> {
                merker.vergissUpload(quelle, pfad)
                return anlegen(quelle, pfad, inhalt)
            }

            override fun senden(id: String, offset: Long, puffer: ByteArray, laenge: Int) =
                client.senden(id, offset, puffer, laenge)
        }
        val start = beginnen(quelle, pfad, inhalt)
        when (val e = Stueckweise(ruf, teil, halt, fortschritt).laden(start, inhalt.groesse) { inhalt.oeffnen(it) }) {
            is Stueckweise.Ende.Fertig -> {
                merker.vergissUpload(quelle, pfad)
                Ausgang.Fertig
            }
            Stueckweise.Ende.Angehalten -> Ausgang.Angehalten
            Stueckweise.Ende.Kuerzer -> Ausgang.Veraendert
            is Stueckweise.Ende.Voll -> Ausgang.Voll(e.satz)
            is Stueckweise.Ende.Fest -> Ausgang.Fehler(e.code, null)
        }
    } catch (e: SicherungClient.Abgelehnt) {
        if (e.code == 507) Ausgang.Voll(e.satz ?: "Auf der Box ist kein Platz mehr für die Sicherung.") else Ausgang.Fehler(e.code, e.satz)
    } catch (ignored: FremderRest) {
        Ausgang.Rest
    } catch (e: IOException) {
        if (halt()) Ausgang.Angehalten else Ausgang.Fehler(0, null)
    }

    /** The upload remembered for this file, where Homes stands with it; else a new one. */
    private fun beginnen(quelle: Napi.Quelle, pfad: String, inhalt: Inhalt): Pair<String, Long> {
        val datei = inhalt.datei
        val gemerkt = merker.upload(quelle, pfad)?.let { Zeile.aus(it) }
        val umschlag = gemerkt?.umschlag?.let { Age.Umschlag.aus(it) }
        if (gemerkt != null && gemerkt.fuer(datei) && (inhalt.an == null) == (umschlag == null)) {
            client.offset(gemerkt.id)?.let {
                inhalt.umschlag = umschlag
                return gemerkt.id to it
            }
        }
        if (gemerkt != null) merker.vergissUpload(quelle, pfad)
        return anlegen(quelle, pfad, inhalt)
    }

    /** A new upload; encrypted, with a new [Age.Umschlag], kept with its id before a byte goes. */
    private fun anlegen(quelle: Napi.Quelle, pfad: String, inhalt: Inhalt): Pair<String, Long> {
        val datei = inhalt.datei
        val umschlag = inhalt.an?.let { Age.Umschlag.neu() }
        val (id, offset) = client.anlegen(quelle, pfad, inhalt.groesse, datei.geaendert)
        // Homes has part of it already, encrypted with randomness this phone no longer knows
        if (umschlag != null && offset in 1 until inhalt.groesse) throw FremderRest()
        merker.merkeUpload(quelle, pfad, Zeile(datei.groesse, datei.geaendert, id, umschlag?.text()).text())
        inhalt.umschlag = umschlag
        return id to offset
    }

    /**
     * What [Merker] keeps for a file: the upload's id, valid only for this
     * size and time, and for an encrypted one its [Age.Umschlag].
     */
    data class Zeile(val groesse: Long, val geaendert: Long, val id: String, val umschlag: String? = null) {
        fun text() = "$groesse|$geaendert|$id" + umschlag?.let { "|$it" }.orEmpty()

        /** Kept for this very file: its size and time. */
        fun fuer(d: Datei) = groesse == d.groesse && geaendert == d.geaendert

        companion object {
            fun aus(s: String): Zeile? {
                val t = s.split('|', limit = 4)
                if (t.size < 3 || t[2].isBlank()) return null
                return Zeile(t[0].toLongOrNull() ?: return null, t[1].toLongOrNull() ?: return null, t[2], t.getOrNull(3))
            }
        }
    }

    companion object {
        /** 8 MiB a PATCH: Homes takes up to 16. */
        const val TEIL = Stueckweise.TEIL

        const val REST =
            "Ein abgebrochener Teil dieser Sicherung liegt noch auf der Box. Die App schickt sie neu, sobald Homes ihn verworfen hat (spätestens nach sieben Tagen)."
    }
}
