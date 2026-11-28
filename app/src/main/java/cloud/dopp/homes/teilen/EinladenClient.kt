package cloud.dopp.homes.teilen

import android.net.Uri
import cloud.dopp.homes.data.HomesHttp
import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.Napi.EinladenJson
import cloud.dopp.homes.data.asBody
import cloud.dopp.homes.data.json
import cloud.dopp.homes.data.request
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InterruptedIOException

/**
 * „Kontaktdaten eintragen lassen“ (owner 2026-11-28): `POST
 * /napi/kontakte/einladen` answers a one-time link on Homes where the person
 * held to this phone fills in their contact. Fetched only when chosen (each
 * link is good once); Homes decides who may (403 with its sentence for anyone
 * but an admin). Blocking; call it off the main thread.
 */
class EinladenClient(
    private val base: String,
    private val token: String,
    private val http: OkHttpClient = HTTP,
) {

    sealed class Ausgang {
        /** The [link] (https), Homes' [text] around it, and until when it is good ([gueltigBis], as Homes sent it). */
        data class Link(val link: String, val text: String?, val gueltigBis: String?) : Ausgang()

        /** Refused, in Homes' sentence, or null when it sent none. */
        data class Abgelehnt(val code: Int, val satz: String?) : Ausgang()

        object NichtGekoppelt : Ausgang()
        object Zeit : Ausgang()
        object Offline : Ausgang()
    }

    fun einladen(): Ausgang = try {
        http.newCall(Napi.KONTAKT_EINLADEN.request(base, token, body = Napi.KONTAKT_EINLADEN.json().asBody())).execute()
            .use { ausgangAus(it.code, it.body?.string()) }
    } catch (ignored: InterruptedIOException) {
        Ausgang.Zeit
    } catch (ignored: IOException) {
        Ausgang.Offline
    }

    companion object {
        /** Homes makes one link; the answer comes in a moment. */
        private val HTTP: OkHttpClient by lazy { HomesHttp.client(connect = 8, read = 15, write = 8) }

        private const val HTTP_401 = 401

        /** A reply, by its status: the link, or why not. A link that is not https is no link. */
        fun ausgangAus(code: Int, body: String?): Ausgang {
            val o = HomesHttp.json(body)
            val link = HomesHttp.text(o, EinladenJson.LINK)?.takeIf { Uri.parse(it).scheme == "https" }
            return when {
                code == HTTP_401 -> Ausgang.NichtGekoppelt
                code in 200..299 && o?.optBoolean(EinladenJson.OK, false) == true && link != null ->
                    Ausgang.Link(link, HomesHttp.text(o, EinladenJson.TEXT), HomesHttp.text(o, EinladenJson.GUELTIG_BIS))
                else -> Ausgang.Abgelehnt(code, HomesHttp.text(o, EinladenJson.ERROR))
            }
        }
    }
}
