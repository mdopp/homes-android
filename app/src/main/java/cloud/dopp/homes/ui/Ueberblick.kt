package cloud.dopp.homes.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isVisible
import cloud.dopp.homes.R
import cloud.dopp.homes.ausloeser.AusloeserActivity
import cloud.dopp.homes.data.Napi.KannKey
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.einrichtung.Abschnitte
import cloud.dopp.homes.einrichtung.Bilder
import cloud.dopp.homes.einrichtung.EinrichtungStore
import cloud.dopp.homes.kann.Kann
import cloud.dopp.homes.kann.KannStore
import cloud.dopp.homes.kann.Liste
import cloud.dopp.homes.kann.hat
import cloud.dopp.homes.verbindung.Verbindung
import cloud.dopp.homes.widget.DokumenteAdresse
import cloud.dopp.homes.widget.HomesPages

/**
 * „Alles auf einen Blick“ (the owner, 2026-11-27): one grid of tiles, three
 * in a row, for every area the person can open — the modules Homes lists
 * under „Öffnen“ (Dokumente, Zeitleiste, Musik, Rezepte, …, each with the
 * line Homes says where it stands), Energie (Homes' page, the house's power
 * and today's yield as its line, [EnergieKachel]), Auslöser and Dein Archiv as the box
 * offers them, Homes itself, and „Dokumente-Adresse“ for whom Homes gives
 * one. Each only while it is there, as the rows were. A small green dot
 * marks an area the person has never opened on this phone; the areas there
 * when the grid first drew count as opened.
 */
object Ueberblick {

    /** One tile: its [key] (for „opened“), picture, name, the line of where it stands, what a tap does. */
    class Bereich(val key: String, @DrawableRes val bild: Int, val name: String, val stand: String?, val neu: String? = null, val tap: () -> Unit)

    /** How many tiles a row holds. */
    private const val SPALTEN = 3

    /** The areas, in the order the grid shows them. */
    private fun bereiche(a: Activity): List<Bereich> {
        val e = EinrichtungStore.einrichtung(a)
        val kann = KannStore.kann(a)
        val base = ServerStore.baseUrl(a)
        // each area's line and what is new, as Homes says them (`bereiche`, homes-android#290)
        val von = e?.bereiche.orEmpty()
        val module = e?.oeffnen.orEmpty().map { m ->
            Bereich(MODUL + m.name, Bilder.fuer(m.icon), m.titel, m.stand ?: von[m.name]?.stand, m.neu ?: von[m.name]?.neu) {
                m.tippen?.let { Abschnitte.oeffnen(a, it.toUri()) }
            }
        }
        val au = von[KannKey.AUSLOESER]
        val ausloeser = Bereich(KannKey.AUSLOESER, R.drawable.ic_ausloeser, a.getString(R.string.ausloeser_title), au?.stand, au?.neu) {
            a.startActivity(Intent(a, AusloeserActivity::class.java))
        }.takeIf { kann.hat(Liste.MEHR, KannKey.AUSLOESER) }
        val ar = von[KannKey.ARCHIV]
        val archiv = StartAngebot.archiv(kann, base)?.let { url ->
            Bereich(KannKey.ARCHIV, R.drawable.ic_archiv, a.getString(R.string.archiv_title), ar?.stand, ar?.neu) { Abschnitte.oeffnen(a, url.toUri()) }
        }
        // the energy as a link (owner 2026-11-28, the widget gone): Homes' Energie page, the widget's numbers as its
        // line; only while the box says it has the energy (unknown: no link to a page that may not be there)
        val energie = base?.takeIf { energie(kann) }?.let { HomesPages.url(it, HomesPages.ENERGIE) }?.let { url ->
            Bereich(KannKey.ENERGIE, R.drawable.ic_bolt, a.getString(R.string.bereich_energie), EnergieKachel.gemerkt(a)) { Abschnitte.oeffnen(a, url.toUri()) }
        }
        val homes = base?.takeIf { e != null }?.let { HomesPages.url(it, HomesPages.START) }?.let { url ->
            Bereich(HOMES, R.drawable.ic_stat_homes, a.getString(R.string.oeffnen_homes), null, null) { Abschnitte.oeffnen(a, url.toUri()) }
        }
        val dok = kann?.dokumenteAdresse.orEmpty()
        val adresse = DokumenteAdresse.muster(dok)?.let { muster ->
            Bereich(ADRESSE, R.drawable.ic_modul_dokumente, a.getString(R.string.dokumente_adresse_kurz), muster, null) { DokumenteAdresse.kopieren(a, dok) }
        }
        return module + listOfNotNull(energie, ausloeser, archiv, homes, adresse)
    }

