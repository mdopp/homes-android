package cloud.dopp.homes.einrichtung

import cloud.dopp.homes.data.HomesHttp
import cloud.dopp.homes.data.Napi.EinrichtungJson
import cloud.dopp.homes.data.Napi.EinrichtungModulJson
import cloud.dopp.homes.data.Napi.KannJson
import org.json.JSONObject

/** A module's own phone app: its name, its Android package, where to get it. */
data class App(val name: String, val paket: String, val store: String?)

/** The person's setup task for a module with an app; [einrichten] is Homes' page with its steps. */
data class Aufgabe(val titel: String, val erledigt: Boolean, val einrichten: String?, val dringend: Boolean = false)

/** An area of the start screen as Homes says it (homes-android#290): its line, what is new in it in this release. */
data class Bereich(val stand: String?, val neu: String?)

/** One module that is on, as `/napi/einrichtung` names it. */
data class Modul(
    val name: String,
    val titel: String,
    /** The app's key for its picture ([Bilder]); an unknown one gets the default. */
    val icon: String,
    /** Its page in Homes or its service's address, https only; null when it has none. */
    val web: String?,
    /** What the row's tap opens instead of [web] (`ziel`, homes#310: the timeline itself), https only; null: [web]. */
    val ziel: String?,
    /** One line under the title (`stand`, homes#310: „Zuletzt aufgezeichnet heute 08:40“); null when Homes says none. */
    val stand: String?,
    /** Homes lists it under „Öffnen“ (`oeffnen`, homes#300); false when the answer does not say. */
    val oeffnen: Boolean,
    /** Its own phone app; null for a module that is only on the web. */
    val app: App?,
    val aufgabe: Aufgabe?,
    /** What is new in it in this release (`neu`), one line; null: nothing. */
    val neu: String? = null,
) {
    /** What the row's tap opens: [ziel] where Homes names one, else [web]. */
    val tippen: String? get() = ziel ?: web
}

/**
 * The person's modules and setup tasks (`GET /napi/einrichtung`, owner
 * 2026-11-04): what the start screen offers to open, the tasks still open,
 * and where the launcher's shortcuts lead. Nothing is taken on a guess: an
 * unknown field is ignored, an address that is not https is none.
 */
class Einrichtung(
    val offen: Int,
    val module: List<Modul>,
    val ziele: Map<String, String>,
    /** The start screen's areas by key (`bereiche`): their line and what is new; empty from an older Homes. */
    val bereiche: Map<String, Bereich> = emptyMap(),
) {

    /** Some open task is urgent: something waits for it (homes-android#290). */
    val dringend: Boolean get() = aufgaben.any { it.aufgabe?.dringend == true }

    /**
     * „Öffnen“ (owner, 2026-11-07: from the server): the modules Homes marks `oeffnen`, in the
     * answer's order, each with an address. No list of the app's own any more (2026-11-08):
     * every Homes marks them.
     */
    val oeffnen: List<Modul> get() = module.filter { it.oeffnen && it.web != null }

    /** The open setup tasks: a done one leaves the list, and then the phone's own app icon is the way in. */
    val aufgaben: List<Modul> get() = module.filter { it.aufgabe?.erledigt == false }

    /** The packages of the modules' apps, the ones the phone may tell Homes it has. */
    val pakete: Set<String> get() = module.mapNotNull { it.app?.paket }.toSet()

    /**
     * What to tell Homes the phone has, by [hat]: every module app installed,
     * sorted; empty while none of them is an open task's (nothing to tick, no request).
     */
    fun installiert(hat: (String) -> Boolean): List<String> {
        val da = pakete.filter(hat).sorted()
        return if (aufgaben.any { it.app?.paket in da }) da else emptyList()
    }

    companion object {
        /** The launcher's targets the app knows: Einkaufsliste, Zeitleiste, Dokument scannen. */
        private val ZIELE = listOf(EinrichtungJson.EINKAUF, EinrichtungJson.ZEITLEISTE, EinrichtungJson.SCANNEN)

        /** An answer; null when it is none (not JSON, no version 1). */
        fun lesen(body: String?): Einrichtung? {
            val o = HomesHttp.json(body) ?: return null
            if (o.optInt(KannJson.VERSION, 0) < 1) return null
            val module = o.optJSONArray(KannJson.MODULE)
            val roh = (0 until (module?.length() ?: 0)).mapNotNull { module?.optJSONObject(it) }
            val ziele = o.optJSONObject(EinrichtungJson.ZIELE)
            return Einrichtung(
                offen = o.optInt(EinrichtungJson.OFFEN, 0).coerceAtLeast(0),
                module = roh.mapNotNull(::modul),
                ziele = ZIELE.mapNotNull { k -> https(HomesHttp.text(ziele, k))?.let { k to it } }.toMap(),
                bereiche = bereicheAus(o.optJSONObject(EinrichtungJson.BEREICHE)),
            )
        }

        /** `bereiche: {key: {stand?, neu?}}`; an entry with neither is left out. */
        private fun bereicheAus(o: JSONObject?): Map<String, Bereich> {
            o ?: return emptyMap()
            return o.keys().asSequence().mapNotNull { k ->
                val b = o.optJSONObject(k)
                Bereich(HomesHttp.text(b, EinrichtungJson.STAND), HomesHttp.text(b, EinrichtungJson.NEU))
                    .takeIf { it.stand != null || it.neu != null }?.let { k to it }
            }.toMap()
        }

        private fun modul(o: JSONObject?): Modul? {
            val name = HomesHttp.text(o, EinrichtungModulJson.NAME) ?: return null
            val app = o?.optJSONObject(KannJson.APP)
            val paket = HomesHttp.text(app, EinrichtungModulJson.PAKET)
            val a = o?.optJSONObject(EinrichtungModulJson.AUFGABE)
            return Modul(
                name = name,
                titel = HomesHttp.text(o, EinrichtungModulJson.TITEL) ?: name,
                icon = HomesHttp.text(o, EinrichtungModulJson.ICON) ?: name,
                web = https(HomesHttp.text(o, EinrichtungModulJson.WEB)),
                ziel = https(HomesHttp.text(o, EinrichtungModulJson.ZIEL)),
                stand = HomesHttp.text(o, EinrichtungModulJson.STAND)?.trim()?.takeIf { it.isNotEmpty() },
                oeffnen = o?.optBoolean(EinrichtungModulJson.OEFFNEN, false) == true,
                app = if (o?.optBoolean(EinrichtungModulJson.NUR_WEB, false) == true || paket == null) {
                    null
                } else {
                    App(HomesHttp.text(app, EinrichtungModulJson.NAME) ?: paket, paket, https(HomesHttp.text(app, EinrichtungModulJson.STORE)))
                },
                aufgabe = a?.let {
                    Aufgabe(
                        titel = HomesHttp.text(it, EinrichtungModulJson.TITEL) ?: name,
                        erledigt = it.optBoolean(EinrichtungModulJson.ERLEDIGT, true),
                        einrichten = https(HomesHttp.text(it, EinrichtungModulJson.EINRICHTEN)),
                        dringend = it.optBoolean(EinrichtungModulJson.DRINGEND, false),
                    )
                },
                neu = HomesHttp.text(o, EinrichtungModulJson.NEU),
            )
        }

        /** [raw] when it is an https address with a host; anything else (http, a scheme of its own) is none. */
        fun https(raw: String?): String? = raw?.trim()?.takeIf {
            it.startsWith("https://", ignoreCase = true) && it.length > "https://".length && it.none(Char::isWhitespace)
        }
    }
}
