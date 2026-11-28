package cloud.dopp.homes.teilen

import android.content.Context
import android.nfc.NdefMessage
import android.util.Base64
import cloud.dopp.homes.ausloeser.WlanTag
import org.json.JSONArray
import org.json.JSONObject

/**
 * The tags kept on this phone to share again („Im Telefon behalten“, owner
 * 2026-11-28), each with its name and its NDEF message as read, and the
 * person's own card („Meine Visitenkarte“) and the guest Wi-Fi („Gast-WLAN“) —
 * all in the [Tresor].
 */
object Behalten {

    /** A kept tag: its [name] and what it carries. */
    class Tag(val name: String, val nachricht: NdefMessage) {
        override fun toString() = "Tag($name)" // never its content
    }

    private const val TAGS = "tags"
    private const val KARTE = "visitenkarte"
    private const val GAST = "gastwlan"
    private const val NAME = "name"
    private const val NDEF = "ndef"

    fun alle(ctx: Context): List<Tag> = aus(Tresor.lesen(ctx, TAGS))

    fun dazu(ctx: Context, name: String, n: NdefMessage) = Tresor.schreiben(ctx, TAGS, text(alle(ctx) + Tag(name, n)))

    fun weg(ctx: Context, i: Int) = Tresor.schreiben(ctx, TAGS, text(alle(ctx).filterIndexed { j, _ -> j != i }))

    /** The person's own card as vCard text; null while none is set. */
    fun visitenkarte(ctx: Context): String? = Tresor.lesen(ctx, KARTE)

    fun setzeVisitenkarte(ctx: Context, vcard: String?) = Tresor.schreiben(ctx, KARTE, vcard)

    /** The guest Wi-Fi, kept as its WSC credential ([WlanTag]); null while none is set. */
    fun gastWlan(ctx: Context): WlanTag.Netz? =
        Tresor.lesen(ctx, GAST)?.let { runCatching { WlanTag.lesen(Base64.decode(it, Base64.NO_WRAP)) }.getOrNull() }

    /** [n] as the guest Wi-Fi; null removes it. */
    fun setzeGastWlan(ctx: Context, n: WlanTag.Netz?) =
        Tresor.schreiben(ctx, GAST, n?.let { Base64.encodeToString(WlanTag.nutzlast(it.ssid, it.passwort, it.schutz), Base64.NO_WRAP) })

    fun text(tags: List<Tag>): String = JSONArray(
        tags.map { JSONObject().put(NAME, it.name).put(NDEF, Base64.encodeToString(it.nachricht.toByteArray(), Base64.NO_WRAP)) },
    ).toString()

    fun aus(text: String?): List<Tag> {
        val a = runCatching { JSONArray(text ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            runCatching { Tag(o.getString(NAME), NdefMessage(Base64.decode(o.getString(NDEF), Base64.NO_WRAP))) }.getOrNull()
        }
    }
}
