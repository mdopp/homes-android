package cloud.dopp.homes.sicherung

/** One file in a granted folder: its path below that folder, its size, when it last changed (ms). */
data class Datei(val pfad: String, val groesse: Long, val geaendert: Long) {
    val name: String get() = pfad.substringAfterLast('/')
}

/**
 * The backup the app last sent whole, per source: what "newer" is measured
 * against. Kept in the app's prefs as one line ([zeile]).
 */
data class Gesendet(val name: String, val groesse: Long, val geaendert: Long) {
    fun zeile(): String = "$geaendert|$groesse|$name"

    companion object {
        fun von(d: Datei) = Gesendet(d.name, d.groesse, d.geaendert)

        fun aus(zeile: String?): Gesendet? {
            val teile = zeile?.split('|', limit = 3) ?: return null
            if (teile.size != 3) return null
            val geaendert = teile[0].toLongOrNull() ?: return null
            val groesse = teile[1].toLongOrNull() ?: return null
            return Gesendet(teile[2], groesse, geaendert)
        }
    }
}

/**
 * What is new in the two folders (#194): pure, so the choice is tested
 * without a phone. The app only reads there; nothing here deletes or changes
 * a file.
 */
object Auswahl {

    /** WhatsApp's folders below `Android/media/com.whatsapp/WhatsApp`. */
    const val DATENBANKEN = "Databases"
    const val MEDIEN = "Media"

    /** WhatsApp's current end-to-end encrypted backup; the dated ones beside it are older days. */
    private const val MSGSTORE = "msgstore.db.crypt15"
    const val MSGSTORE_PFAD = "$DATENBANKEN/$MSGSTORE"

    /**
     * A file changed in the last ten minutes may still be being written (the
     * messenger's nightly backup): it waits for the next run.
     */
    private const val RUHE_MS = 10 * 60 * 1000L

    /** Signal's on-device backups: `signal-<date and time>.backup` (older versions) or `signal-backup-….backup`. */
    private val SIGNAL = Regex("""signal-.+\.backup""")

    /** What to do about a source's one backup file. */
    sealed class Wahl {
        /** The folder holds no such backup at all. */
        object Keine : Wahl()

        /** The newest one is the one sent last, or older than it. */
        object Schon : Wahl()

        /** The newest one is still being written: try again soon. */
        data class Wartet(val datei: Datei) : Wahl()

        /** The newest one is new: send it. */
        data class Senden(val datei: Datei) : Wahl()
    }

    /** A path with a dot-name in it: `.Statuses`, `.nomedia`, `.trash`, a hidden file. */
    fun versteckt(pfad: String): Boolean = pfad.split('/').any { it.startsWith(".") }

    /** Not changed for [RUHE_MS]. */
    private fun ruhig(d: Datei, jetzt: Long): Boolean = jetzt - d.geaendert >= RUHE_MS

    /** [neueste] against what was sent last: only a newer, different file counts. */
    private fun gegen(neueste: Datei?, zuletzt: Gesendet?, jetzt: Long): Wahl = when {
        neueste == null -> Wahl.Keine
        zuletzt != null && Gesendet.von(neueste) == zuletzt -> Wahl.Schon
        zuletzt != null && neueste.geaendert < zuletzt.geaendert -> Wahl.Schon
        !ruhig(neueste, jetzt) -> Wahl.Wartet(neueste)
        else -> Wahl.Senden(neueste)
    }

    /** WhatsApp: `Databases/msgstore.db.crypt15` from [datenbanken] (the files in `Databases`), if new. */
    fun msgstore(datenbanken: List<Datei>, zuletzt: Gesendet?, jetzt: Long): Wahl =
        gegen(msgstoreDatei(datenbanken), zuletzt, jetzt)

    /** Signal: the newest `signal-….backup` directly in its folder, if newer than the one sent last. */
    fun signal(dateien: List<Datei>, zuletzt: Gesendet?, jetzt: Long): Wahl = gegen(neuesteSignal(dateien), zuletzt, jetzt)

    /** The newest `signal-….backup` directly in Signal's folder, or null: what the screen shows as found. */
    fun neuesteSignal(dateien: List<Datei>): Datei? =
        dateien.filter { '/' !in it.pfad && SIGNAL.matches(it.name) && it.groesse > 0 }
            .maxWithOrNull(compareBy<Datei>({ it.geaendert }, { it.name }))

    /** WhatsApp's current chat database in `Databases`, or null: what the screen shows as found. */
    fun msgstoreDatei(datenbanken: List<Datei>): Datei? =
        datenbanken.firstOrNull { it.pfad == MSGSTORE_PFAD && it.groesse > 0 }

    /**
     * WhatsApp's media worth asking Homes about: below `Media/`, no dot-name on
     * the way (`.Statuses` holds other people's statuses, `.nomedia` is a
     * marker), not empty, at rest.
     */
    fun medien(dateien: List<Datei>, jetzt: Long): List<Datei> =
        dateien.filter { it.pfad.startsWith("$MEDIEN/") && !versteckt(it.pfad) && it.groesse > 0 && ruhig(it, jetzt) }
            .sortedBy { it.pfad }

    /** [medien] Homes reported missing, in their order. */
    fun fehlende(medien: List<Datei>, fehlend: Set<String>): List<Datei> = medien.filter { it.pfad in fehlend }
}
