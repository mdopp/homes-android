package cloud.dopp.homes.ausloeser

import android.content.Context
import android.os.BatteryManager
import androidx.core.content.edit
import cloud.dopp.homes.HomesConfig
import cloud.dopp.homes.data.Napi
import org.json.JSONObject
import java.net.URI

/**
 * The phone as a trigger for Home Assistant's automations (homes ADR 0004 §1,
 * the owner, 2026-11-26: „Ich will die Home Assistant App nicht“): a touched
 * NFC tag and the charger, each reported to Homes, which fires HA's event.
 * Here: what a tag says, how the phone charges, the switch, and the German
 * sentence of every outcome.
 */
object Ausloeser {

    /** What Homes writes onto a tag: `cloud.dopp.homes://tag/<id>`. */
    private const val TAG_HOST = "tag"

    /** A tag written by HA's own app: `https://www.home-assistant.io/tag/<id>`; its id is what follows `/tag/`. */
    private const val HA_HOST = "www.home-assistant.io"
    private const val HA_PFAD = "/tag/"

    /** An id as HA keeps one: letters, digits and `-_.:`, at most this long. */
    private val ID = Regex("""[A-Za-z0-9_.:-]{1,128}""")

    /**
     * The tag's id in [text], an NDEF URI record or a link: the box's own address
     * `https://<homes>/tag/<id>` (§5, an App Link; [homes] the paired server's
     * host, no other box's), Homes' older scheme form, or HA's app's. Anything
     * else, or an id HA could not have made, is null.
     */
    fun tagAus(text: String?, homes: String? = null): String? {
        val u = runCatching { URI(text?.trim().orEmpty()) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase()
        val host = u.host?.lowercase()
        val pfad = u.rawPath.orEmpty()
        val https = scheme == "https" && pfad.startsWith(HA_PFAD)
        val id = when {
            scheme == HomesConfig.SCHEME && host == TAG_HOST -> pfad.removePrefix("/")
            https && host == HA_HOST -> pfad.removePrefix(HA_PFAD)
            https && host != null && host == homes?.lowercase() -> pfad.removePrefix(HA_PFAD)
            else -> return null
        }.removeSuffix("/")
        return id.takeIf { ID.matches(it) }
    }

    /** How the phone charges, from BatteryManager's `EXTRA_PLUGGED`: Android does not say which charger. */
    fun ladeart(plugged: Int): Napi.Ladeart = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> Napi.Ladeart.KABELLOS
        BatteryManager.BATTERY_PLUGGED_AC -> Napi.Ladeart.KABEL
        BatteryManager.BATTERY_PLUGGED_USB -> Napi.Ladeart.USB
        else -> Napi.Ladeart.UNBEKANNT
    }

    // --- the switch and the last charger report ---------------------------------

    private const val PREFS = "ausloeser"
    private const val LADEN = "laden_melden"
    private const val ZULETZT = "laden_zuletzt"
    private const val ZULETZT_UM = "laden_zuletzt_um"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** „Ladestation melden“: off until the person turns it on. */
    fun ladenMelden(ctx: Context): Boolean = prefs(ctx).getBoolean(LADEN, false)

    fun setzeLadenMelden(ctx: Context, an: Boolean) = prefs(ctx).edit { putBoolean(LADEN, an) }

    /** The last charger report as one German sentence, and when (epoch ms); null before the first. */
    fun zuletzt(ctx: Context): Pair<String, Long>? {
        val p = prefs(ctx)
        val satz = p.getString(ZULETZT, null) ?: return null
        return satz to p.getLong(ZULETZT_UM, 0L)
    }

    fun merke(ctx: Context, satz: String, um: Long) = prefs(ctx).edit { putString(ZULETZT, satz).putLong(ZULETZT_UM, um) }

    // --- wishes that wait for an admin (§8) ------------------------------------------

    private const val WARTEN = "freigaben"

    /** The wishes this phone filed that still wait, by their id, each with its tag; asked while the screen is open. */
    fun wartende(ctx: Context): Map<String, String> {
        val o = runCatching { JSONObject(prefs(ctx).getString(WARTEN, null) ?: return emptyMap()) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optString(it) }
    }

    fun wartet(ctx: Context, id: String, tag: String) = setzeWartende(ctx, wartende(ctx) + (id to tag))

    fun erledigt(ctx: Context, id: String) = setzeWartende(ctx, wartende(ctx) - id)

    private fun setzeWartende(ctx: Context, m: Map<String, String>) = prefs(ctx).edit { putString(WARTEN, JSONObject(m).toString()) }

    /** [url] when it is an https address on the paired Homes ([base]): what the app opens for a tag (§8), nothing else. */
    fun aufHomes(url: String?, base: String?): String? {
        val u = runCatching { URI(url?.trim().orEmpty()) }.getOrNull() ?: return null
        val homes = runCatching { URI(base.orEmpty()).host }.getOrNull()?.lowercase() ?: return null
        return url?.trim()?.takeIf { u.scheme?.lowercase() == "https" && u.host?.lowercase() == homes }
    }

    // --- the sentences ------------------------------------------------------------

    const val NICHT_GEKOPPELT = "Die Homes-App ist nicht mit Homes gekoppelt: koppel sie auf dem Startbildschirm."
    private const val OHNE_HA = "Diese Box hat kein Home Assistant: ohne „Geräte“ löst ein Tag nichts aus."
    private const val ZEIT = "Homes hat nicht rechtzeitig geantwortet. Versuch es gleich noch einmal."
    private const val OFFLINE = "Homes ist gerade nicht erreichbar. Ist das Telefon im Netz?"
    private const val UNBEKANNT = "Unbekannter Tag – auf Homes oder hier benennen"
    const val KEIN_TAG = "Auf diesem Tag steht nichts, was Homes kennt."

    /** A failed call in words; null when it did not fail. */
    fun fehler(a: AusloeserClient.Ausgang): String? = when (a) {
        is AusloeserClient.Ausgang.Abgelehnt -> a.satz
        AusloeserClient.Ausgang.OhneHa -> OHNE_HA
        AusloeserClient.Ausgang.NichtGekoppelt -> NICHT_GEKOPPELT
        AusloeserClient.Ausgang.Zeit -> ZEIT
        AusloeserClient.Ausgang.Offline -> OFFLINE
        else -> null
    }

    /** The one line a touched tag ends with: „Bürotür – gemeldet“, the unknown tag's, or why not. */
    fun tagZeile(a: AusloeserClient.Ausgang): String = when (a) {
        // a tag with an action of Homes' own says what it started (§4): „Der Scanner startet – nur für dich.“
        is AusloeserClient.Ausgang.Gemeldet -> a.text ?: if (a.name.isEmpty()) UNBEKANNT else "${a.name} – gemeldet"
        else -> fehler(a) ?: AusloeserClient.UNLESBAR
    }

    /** What a charger report said, for the Auslöser screen's line. */
    fun ladenZeile(an: Boolean, ladeart: Napi.Ladeart, a: AusloeserClient.Ausgang): String {
        val wie = when (ladeart) {
            Napi.Ladeart.KABELLOS -> " kabellos"
            Napi.Ladeart.KABEL -> " am Kabel"
            Napi.Ladeart.USB -> " über USB"
            Napi.Ladeart.UNBEKANNT -> ""
        }
        val was = if (an) "Lädt$wie" else "Lädt nicht mehr"
        return fehler(a)?.let { "$was – nicht gemeldet: $it" } ?: "$was – gemeldet"
    }
}
