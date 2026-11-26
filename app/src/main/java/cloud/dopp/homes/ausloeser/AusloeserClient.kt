package cloud.dopp.homes.ausloeser

import cloud.dopp.homes.data.HomesHttp
import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.Napi.AusloeserJson
import cloud.dopp.homes.data.Napi.GemeldetJson
import cloud.dopp.homes.data.Napi.NeuerTagJson
import cloud.dopp.homes.data.Napi.TagsJson
import cloud.dopp.homes.data.asBody
import cloud.dopp.homes.data.json
import cloud.dopp.homes.data.request
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException

/**
 * The phone as a trigger (homes ADR 0004 §1): `POST /napi/ausloeser` for a
 * touched NFC tag or the charger, `POST /napi/ausloeser/tag` for a new tag in
 * Home Assistant, `GET /napi/ausloeser/tags` for HA's list. Homes fires the
 * event and says what it did; it decides nothing, and neither does the app.
 *
 * It does not pass [cloud.dopp.homes.data.ScreenGate]: a tag is touched and
 * written with the screen on anyway, and the charger's one request goes with
 * the screen off too — the app's second exception to „no network while the
 * screen is off“, behind its switch, the owner's yes of 2026-11-26 (ADR 0004; AGENTS.md). Blocking; call it off the main thread.
 */
