package cloud.dopp.homes.teilen

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import cloud.dopp.homes.ausloeser.WlanTag
import cloud.dopp.homes.data.Napi.TeilenKarte
import cloud.dopp.homes.data.Napi.TeilenWlan
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * „Kontakt austauschen“ (owner approved 2026-11-28): one session at a time on
 * this phone, held in memory ([jetzt]); „Bei Berührung Kontakt austauschen“
 * (on by default — the link alone shows only the first name), and which of
 * the card's fields „Was teilen?“ offers switched on, remembered per field on
 * this phone. What is released is built here: exactly the switched fields.
 */
object Austausch {

    /** A session: its id on Homes, the link the other phone opens, until when (RFC 3339, as Homes sent it). */
    class Sitzung(val id: String, val link: String, val bis: String?)

    /** The session going on; null: none. Gone with the process, as Homes' is with its restart. */
    @Volatile
    var jetzt: Sitzung? = null

    /** The line to Homes; replaced in tests. */
    @VisibleForTesting
    var http: OkHttpClient? = null

    private const val PREFS = "austausch"
    private const val BERUEHRUNG = "bei_beruehrung"
    private const val FELD = "feld_"

    /** The fields „Was teilen?“ offers, in its order; the first name is always shown and not one of them. */
    val FELDER = listOf(
        Visitenkarte.Feld.NAME, Visitenkarte.Feld.MOBIL, Visitenkarte.Feld.TELEFON, Visitenkarte.Feld.MAIL,
        Visitenkarte.Feld.ADRESSE, Visitenkarte.Feld.GEBURTSTAG, Visitenkarte.Feld.FIRMA,
    )

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun beiBeruehrung(ctx: Context): Boolean = p(ctx).getBoolean(BERUEHRUNG, true)

    fun setzeBeiBeruehrung(ctx: Context, an: Boolean) = p(ctx).edit { putBoolean(BERUEHRUNG, an) }

    fun feldAn(ctx: Context, f: Visitenkarte.Feld): Boolean = p(ctx).getBoolean(FELD + f.name, true)

    fun setzeFeld(ctx: Context, f: Visitenkarte.Feld, an: Boolean) = p(ctx).edit { putBoolean(FELD + f.name, an) }

    /** The client for the paired Homes; null while not paired. */
    fun client(ctx: Context): AustauschClient? {
        val base = ServerStore.baseUrl(ctx)?.takeIf { it.isNotBlank() } ?: return null
        val token = TokenStore.get(ctx)?.takeIf { it.isNotBlank() } ?: return null
        return http?.let { AustauschClient(base, token, it) } ?: AustauschClient(base, token)
    }

    /** The first name and fields of „Meine Visitenkarte“; null and none while it is not set. */
    fun karte(ctx: Context): Pair<String?, Map<Visitenkarte.Feld, String>> =
        Behalten.visitenkarte(ctx)?.let(Visitenkarte::lesen) ?: (null to emptyMap())

    /** A birthday as Homes takes it: `YYYY-MM-DD` or `--MM-DD`; else none. */
    private val GEBURTSTAG = Regex("""(\d{4}|-)-\d{2}-\d{2}""")

    /** The `karte` of exactly [an]'s fields of [felder]; null when none is switched on. */
    fun karteJson(felder: Map<Visitenkarte.Feld, String>, an: Set<Visitenkarte.Feld>): JSONObject? {
        val o = JSONObject()
        felder.filterKeys { it in an }.forEach { (f, w) ->
            when (f) {
                Visitenkarte.Feld.NAME -> o.put(TeilenKarte.NAME, w)
                Visitenkarte.Feld.MOBIL -> o.put(TeilenKarte.MOBIL, w)
                Visitenkarte.Feld.TELEFON -> o.put(TeilenKarte.TELEFON, w)
                Visitenkarte.Feld.MAIL -> o.put(TeilenKarte.MAIL, w)
                Visitenkarte.Feld.ADRESSE -> o.put(TeilenKarte.ADRESSE, w)
                Visitenkarte.Feld.GEBURTSTAG -> if (GEBURTSTAG.matches(w)) o.put(TeilenKarte.GEBURTSTAG, w)
                Visitenkarte.Feld.FIRMA -> o.put(TeilenKarte.FIRMA, w)
            }
        }
        return o.takeIf { it.length() > 0 }
    }

    /** The guest Wi-Fi as `wlan`. */
    fun wlanJson(n: WlanTag.Netz): JSONObject = JSONObject().put(TeilenWlan.SSID, n.ssid).apply {
        if (n.schutz == WlanTag.Schutz.OFFEN) put(TeilenWlan.OFFEN, true) else put(TeilenWlan.KENNWORT, n.passwort)
    }
}