    /** The box has the energy (`widgets` `energie`, the energie module on); false while unknown. */
    fun energie(kann: Kann?): Boolean = kann != null && kann.hat(Liste.WIDGETS, KannKey.ENERGIE)

    /** Draw the grid into [group], [section] gone without a tile; faint while what it shows is the last thing known. */
    fun zeichnen(a: Activity, section: View?, group: ViewGroup?) {
        group ?: return
        val bereiche = bereiche(a)
        val gesehen = gesehen(a, bereiche.map { it.key })
        val neuGesehen = neuGesehen(a)
        group.removeAllViews()
        bereiche.chunked(SPALTEN).forEach { reihe ->
            val zeile = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            // new: never opened here, or Homes says something is new in it that the person has not opened since
            reihe.forEach { b -> zeile.addView(kachel(a, zeile, b, b.key !in gesehen || (b.neu != null && neuGesehen[b.key] != b.neu))) }
            repeat(SPALTEN - reihe.size) { zeile.addView(Space(a), zelle(a)) } // the last row keeps the columns' width
            group.addView(zeile)
        }
        group.alpha = if (Verbindung.stand(a) != null) GRAU else 1f
        section?.isVisible = bereiche.isNotEmpty()
    }

    private fun zelle(ctx: Context) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        val m = ctx.resources.getDimensionPixelSize(R.dimen.bereich_abstand)
        setMargins(m, m, m, m)
    }

    private fun kachel(a: Activity, zeile: ViewGroup, b: Bereich, neu: Boolean): View =
        a.layoutInflater.inflate(R.layout.item_bereich, zeile, false).apply {
            layoutParams = zelle(a)
            findViewById<ImageView>(R.id.bereich_bild).setImageResource(b.bild)
            findViewById<TextView>(R.id.bereich_name).text = b.name
            // once, while new: what is new („neu: Tags benennen …“), else its line
            val stand = if (neu) {
                b.neu?.let { a.getString(R.string.bereich_neu_was, it) } ?: b.stand ?: a.getString(R.string.bereich_neu)
            } else {
                b.stand
            }
            findViewById<TextView>(R.id.bereich_stand).apply {
                text = stand
                isVisible = stand != null
            }
            findViewById<TextView>(R.id.bereich_neu).isVisible = neu
            setOnClickListener {
                geoeffnet(a, b.key, b.neu)
                b.tap()
            }
        }

    // --- opened on this phone -----------------------------------------------------------

    private const val PREFS = "start_bereiche"
    private const val ANGEFANGEN = "angefangen"
    private const val MODUL = "modul:"
    private const val HOMES = "homes"
    private const val ADRESSE = "dokumente-adresse"
    private const val NEU = "neu:"
    private const val GRAU = 0.45f

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The areas opened (or there at the first draw); the first draw marks [jetzt] all as opened. */
    fun gesehen(ctx: Context, jetzt: List<String>): Set<String> {
        val p = p(ctx)
        if (!p.getBoolean(ANGEFANGEN, false)) {
            p.edit {
                putBoolean(ANGEFANGEN, true)
                jetzt.forEach { putBoolean(it, true) }
            }
            return jetzt.toSet()
        }
        return jetzt.filter { p.getBoolean(it, false) }.toSet()
    }

    /** Opened: no dot, and what Homes said is new in it is seen. */
    private fun geoeffnet(ctx: Context, key: String, neu: String?) = p(ctx).edit {
        putBoolean(key, true)
        neu?.let { putString(NEU + key, it) }
    }

    /** What is new that the person has seen, by area. */
    private fun neuGesehen(ctx: Context): Map<String, String> =
        p(ctx).all.mapNotNull { (k, v) -> if (k.startsWith(NEU) && v is String) k.removePrefix(NEU) to v else null }.toMap()
}