class AusloeserClient(
    private val base: String,
    private val token: String,
    private val http: OkHttpClient = HTTP,
) {

    /** One of HA's tags: its id, its name there (`""` when it has none), and what it does (`""`: report to HA, §4). */
    data class Tag(val tag: String, val name: String, val aktion: String = "")

    /** An action this box offers a tag (§4): its [id] (`""` is HA's) and its German [titel]. */
    data class Aktion(val id: String, val titel: String)

    /** What a call came to. */
    sealed class Ausgang {
        /**
         * A report Homes passed on: the tag's name in HA, `""` when HA does not know it (and for the charger); a tag
         * with an action of Homes' own (§4) brings its [aktion] and [text], the sentence to show instead.
         */
        data class Gemeldet(val name: String, val aktion: String = "", val text: String? = null) : Ausgang()

        /** A tag in HA: its [tag] id, the [uri] to write onto a new one, and what it does now. */
        data class Angelegt(val tag: String, val uri: String, val aktion: String = "") : Ausgang()

        /**
         * HA's tags, in Homes' order, the names to offer for a new one (the house's rooms, a few places), and the
         * actions this box offers a tag, in Homes' order (§4).
         */
        data class Tags(
            val tags: List<Tag>,
            val vorschlaege: List<String> = emptyList(),
            val aktionen: List<Aktion> = emptyList(),
        ) : Ausgang()

        /** Removed from HA. */
        object Entfernt : Ausgang()

        /** Homes refused, in its sentence, or the app's when it sent none. */
        data class Abgelehnt(val code: Int, val satz: String) : Ausgang()

        /** 404 `not_found`: the box has no Home Assistant (module geraete off). */
        object OhneHa : Ausgang()

        /** 401: the device is not paired (any more). */
        object NichtGekoppelt : Ausgang()

        /** No answer in time. */
        object Zeit : Ausgang()

        /** Homes not reached. */
        object Offline : Ausgang()
    }

    /** An NFC tag [tag] was touched. */
    fun nfc(tag: String): Ausgang = post(Napi.AUSLOESER, Napi.AUSLOESER.json(Napi.Art.NFC.wire, tag, null, null)) { gemeldet(it) }

    /** The phone began ([an]) or stopped charging, by [ladeart]. */
    fun laden(an: Boolean, ladeart: Napi.Ladeart): Ausgang {
        val zustand = if (an) Napi.ZUSTAND_AN else Napi.ZUSTAND_AUS
        return post(Napi.AUSLOESER, Napi.AUSLOESER.json(Napi.Art.LADEN.wire, null, zustand, ladeart.wire)) { gemeldet(it) }
    }

    /**
     * A new tag named [name] in HA; with [tag], that one named or renamed, not written again (§2). [aktion]: what it
     * does (§4), `""` back to HA; null leaves it as it is.
     */
    fun benennen(name: String, tag: String? = null, aktion: String? = null): Ausgang =
        post(Napi.AUSLOESER_TAG, Napi.AUSLOESER_TAG.json(name, tag, aktion)) { o ->
            val id = HomesHttp.text(o, NeuerTagJson.TAG)
            val uri = HomesHttp.text(o, NeuerTagJson.URI)
            if (id == null || uri == null) null else Ausgang.Angelegt(id, uri, HomesHttp.text(o, NeuerTagJson.AKTION).orEmpty())
        }

    /** HA's tags. */
    fun tags(): Ausgang = rufe(Napi.AUSLOESER_TAGS.request(base, token)) { o -> tagsAus(o) }

    /** [tag] out of HA, name and id. */
    fun entfernen(tag: String): Ausgang = rufe(Napi.AUSLOESER_TAG_WEG.with(tag).request(base, token)) { Ausgang.Entfernt }

    private fun post(endpoint: Napi.Endpoint, body: JSONObject, lies: (JSONObject) -> Ausgang?): Ausgang =
        rufe(endpoint.request(base, token, body = body.asBody()), lies)

    @Suppress("SwallowedException") // the outcome is the answer: the caller says it in German
    private fun rufe(req: Request, lies: (JSONObject) -> Ausgang?): Ausgang = try {
        http.newCall(req).execute().use { resp -> ausgangAus(resp.code, resp.body?.string(), lies) }
    } catch (e: InterruptedIOException) {
        Ausgang.Zeit
    } catch (e: IOException) {
        Ausgang.Offline
    }

    companion object {
        /** Homes asks HA, which answers in a moment; a touched tag should not wait long. */
        private val HTTP: OkHttpClient by lazy { HomesHttp.client(connect = 8, read = 12, write = 8) }

        /** Said when Homes answered without a sentence of its own. */
        const val UNLESBAR = "Homes hat nicht lesbar geantwortet."

        /**
         * A reply: 401 unpaired, 404 `not_found` no HA, any other refusal Homes' `error`; a 2xx
         * `{ok: true}` read by [lies], whose null means it lacked what it must say.
         */
        fun ausgangAus(code: Int, body: String?, lies: (JSONObject) -> Ausgang?): Ausgang {
            val o = HomesHttp.json(body)
            return when {
                code == 401 -> Ausgang.NichtGekoppelt
                code == 404 && HomesHttp.text(o, AusloeserJson.REASON) == Napi.REASON_NOT_FOUND -> Ausgang.OhneHa
                code !in 200..299 || o == null || !o.optBoolean(AusloeserJson.OK, false) ->
                    Ausgang.Abgelehnt(code, HomesHttp.text(o, AusloeserJson.ERROR) ?: "$UNLESBAR ($code)")
                else -> lies(o) ?: Ausgang.Abgelehnt(code, UNLESBAR)
            }
        }

        /** A report's `{ok, name, aktion?, text?}`: the name, `""` when HA does not know the tag or none came. */
        fun gemeldet(o: JSONObject): Ausgang = Ausgang.Gemeldet(
            HomesHttp.text(o, GemeldetJson.NAME).orEmpty(),
            HomesHttp.text(o, GemeldetJson.AKTION).orEmpty(),
            HomesHttp.text(o, GemeldetJson.TEXT),
        )

        /** `{ok, tags: [{tag, name, aktion}], vorschlaege, aktionen}`; an entry without its id is left out. */
        fun tagsAus(o: JSONObject): Ausgang? {
            val a = o.optJSONArray(TagsJson.TAGS) ?: return null
            val tags = (0 until a.length()).mapNotNull { i ->
                val t = a.optJSONObject(i)
                HomesHttp.text(t, TagsJson.TAG)?.let {
                    Tag(it, HomesHttp.text(t, TagsJson.NAME).orEmpty(), HomesHttp.text(t, TagsJson.AKTION).orEmpty())
                }
            }
            val v = o.optJSONArray(TagsJson.VORSCHLAEGE)
            val vorschlaege = (0 until (v?.length() ?: 0)).mapNotNull { v?.optString(it)?.trim()?.ifBlank { null } }.distinct()
            return Ausgang.Tags(tags, vorschlaege, aktionenAus(o))
        }

        /** `aktionen: [{id, titel}]`, as Homes lists them; HA's id is `""`, an entry without a title is left out. */
        private fun aktionenAus(o: JSONObject): List<Aktion> {
            val a = o.optJSONArray(TagsJson.AKTIONEN) ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val e = a.optJSONObject(i) ?: return@mapNotNull null
                val titel = HomesHttp.text(e, TagsJson.TITEL) ?: return@mapNotNull null
                Aktion(HomesHttp.text(e, TagsJson.ID).orEmpty(), titel)
            }.distinctBy { it.id }
        }
    }
}
