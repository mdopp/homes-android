package cloud.dopp.homes.sprachmodell

import cloud.dopp.homes.data.HomesHttp
import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.Napi.SprachmodellJson
import cloud.dopp.homes.data.asBody
import cloud.dopp.homes.data.json
import cloud.dopp.homes.data.request
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException

/** One duration's button of a preset, and the question before it: all Homes' words. */
data class Dauer(val stunden: Int, val label: String, val frage: String, val ja: String, val nein: String)

/** One preset the person may switch to, with a button per duration. */
data class Wahl(val preset: String, val name: String, val dauern: List<Dauer>)

/**
 * The language model's card (owner 2026-11-17), as Homes' web page says it
 * (`sprachmodell.Ansicht`): [zeile] the model and for whom, [kosten] what the
 * window costs (a warning, "" in the household), else [detail]; [zurueck]
 * the give-back button's label ("" = none); [zustand] one of haushalt,
 * anderes, laedt, belegt, weg.
 */
data class Karte(
    val zustand: String,
    val titel: String,
    val hinweis: String,
    val zeile: String,
    val detail: String,
    val kosten: String,
    val zurueck: String,
    val wahl: List<Wahl>,
) {
    companion object {
        /** `{ok, karte}` as a card; null when the body carries none. */
        fun lesen(body: String?): Karte? {
            val o = HomesHttp.json(body)?.optJSONObject(SprachmodellJson.KARTE) ?: return null
            val zeile = HomesHttp.text(o, SprachmodellJson.ZEILE) ?: return null
            return Karte(
                zustand = wort(o, SprachmodellJson.ZUSTAND),
                titel = wort(o, SprachmodellJson.TITEL),
                hinweis = wort(o, SprachmodellJson.HINWEIS),
                zeile = zeile,
                detail = wort(o, SprachmodellJson.DETAIL),
                kosten = wort(o, SprachmodellJson.KOSTEN),
                zurueck = wort(o, SprachmodellJson.ZURUECK),
                wahl = liste(o.optJSONArray(SprachmodellJson.WAHL)).mapNotNull(::wahl),
            )
        }

        private fun wort(o: JSONObject, key: String) = HomesHttp.text(o, key).orEmpty()

        private fun liste(a: JSONArray?): List<JSONObject> = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }

        private fun wahl(o: JSONObject): Wahl? {
            val preset = HomesHttp.text(o, SprachmodellJson.PRESET) ?: return null
            val dauern = liste(o.optJSONArray(SprachmodellJson.DAUERN)).mapNotNull { d ->
                val stunden = d.optInt(SprachmodellJson.STUNDEN, 0)
                val label = HomesHttp.text(d, SprachmodellJson.LABEL)
                val frage = HomesHttp.text(d, SprachmodellJson.FRAGE)
                if (stunden <= 0 || label == null || frage == null) {
                    null
                } else {
                    Dauer(stunden, label, frage, wort(d, SprachmodellJson.JA), wort(d, SprachmodellJson.NEIN))
                }
            }
            return Wahl(preset, HomesHttp.text(o, SprachmodellJson.NAME) ?: preset, dauern).takeIf { dauern.isNotEmpty() }
        }
    }
}

/**
 * `GET|POST /napi/sprachmodell` (owner 2026-11-17) with the device token.
 * Blocking; call it off the main thread.
 */
class SprachmodellClient(
    private val base: String,
    private val token: String,
    private val http: OkHttpClient = HTTP,
) {

    /** What a read came to. */
    sealed class Lesen {
        data class Da(val karte: Karte) : Lesen()

        /** 404: the box has no switchable model (or an older Homes): no card. */
        object Keins : Lesen()

        /** Anything else, or no answer: the card shown stays. */
        object Nichts : Lesen()
    }

    /** What a switch came to. */
    sealed class Ergebnis {
        /** Done: the new state, to draw. */
        data class Neu(val karte: Karte) : Ergebnis()

        /** Refused: Homes' sentence, when it sent one. */
        data class Abgelehnt(val satz: String?) : Ergebnis()

        /** No answer in time: it may have switched all the same. */
        object Zeit : Ergebnis()

        /** No connection to Homes. */
        object Offline : Ergebnis()
    }

    // No answer is an answer here: the card shown stays.
    @Suppress("SwallowedException")
    fun lesen(): Lesen = try {
        http.newCall(Napi.SPRACHMODELL.request(base, token)).execute().use { resp -> gelesen(resp.code, resp.body?.string()) }
    } catch (e: IOException) {
        Lesen.Nichts
    }

    private fun gelesen(code: Int, body: String?): Lesen = when {
        code == 404 -> Lesen.Keins
        code in 200..299 -> Karte.lesen(body)?.let { Lesen.Da(it) } ?: Lesen.Nichts
        else -> Lesen.Nichts
    }

    /** Switch to [preset] for [stunden] hours. */
    fun schalten(preset: String, stunden: Int): Ergebnis = senden(Napi.SPRACHMODELL_SCHALTEN.json(preset, stunden, null))

    /** Give the model back to the household. */
    fun zurueck(): Ergebnis = senden(Napi.SPRACHMODELL_SCHALTEN.json(null, null, true))

    @Suppress("SwallowedException") // as [lesen]: the outcome is the answer
    private fun senden(body: JSONObject): Ergebnis = try {
        val req = Napi.SPRACHMODELL_SCHALTEN.request(base, token, body = body.asBody())
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string()
            val karte = if (resp.isSuccessful) Karte.lesen(text) else null
            karte?.let { Ergebnis.Neu(it) } ?: Ergebnis.Abgelehnt(HomesHttp.satz(text, Napi.SchaltenJson.ERROR))
        }
    } catch (e: InterruptedIOException) {
        Ergebnis.Zeit
    } catch (e: IOException) {
        Ergebnis.Offline
    }

    companion object {
        /** A switch asks the model's lease up to three times, 8 s each: the read waits longer than that. */
        private val HTTP: OkHttpClient by lazy { HomesHttp.client(connect = 10, read = 40) }
    }
}
