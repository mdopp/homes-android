package cloud.dopp.homes.data

import cloud.dopp.homes.data.Napi.DateiJson
import cloud.dopp.homes.data.Napi.FehlendJson
import cloud.dopp.homes.data.Napi.KontakteJson
import cloud.dopp.homes.data.Napi.KorrekturenJson
import cloud.dopp.homes.data.Napi.LetzteJson
import cloud.dopp.homes.data.Napi.SchluesselJson
import cloud.dopp.homes.data.Napi.StandJson
import cloud.dopp.homes.data.Napi.TeilJson
import cloud.dopp.homes.data.Napi.UploadJson
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Daten sichern (#194, laib ADR 0029): the calls that bring the phone's
 * WhatsApp and Signal backups to Homes, against the contract Homes builds in
 * homes#207 — what reached the box, which media are missing, and a resumable
 * upload (create, HEAD for the offset, PATCH the next bytes).
 *
 * The one client that does **not** pass [ScreenGate]: the daily backup runs
 * while the phone charges, mostly with the screen off, and only on an
 * unmetered network (the owner's call in #194; docs/CONTRACT.md (e)). The
 * widgets' calls keep the gate.
 *
 * Blocking; call it off the main thread. A transport failure is an
 * [IOException]; an answer the caller cannot go on from is [Abgelehnt], with
 * Homes' German sentence when it sent one.
 */
class SicherungClient(
    private val base: String,
    private val token: String,
    private val http: OkHttpClient = HTTP,
) {

    /**
     * Homes answered [code], with its sentence [satz] (`{"fehler": …}`) when it
     * sent one. A 401 comes from the shared login check as `{"error":"unauthorized"}`:
     * no sentence, the app says "pair again" itself.
     */
    class Abgelehnt(val code: Int, val satz: String?) : IOException("Homes answered $code")

    /**
     * One accepted correction of the person's own contact [uid] (the lookup
     * key „Kontakte übertragen“ sent), named [name] as the link showed it.
     */
    data class Korrektur(val id: String, val uid: String, val name: String, val aenderungen: List<Aenderung>) {
        /**
         * One field: [art] vorname, nachname, telefon, mail, adresse,
         * geburtstag, firma or position; [vorher] "" is new, [nachher] "" taken
         * away; a number's kind mobil, privat or geschaeftlich.
         */
        data class Aenderung(
            val nr: Int,
            val art: String,
            val vorher: String,
            val nachher: String,
            val vorherArt: String = "",
            val nachherArt: String = "",
            val vorherAdresse: Anschrift? = null,
            val nachherAdresse: Anschrift? = null,
        )

        /** A postal address as the page asks for it. */
        data class Anschrift(val strasse: String, val plz: String, val ort: String, val land: String)
    }

    /** One source as Homes reports it: when the last file arrived (RFC 3339), how many, how big, how many wait. */
    data class QuelleStand(val zuletzt: String?, val dateien: Long, val bytes: Long, val wartend: Long)

    /** What `GET /napi/sicherung/stand` came to. */
    sealed class Stand {
        data class Da(val quellen: Map<Napi.Quelle, QuelleStand>, val freiBytes: Long?) : Stand()

        /** Homes' own sentence why not, e.g. the box has no archive yet (503). */
        data class Fehler(val satz: String) : Stand()
        object NichtGekoppelt : Stand()
        object Unerreichbar : Stand()
    }

    /** How a PATCH came out. */
    sealed class Teil {
        /** Taken: Homes now holds [offset] bytes; [fertig] once the file is whole and in the inbox. */
        data class Weiter(val offset: Long, val fertig: Boolean) : Teil()

        /** 409: Homes holds [offset] bytes, not what the PATCH said; go on from there. */
        data class Versatz(val offset: Long) : Teil()

        /** 404: Homes does not know this upload (any more); start a new one. */
        object Unbekannt : Teil()

        /** 507: the box is full; [satz] says so. */
        data class Voll(val satz: String) : Teil()
    }

    /** What Homes' mirror of the contacts came to: all it holds now, and what changed. */
    data class Kontakte(val kontakte: Int, val neu: Int, val geaendert: Int, val entfernt: Int)

    /** One file the box keeps for a new phone (homes#209): its path in the source, size, mtime (epoch ms). */
    data class LetzteDatei(val pfad: String, val groesse: Long, val geaendert: Long)

    /** What `GET /napi/sicherung/letzte` came to. */
    sealed class Letzte {
        data class Da(val quellen: Map<Napi.Quelle, List<LetzteDatei>>) : Letzte()

        /** Homes' sentence why not, e.g. the box has no archive (503). */
        data class Fehler(val satz: String) : Letzte()
        object NichtGekoppelt : Letzte()
        object Unerreichbar : Letzte()
    }

    /**
     * A download under way: [ein] are the file's bytes from [start] on (where
     * the phone asked, or 0 when Homes sent it whole), of the file [etag] names.
     */
    class Strom(val start: Long, val etag: String?, val ein: InputStream)

    @Volatile
    private var laufend: Call? = null

    /** Cut the request in flight, if any: the worker was stopped. */
    fun abbrechen() {
        laufend?.cancel()
    }

    private fun <T> rufe(req: Request, lies: (Response) -> T): T {
        val call = http.newCall(req)
        laufend = call
        try {
            return call.execute().use(lies)
        } finally {
            laufend = null
        }
    }

    private fun json(endpoint: Napi.Endpoint, body: JSONObject): Request =
        endpoint.request(base, token, body = body.asBody())

    /** What reached the box, per source. Never throws. */
    fun stand(): Stand = try {
        rufe(Napi.SICHERUNG_STAND.request(base, token)) { resp -> standAus(resp.code, resp.body?.string()) }
    } catch (e: IOException) {
        Stand.Unerreichbar
    }

    /**
     * Which of [dateien] (path, size) Homes does not have yet, at most
     * [Napi.FEHLEND_MAX] at a time. Only paths that were asked about come back.
     */
    fun fehlend(quelle: Napi.Quelle, dateien: List<Pair<String, Long>>): Set<String> {
        require(dateien.size <= Napi.FEHLEND_MAX) { "at most ${Napi.FEHLEND_MAX} files a call" }
        // One dot-name anywhere in one path makes Homes refuse the whole question (400).
        require(dateien.none { (pfad, _) -> pfad.split('/').any { it.startsWith(".") } }) { "a dot-name in a path" }
        val liste = JSONArray()
        dateien.forEach { (pfad, groesse) -> liste.put(JSONObject().put(DateiJson.PFAD, pfad).put(DateiJson.GROESSE, groesse)) }
        val gefragt = dateien.map { it.first }.toSet()
        return rufe(json(Napi.SICHERUNG_FEHLEND, Napi.SICHERUNG_FEHLEND.json(quelle.wire, liste))) { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful) throw Abgelehnt(resp.code, satzAus(body))
            val arr = HomesHttp.json(body)?.optJSONArray(FehlendJson.FEHLEND)
                ?: throw Abgelehnt(resp.code, null)
            (0 until arr.length()).map { arr.optString(it) }.filter { it in gefragt }.toSet()
        }
    }

    /** Start the upload of [pfad] ([groesse] bytes, changed at [geaendert]), or resume Homes' partial one: its id and offset. */
    fun anlegen(quelle: Napi.Quelle, pfad: String, groesse: Long, geaendert: Long): Pair<String, Long> {
        val body = Napi.SICHERUNG_UPLOADS.json(quelle.wire, pfad, groesse, geaendert)
        return rufe(json(Napi.SICHERUNG_UPLOADS, body)) { resp ->
            val text = resp.body?.string()
            if (!resp.isSuccessful) throw Abgelehnt(resp.code, satzAus(text))
            val o = HomesHttp.json(text) ?: throw Abgelehnt(resp.code, null)
            val id = o.optString(UploadJson.ID)
            val offset = o.optLong(UploadJson.OFFSET, -1)
            if (id.isBlank() || offset < 0) throw Abgelehnt(resp.code, null)
            id to offset
        }
    }

    /** How many bytes of upload [id] Homes holds; null when it does not know the upload (404). */
    fun offset(id: String): Long? {
        val endpoint = Napi.SICHERUNG_UPLOAD_STAND.with(id)
        return rufe(endpoint.request(base, token, endpoint.path)) { resp ->
            if (resp.code == 404) return@rufe null
            if (!resp.isSuccessful) throw Abgelehnt(resp.code, null)
            resp.replyHeader(endpoint, Napi.UPLOAD_OFFSET)?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
                ?: throw Abgelehnt(resp.code, null)
        }
    }

    /** Send [laenge] bytes of [puffer] as upload [id]'s bytes from [offset] on. */
    fun senden(id: String, offset: Long, puffer: ByteArray, laenge: Int): Teil {
        val endpoint = Napi.SICHERUNG_UPLOAD_TEIL.with(id)
        val body = puffer.toRequestBody(OFFSET_STREAM, 0, laenge)
        val req = endpoint.request(base, token, endpoint.path, body, mapOf(Napi.UPLOAD_OFFSET to offset.toString()))
        return rufe(req) { resp -> teilAus(resp.code, resp.body?.string()) }
    }

    /**
     * The person's archive key, an age X25519 recipient (`age1…`). Homes'
     * 404 says in its sentence why there is none; that is [Abgelehnt].
     */
    fun schluessel(): String = rufe(Napi.SICHERUNG_SCHLUESSEL.request(base, token)) { resp ->
        val text = resp.body?.string()
        if (!resp.isSuccessful) throw Abgelehnt(resp.code, satzAus(text))
        HomesHttp.text(HomesHttp.json(text), SchluesselJson.EMPFAENGER)
            ?: throw Abgelehnt(resp.code, null)
    }

    /** What the box keeps for a new phone, per source. Never throws. */
    fun letzte(): Letzte = try {
        rufe(Napi.SICHERUNG_LETZTE.request(base, token)) { resp -> letzteAus(resp.code, resp.body?.string()) }
    } catch (e: IOException) {
        Letzte.Unerreichbar
    }

    /**
     * [pfad] of [quelle] from byte [ab] on, handed to [lies] as it streams.
     * A refusal is [Abgelehnt] with Homes' sentence (404: gone from the box;
     * 423: the archive is closed, for a WhatsApp medium); a cut is an [IOException].
     */
    fun <T> holen(quelle: Napi.Quelle, pfad: String, ab: Long, lies: (Strom) -> T): T {
        val endpoint = Napi.SICHERUNG_LETZTE_DATEI.with(quelle.wire, pfad)
        val req = endpoint.request(base, token, endpoint.path, headers = mapOf(Napi.RANGE to "bytes=$ab-"))
        return rufe(req) { resp ->
            val body = resp.body ?: throw Abgelehnt(resp.code, null)
            val start = when (resp.code) {
                200 -> 0L
                206 -> startAus(resp.replyHeader(endpoint, Napi.CONTENT_RANGE)) ?: throw Abgelehnt(resp.code, null)
                else -> throw Abgelehnt(resp.code, satzAus(body.string()))
            }
            lies(Strom(start, resp.replyHeader(endpoint, Napi.ETAG), body.byteStream()))
        }
    }

    /** [datei] (the contacts as concatenated vCards) to the person's address book „Handy-Kontakte“ on the box. */
    fun kontakte(datei: File): Kontakte {
        val req = Napi.KONTAKTE.request(base, token, body = datei.asRequestBody(VCARD))
        return rufe(req) { resp -> kontakteAus(resp.code, resp.body?.string()) }
    }

    /**
     * The accepted corrections of the person's own contacts (Kontakt prüfen
     * lassen, owner 2026-11-07); throws [Abgelehnt] or an IOException.
     */
    fun korrekturen(): List<Korrektur> =
        rufe(Napi.KORREKTUREN.request(base, token)) { resp -> korrekturenAus(resp.code, resp.body?.string()) }

    /** What the phone wrote of correction [id]: the numbers in [geschrieben]; a 404 (nothing waits) is no error. */
    fun erledigt(id: String, geschrieben: List<Int>) {
        val e = Napi.KORREKTUR_ERLEDIGT.with(id)
        rufe(json(e, e.json(JSONArray(geschrieben)))) { resp ->
            if (resp.code !in 200..299 && resp.code != 404) throw Abgelehnt(resp.code, satzAus(resp.body?.string()))
        }
    }

    companion object {
        private val VCARD = Napi.VCARD.toMediaType()

        /** A `kontakte` reply: the counts, or [Abgelehnt] with Homes' sentence. */
        fun kontakteAus(code: Int, body: String?): Kontakte {
            if (code !in 200..299) throw Abgelehnt(code, satzAus(body))
            val o = HomesHttp.json(body) ?: throw Abgelehnt(code, null)
            if (!o.has(KontakteJson.KONTAKTE)) throw Abgelehnt(code, null)
            return Kontakte(
                o.optInt(KontakteJson.KONTAKTE),
                o.optInt(KontakteJson.NEU),
                o.optInt(KontakteJson.GEAENDERT),
                o.optInt(KontakteJson.ENTFERNT),
            )
        }

        /** A `korrekturen` reply: each correction with its changes; a malformed change or one without a UID left out. */
        fun korrekturenAus(code: Int, body: String?): List<Korrektur> {
            if (code !in 200..299) throw Abgelehnt(code, satzAus(body))
            val list = HomesHttp.json(body)?.optJSONArray(KorrekturenJson.KORREKTUREN) ?: throw Abgelehnt(code, null)
            return (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let(::korrekturAus) }
        }

        private fun korrekturAus(o: JSONObject): Korrektur? {
            val id = o.optString(KorrekturenJson.ID).ifBlank { return null }
            val uid = o.optString(KorrekturenJson.UID).ifBlank { return null }
            val aenderungen = o.optJSONArray(KorrekturenJson.AENDERUNGEN) ?: return null
            val liste = (0 until aenderungen.length()).mapNotNull { i ->
                val a = aenderungen.optJSONObject(i) ?: return@mapNotNull null
                if (!a.has(KorrekturenJson.NR)) return@mapNotNull null
                Korrektur.Aenderung(
                    nr = a.optInt(KorrekturenJson.NR), art = a.optString(KorrekturenJson.ART),
                    vorher = a.optString(KorrekturenJson.VORHER), nachher = a.optString(KorrekturenJson.NACHHER),
                    vorherArt = a.optString(KorrekturenJson.VORHER_ART), nachherArt = a.optString(KorrekturenJson.NACHHER_ART),
                    vorherAdresse = adresseAus(a.optJSONObject(KorrekturenJson.VORHER_ADRESSE)),
                    nachherAdresse = adresseAus(a.optJSONObject(KorrekturenJson.NACHHER_ADRESSE)),
                )
            }
            return Korrektur(id, uid, o.optString(KorrekturenJson.NAME), liste)
        }

        private fun adresseAus(o: JSONObject?): Korrektur.Anschrift? = o?.let {
            Korrektur.Anschrift(
                it.optString(KorrekturenJson.STRASSE), it.optString(KorrekturenJson.PLZ),
                it.optString(KorrekturenJson.ORT), it.optString(KorrekturenJson.LAND),
            )
        }

        /** One client for the backups: a PATCH is up to 8 MiB, and Homes writes it to disk before it answers. */
        private val HTTP: OkHttpClient by lazy {
            HomesHttp.client(connect = 15, read = 60, write = 60)
        }

        private val OFFSET_STREAM = Napi.OFFSET_STREAM.toMediaType()

        /** Homes' sentence in an error body, `{"fehler": …}`, or null. */
        fun satzAus(body: String?): String? =
            HomesHttp.satz(body, UploadJson.FEHLER)

        /** Where a 206's `Content-Range: bytes <start>-<end>/<total>` starts, or null. */
        fun startAus(contentRange: String?): Long? =
            Regex("""bytes (\d+)-\d+/(\d+|\*)""").matchEntire(contentRange?.trim().orEmpty())?.groupValues?.get(1)?.toLongOrNull()

        /** A `letzte` reply: per source the files, a malformed entry left out. */
        fun letzteAus(code: Int, body: String?): Letzte {
            if (code == 401) return Letzte.NichtGekoppelt
            if (code !in 200..299) {
                return Letzte.Fehler(satzAus(body) ?: "Homes hat nicht gesagt, was zum Zurückholen bereitliegt ($code).")
            }
            val quellen = HomesHttp.json(body)?.optJSONObject(LetzteJson.QUELLEN)
                ?: return Letzte.Fehler("Homes hat nicht lesbar gesagt, was zum Zurückholen bereitliegt.")
            val out = LinkedHashMap<Napi.Quelle, List<LetzteDatei>>()
            Napi.Quelle.entries.forEach { q ->
                val arr = quellen.optJSONArray(q.wire) ?: return@forEach
                out[q] = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val pfad = o.optString(LetzteJson.PFAD)
                    val groesse = o.optLong(LetzteJson.GROESSE, -1)
                    if (pfad.isBlank() || groesse < 0) null else LetzteDatei(pfad, groesse, o.optLong(LetzteJson.GEAENDERT, 0))
                }
            }
            return Letzte.Da(out)
        }

        /** A `stand` reply as the screen shows it. */
        fun standAus(code: Int, body: String?): Stand {
            if (code == 401) return Stand.NichtGekoppelt
            if (code !in 200..299) {
                return Stand.Fehler(satzAus(body) ?: "Homes hat den Stand der Sicherungen nicht gemeldet ($code).")
            }
            val o = HomesHttp.json(body)
                ?: return Stand.Fehler("Homes hat den Stand der Sicherungen nicht lesbar gemeldet.")
            val quellen = o.optJSONObject(StandJson.QUELLEN)
            val stand = LinkedHashMap<Napi.Quelle, QuelleStand>()
            Napi.Quelle.entries.forEach { q ->
                val s = quellen?.optJSONObject(q.wire) ?: return@forEach
                stand[q] = QuelleStand(
                    zuletzt = if (s.isNull(StandJson.ZULETZT)) null else s.optString(StandJson.ZULETZT).ifBlank { null },
                    dateien = s.optLong(StandJson.DATEIEN),
                    bytes = s.optLong(StandJson.BYTES),
                    wartend = s.optLong(StandJson.WARTEND),
                )
            }
            val frei = if (o.has(StandJson.FREI_BYTES)) o.optLong(StandJson.FREI_BYTES, -1).takeIf { it >= 0 } else null
            return Stand.Da(stand, frei)
        }

        /**
         * A PATCH reply. A refusal after a partial write (409, and 408 when the
         * connection was cut while Homes wrote) carries Homes' offset: go on from
         * there. 507 stops; anything else is [Abgelehnt].
         */
        fun teilAus(code: Int, body: String?): Teil {
            val o = HomesHttp.json(body)
            val offset = o?.optLong(TeilJson.OFFSET, -1) ?: -1
            return when (code) {
                200 -> {
                    if (offset < 0) throw Abgelehnt(code, null)
                    Teil.Weiter(offset, o?.optBoolean(TeilJson.FERTIG) ?: false)
                }
                409, 408 -> {
                    if (offset < 0) throw Abgelehnt(code, satzAus(body))
                    Teil.Versatz(offset)
                }
                404 -> Teil.Unbekannt
                507 -> Teil.Voll(satzAus(body) ?: "Auf der Box ist kein Platz mehr für die Sicherung.")
                else -> throw Abgelehnt(code, satzAus(body))
            }
        }
    }
}
