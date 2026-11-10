package cloud.dopp.homes.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import cloud.dopp.homes.R
import cloud.dopp.homes.data.Napi.KannKey
import cloud.dopp.homes.data.ToolDefs
import cloud.dopp.homes.kann.Kann
import cloud.dopp.homes.kann.Liste
import cloud.dopp.homes.kann.Modul
import cloud.dopp.homes.kann.hat
import cloud.dopp.homes.sicherung.Rueckholung

/**
 * The start screen's lists as the box offers them (homes ADR 0001 §3, §10):
 * a row for each feature the box has, a quiet trace ([Spuren]) for each it
 * lacks — nothing hidden. With what the box can do unknown, every row is
 * there, as before.
 */
internal class StartAngebot(
    private val activity: Activity,
    private val kann: Kann?,
    /** Draws the lists again: a request was pressed, or ended. */
    private val neu: () -> Unit,
) {

    /** A feature's row: its key in [Liste], its look, and what a tap does. */
    class Zeile(val key: String, @DrawableRes val icon: Int, val label: String, val tap: () -> Unit)

    /**
     * [zeilen] into [group]: each one the box offers as a row ([row]), each one
     * it lacks as a trace in its place — or, with [inSpuren], in its module's
     * line of Mehr's quiet section ([spuren]) where it has one. Then the keys
     * of [liste] an off module would bring and no row names, as traces
     * labelled by [titel] (null: not one the app offers).
     */
    fun fuellen(
        group: ViewGroup,
        liste: Liste,
        zeilen: List<Zeile>,
        row: (Zeile) -> View,
        inSpuren: Boolean = false,
        titel: (String, Modul) -> String? = { _, _ -> null },
    ) {
        zeilen.forEach { z ->
            if (kann.hat(liste, z.key)) {
                group.addView(row(z))
                return@forEach
            }
            val m = kann?.spurVon(liste, z.key)
            if (inSpuren && m != null && !m.admins) return@forEach
            group.addView(Spuren.zeile(activity, group, z.label, z.icon, m, neu))
        }
        if (kann == null) return
        val named = zeilen.map { it.key }.toSet()
        kann.fehlend(liste).filter { it !in named }.forEach { key ->
            val m = kann.spurVon(liste, key) ?: return@forEach
            val label = titel(key, m) ?: return@forEach
            group.addView(Spuren.zeile(activity, group, label, R.drawable.ic_services, m, neu))
        }
    }

    /**
     * Mehr's quiet section: one trace per module the box lacks, but the
     * admins' (their trace is on Homes' Box page). False when there is none.
     */
    fun spuren(group: ViewGroup): Boolean {
        group.removeAllViews()
        val spuren = kann?.spuren.orEmpty()
        spuren.forEach { m -> group.addView(Spuren.zeile(activity, group, m.titel, icon(m), m, neu)) }
        return spuren.isNotEmpty()
    }

    companion object {
        /**
         * „Dein Archiv“ (owner 2026-11-10): the person's archive beside Homes at
         * [base], when Homes says they have one (`mehr` "archiv", per person);
         * null otherwise, and while Homes has said nothing yet.
         */
        fun archiv(kann: Kann?, base: String?): String? =
            if (kann?.hat(Liste.MEHR, KannKey.ARCHIV) == true) base?.let(Rueckholung::archivAdresse) else null

        /** A widget key the picker can offer: the device, the energy, a tool the app offers. */
        fun widget(key: String): Boolean = key == KannKey.GERAET || key == KannKey.ENERGIE ||
            (key.startsWith(KannKey.TOOL) && key.removePrefix(KannKey.TOOL) !in ToolDefs.NOT_OFFERED)

        /** A trace's icon by what its module would bring, the app's own where it has one. */
        @DrawableRes
        private fun icon(m: Modul): Int {
            fun bringt(l: Liste, key: String) = m.bietet[l]?.contains(key) == true
            return when {
                bringt(Liste.MEHR, KannKey.DATEN_SICHERN) -> R.drawable.ic_backup
                bringt(Liste.MEHR, KannKey.STANDORTVERLAUF) -> R.drawable.ic_standort
                !m.bietet[Liste.KONTO].isNullOrEmpty() -> R.drawable.ic_konto
                bringt(Liste.WIDGETS, KannKey.GERAET) -> R.drawable.ic_device
                bringt(Liste.WIDGETS, KannKey.ENERGIE) -> R.drawable.ic_bolt
                else -> R.drawable.ic_services
            }
        }
    }
}
