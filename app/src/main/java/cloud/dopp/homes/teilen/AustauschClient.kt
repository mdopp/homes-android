package cloud.dopp.homes.teilen

import androidx.core.net.toUri
import cloud.dopp.homes.data.HomesHttp
import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.Napi.TeilenNeuJson
import cloud.dopp.homes.data.Napi.TeilenOkJson
import cloud.dopp.homes.data.Napi.TeilenStandJson
import cloud.dopp.homes.data.asBody
import cloud.dopp.homes.data.json
import cloud.dopp.homes.data.request
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException

/**
 * „Kontakt austauschen“ (owner approved 2026-11-28; homes `internal/napi/teilen.go`): a session on Homes whose
 * link the other phone opens; what its page shows is set here, all of it each time, and followed. Homes keeps it
 * in memory, 30 minutes at most. Blocking; call it off the main thread.
 */
class AustauschClient(
    private val base: String,
    private val token: String,
    private val http: OkHttpClient = HTTP,
) {

    sealed class Antwort {
        /** Started: its [id], the [link] (https) for the other phone, until when ([bis], RFC 3339). */
        data class Neu(val id: String, val link: String, val bis: String?) : Antwort()

        /** The page was opened, the form sent. */
        data class Stand(val geoeffnet: Boolean, val eingetragen: Boolean) : Antwort()

        object Ok : Antwort()

        /** 410 or 404: over, or never this person's; [satz] Homes' words. */
        data class Vorbei(val satz: String?) : Antwort()

        /** Any other refusal (403, 400, 429, 503), in Homes' sentence or null. */
        data class Abgelehnt(val code: Int, val satz: String?) : Antwort()

        object NichtGekoppelt : Antwort()
        object Zeit : Antwort()
        object Offline : Antwort()
    }

    /** A new session whose page names [vorname] (null: Homes takes the login's). */
    fun neu(vorname: String?): Antwort = rufe(Napi.TEILEN_NEU.request(base, token, body = Napi.TEILEN_NEU.json(vorname).asBody())) { o ->
        val id = HomesHttp.text(o, TeilenNeuJson.ID)
        val link = HomesHttp.text(o, TeilenNeuJson.LINK)?.takeIf { it.toUri().scheme == HTTPS }
        if (id == null || link == null) null else Antwort.Neu(id, link, HomesHttp.text(o, TeilenNeuJson.GUELTIG_BIS))
    }

    /** The page shows exactly [karte], [wlan] (null: not) and the form ([eintragen]). */
    fun setzen(id: String, vorname: String?, karte: JSONObject?, wlan: JSONObject?, eintragen: Boolean): Antwort {
        val e = Napi.TEILEN_SETZEN.with(id)
        val body = e.json(vorname.orEmpty(), karte ?: JSONObject.NULL, wlan ?: JSONObject.NULL, eintragen)
        return rufe(e.request(base, token, body = body.asBody())) { Antwort.Ok }
    }

    fun stand(id: String): Antwort = rufe(Napi.TEILEN_STAND.with(id).request(base, token)) { o ->
        Antwort.Stand(o.optBoolean(TeilenStandJson.GEOEFFNET, false), o.optBoolean(TeilenStandJson.EINGETRAGEN, false))
    }

    fun beenden(id: String): Antwort = rufe(Napi.TEILEN_ENDE.with(id).request(base, token)) { Antwort.Ok }

    private fun rufe(r: Request, gut: (JSONObject) -> Antwort?): Antwort = try {
        http.newCall(r).execute().use { antwortAus(it.code, it.body?.string(), gut) }
    } catch (ignored: InterruptedIOException) {
        Antwort.Zeit
    } catch (ignored: IOException) {
        Antwort.Offline
    }

    companion object {
        private val HTTP: OkHttpClient by lazy { HomesHttp.client(connect = 8, read = 15, write = 8) }

        private const val HTTPS = "https"
        private const val HTTP_401 = 401
        private const val HTTP_404 = 404
        private const val HTTP_410 = 410

        /** A reply, by its status: [gut] reads a 2xx `{ok: true}` (null: unreadable). */
        fun antwortAus(code: Int, body: String?, gut: (JSONObject) -> Antwort?): Antwort {
            val o = HomesHttp.json(body)
            val satz = HomesHttp.text(o, TeilenOkJson.ERROR)
            return when {
                code == HTTP_401 -> Antwort.NichtGekoppelt
                code == HTTP_404 || code == HTTP_410 -> Antwort.Vorbei(satz)
                code in 200..299 && o?.optBoolean(TeilenOkJson.OK, false) == true -> gut(o) ?: Antwort.Abgelehnt(code, null)
                else -> Antwort.Abgelehnt(code, satz)
            }
        }
    }
}
