package cloud.dopp.homes.fragen

import cloud.dopp.homes.data.HomesHttp
import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.Napi.FragenJson
import cloud.dopp.homes.data.asBody
import cloud.dopp.homes.data.json
import cloud.dopp.homes.data.request
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Daten erfragen (owner 2026-11-28): `POST /napi/kontakte/fragen` with what a
 * shared card says; Homes looks for the contact in the household's address
 * book and answers the link its person fills in, with the words to send.
 * Homes decides what is missing and what the message says; the app only
 * hands it on. Blocking; call it off the main thread.
 */
class FragenClient(
    private val base: String,
    private val token: String,
    private val http: OkHttpClient = HTTP,
) {

    /** One contact that fits, as Homes names it, and its UID to ask again with. */
    data class Kandidat(val kontakt: String, val uid: String)

    sealed class Ausgang {
        /**
         * The contact and the link: [vorname] whom to address, [text] the message with the link, [fehlt] what is
         * missing in Homes' words, [telefon] the number to send to, [neu] made just now.
         */
        data class Gefunden(
            val kontakt: String,
            val vorname: String,
            val text: String,
            val fehlt: List<String>,
            val telefon: String? = null,
            val neu: Boolean = false,
        ) : Ausgang()

        /** 404 with `anlegen`: not in the household's address book; [satz] Homes' sentence. */
        data class NichtDa(val satz: String) : Ausgang()

        /** 409: more than one fits. */
        data class Mehrere(val kandidaten: List<Kandidat>, val satz: String?) : Ausgang()

        /** Any other refusal, in Homes' sentence or the app's when it sent none. */
        data class Abgelehnt(val code: Int, val satz: String?) : Ausgang()

        object NichtGekoppelt : Ausgang()
        object Zeit : Ausgang()
        object Offline : Ausgang()
    }

    /** Ask for [karte]'s contact; [anlegen] makes it first, [uid] names the one picked. */
    fun fragen(karte: GeteilteKarte.Karte, anlegen: Boolean = false, uid: String? = null): Ausgang {
        val body = Napi.KONTAKT_FRAGEN.json(
            karte.name, JSONArray(karte.telefon), JSONArray(karte.mail), if (anlegen) true else null, uid,
        )
        return try {
            http.newCall(Napi.KONTAKT_FRAGEN.request(base, token, body = body.asBody())).execute()
                .use { ausgangAus(it.code, it.body?.string()) }
        } catch (ignored: InterruptedIOException) {
            Ausgang.Zeit
        } catch (ignored: IOException) {
            Ausgang.Offline
        }
    }

    companion object {
        /** Homes reads one address book; the answer comes in a moment. */
        private val HTTP: OkHttpClient by lazy { HomesHttp.client(connect = 8, read = 20, write = 8) }

        private const val HTTP_401 = 401
        private const val HTTP_404 = 404
        private const val HTTP_409 = 409

        /** A reply, by its status: what to show. */
        fun ausgangAus(code: Int, body: String?): Ausgang {
            val o = HomesHttp.json(body)
            val satz = HomesHttp.text(o, FragenJson.ERROR)
            return when {
                code == HTTP_401 -> Ausgang.NichtGekoppelt
                code == HTTP_404 && o?.optBoolean(FragenJson.ANLEGEN, false) == true && satz != null -> Ausgang.NichtDa(satz)
                code == HTTP_409 -> kandidaten(o)?.let { Ausgang.Mehrere(it, satz) } ?: Ausgang.Abgelehnt(code, satz)
                code in 200..299 && o?.optBoolean(FragenJson.OK, false) == true -> gefunden(o) ?: Ausgang.Abgelehnt(code, null)
                else -> Ausgang.Abgelehnt(code, satz)
            }
        }

        /** `{kontakt, vorname, link, text, fehlt, telefon?, neu?}`; null without a text or link to send. */
        private fun gefunden(o: JSONObject): Ausgang.Gefunden? {
            val text = HomesHttp.text(o, FragenJson.TEXT) ?: HomesHttp.text(o, FragenJson.LINK) ?: return null
            val kontakt = HomesHttp.text(o, FragenJson.KONTAKT).orEmpty()
            val f = o.optJSONArray(FragenJson.FEHLT)
            return Ausgang.Gefunden(
                kontakt = kontakt,
                vorname = HomesHttp.text(o, FragenJson.VORNAME) ?: kontakt,
                text = text,
                fehlt = (0 until (f?.length() ?: 0)).mapNotNull { f?.optString(it)?.trim()?.ifEmpty { null } },
                telefon = HomesHttp.text(o, FragenJson.TELEFON),
                neu = o.optBoolean(FragenJson.NEU, false),
            )
        }

        /** `kandidaten: [{kontakt, uid}]`; an entry without its uid is left out, none at all is null. */
        private fun kandidaten(o: JSONObject?): List<Kandidat>? {
            val a = o?.optJSONArray(FragenJson.KANDIDATEN) ?: return null
            return (0 until a.length()).mapNotNull { i ->
                val k = a.optJSONObject(i)
                val uid = HomesHttp.text(k, FragenJson.UID) ?: return@mapNotNull null
                Kandidat(HomesHttp.text(k, FragenJson.KONTAKT) ?: uid, uid)
            }.ifEmpty { null }
        }
    }
}
