package cloud.dopp.homes.kann

import cloud.dopp.homes.data.HomesHttp
import cloud.dopp.homes.data.Napi.AngebotJson
import cloud.dopp.homes.data.Napi.KannJson
import cloud.dopp.homes.data.Napi.ModulJson
import org.json.JSONArray
import org.json.JSONObject

/** The lists of `/napi/kann`'s `app` and of each module's `bietet` (homes ADR 0001 §2). */
enum class Liste(val key: String) {
    WIDGETS(AngebotJson.WIDGETS),
    TEILEN(AngebotJson.TEILEN),
    KONTO(AngebotJson.KONTO),
    SICHERUNG(AngebotJson.SICHERUNG),
    MEHR(AngebotJson.MEHR),
}

/** One module as Homes describes it: on or off, why not, what it would bring, and its trace (§10). */
data class Modul(
    val name: String,
    val titel: String,
    val an: Boolean,
    /** Why it is off, one German sentence; null while on. */
    val grund: String?,
    /** What it brings the app, on or off: by list, the app's keys. */
    val bietet: Map<Liste, Set<String>>,
    /** Only admins use it: its trace is on Homes' Box page, not in the app. */
    val admins: Boolean,
    /** The trace's key: fehlt, aus, or its wish's state; null while on. */
    val spurId: String?,
    /** The trace's German words, for a key this app does not know. */
    val spur: String?,
    /** The trace offers „Hier anfordern“. */
    val anfordern: Boolean,
)

/**
 * What the box can do, as `GET /napi/kann` said it last (homes ADR 0001 §3):
 * the app offers a feature while its key is in [app]'s list, and leaves a
 * quiet trace where a known key is missing (§10) — never hides it.
 *
 * Nothing is taken on a guess: an unknown key or field is ignored, and a
 * list Homes did not send says nothing, so its features stay as they are.
 * A Homes without `/napi/kann`, or none read yet, is no [Kann] at all:
 * everything is offered as before ([hat] on null). [dokumenteAdresse]: the person's own, null without one.
 */
class Kann(val module: List<Modul>, private val app: Map<Liste, Set<String>>, val dokumenteAdresse: String? = null) {

    /** Does the box offer [key] in [liste]? A list Homes did not send: yes. */
    fun hat(liste: Liste, key: String): Boolean = app[liste]?.contains(key) ?: true

    /** The off modules that leave a trace in the app: every one but the admins'. */
    val spuren: List<Modul> get() = module.filter { !it.an && !it.admins }

    /** The off module that would bring [key] in [liste], whose trace stands for it. */
    fun spurVon(liste: Liste, key: String): Modul? =
        module.firstOrNull { !it.an && it.bietet[liste]?.contains(key) == true }

    /** The keys of [liste] that off modules would bring and the box does not offer now, in order. */
    fun fehlend(liste: Liste): List<String> =
        module.filter { !it.an }.flatMap { it.bietet[liste].orEmpty() }.distinct().filter { !hat(liste, it) }

    companion object {

        /** `/napi/kann`'s answer; null when it is none (not JSON, no version). */
        fun lesen(body: String?): Kann? {
            val o = HomesHttp.json(body) ?: return null
            if (o.optInt(KannJson.VERSION, 0) < 1) return null
            val module = o.optJSONArray(KannJson.MODULE) ?: JSONArray()
            return Kann(
                (0 until module.length()).mapNotNull { modul(module.optJSONObject(it)) },
                angebot(o.optJSONObject(KannJson.APP)),
                HomesHttp.text(o, KannJson.DOKUMENTE_ADRESSE),
            )
        }

        /** One module; null without its name or whether it is on, the two things it must say. */
        fun modul(o: JSONObject?): Modul? {
            if (o == null || !o.has(ModulJson.AN)) return null
            val name = HomesHttp.text(o, ModulJson.NAME) ?: return null
            return Modul(
                name = name,
                titel = HomesHttp.text(o, ModulJson.TITEL) ?: name,
                an = o.optBoolean(ModulJson.AN, true),
                grund = HomesHttp.text(o, ModulJson.GRUND),
                bietet = angebot(o.optJSONObject(ModulJson.BIETET)),
                admins = o.optBoolean(ModulJson.ADMINS, false),
                spurId = HomesHttp.text(o, ModulJson.SPUR_ID),
                spur = HomesHttp.text(o, ModulJson.SPUR),
                anfordern = o.optBoolean(ModulJson.ANFORDERN, false),
            )
        }

        /** The lists Homes sent, each as a set of its strings. */
        private fun angebot(o: JSONObject?): Map<Liste, Set<String>> {
            if (o == null) return emptyMap()
            return Liste.values().mapNotNull { l ->
                val a = o.optJSONArray(l.key) ?: return@mapNotNull null
                l to (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }.toSet()
            }.toMap()
        }
    }
}

/** Does the box offer [key] in [liste]? Unknown (an older Homes, nothing read yet): yes, as before. */
fun Kann?.hat(liste: Liste, key: String): Boolean = this?.hat(liste, key) ?: true
