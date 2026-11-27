package cloud.dopp.homes.ui

import android.content.Context
import androidx.core.content.edit
import cloud.dopp.homes.data.Card
import cloud.dopp.homes.data.Napi.Quelle

/**
 * „Jetzt“ on the start screen (the owner, 2026-11-27: „nothing hidden; every
 * area speaks only as loud as it matters now“): at most two hints, only while
 * something wants attention, each with × — dismissed until it changes — and
 * each also reachable in its own area. Today a device whose own words say it
 * wants care (a printer's „Papier leer“, a lost device) and a backup part
 * gone quiet for more than two days. No hint: the section is not there.
 * What a device needs is Homes' word for it (its card's `achtung`).
 *
 * Pure but for the dismissed store: what wants attention is decided from what
 * the app already has, the favourites and the backup's own memory.
 */
object Jetzt {

    /** One hint: its [id] (stable per thing), [stand] (what it says now: a new one shows again), its line, where it leads. */
    data class Hinweis(val id: String, val stand: String, val text: String, val ziel: Ziel)

    /** Where a hint leads: a device's page on Homes, or the backup's screen. */
    sealed class Ziel {
        data class Seite(val page: String?) : Ziel()
        object Sicherung : Ziel()
    }

    /** How many hints at most. */
    private const val HOECHSTENS = 2

    /** A backup part quiet longer than this is overdue. */
    private const val UEBERFAELLIG_MS = 2L * 24 * 60 * 60 * 1000

    /** Wants attention: Homes says why (its card's `achtung`, homes-android#290). */
    private fun achtung(card: Card): Boolean = card.achtung != null

    /** The favourites as the start screen orders them: what wants attention, then what is on, then the rest; each group in Homes' order. */
    fun ordnen(cards: List<Card>): List<Card> = cards.sortedBy { c ->
        when {
            achtung(c) -> 0
            c.isOn -> 1
            else -> 2
        }
    }

    /** A device's hint: its name and its words. */
    fun geraet(card: Card): Hinweis? {
        if (!achtung(card)) return null
        val was = card.achtung ?: return null
        return Hinweis("geraet:" + card.entityId, was, "${card.name}: $was", Ziel.Seite(card.page))
    }

    /** A backup part's hint: configured, last sent at [zuletzt], quiet for more than two days before [jetzt]. */
    fun sicherung(quelle: Quelle, name: String, zuletzt: Long?, jetzt: Long): Hinweis? {
        val z = zuletzt ?: return null
        if (jetzt - z <= UEBERFAELLIG_MS) return null
        val tage = ((jetzt - z) / (24L * 60 * 60 * 1000)).toInt()
        return Hinweis("sicherung:" + quelle.wire, z.toString(), "$name: zuletzt vor $tage Tagen gesichert", Ziel.Sicherung)
    }

    /** The hints to show: not dismissed in what they say now, at most [HOECHSTENS], devices first. */
    fun zeigen(alle: List<Hinweis>, weg: Map<String, String>): List<Hinweis> =
        alle.filter { weg[it.id] != it.stand }.take(HOECHSTENS)

    // --- the dismissed ones ---------------------------------------------------------------

    private const val PREFS = "start_jetzt"

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** What was dismissed: each hint's id with what it said then. */
    fun weg(ctx: Context): Map<String, String> = p(ctx).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    /** × on [h]: gone until it says something else. */
    fun wegtun(ctx: Context, h: Hinweis) = p(ctx).edit { putString(h.id, h.stand) }

}
