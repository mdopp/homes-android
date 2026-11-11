package cloud.dopp.homes.sicherung

/** One phone number, e-mail address or web address, with its vCard types (`cell`, `work`, …); a web address with its label and vCard group. */
data class Eintrag(val wert: String, val typen: List<String> = emptyList(), val label: String? = null, val gruppe: String? = null)

/** A postal address, in the order of the vCard ADR components. */
data class Adresse(
    val typen: List<String> = emptyList(),
    val postfach: String? = null,
    val zusatz: String? = null,
    val strasse: String? = null,
    val ort: String? = null,
    val region: String? = null,
    val plz: String? = null,
    val land: String? = null,
)

/** One contact as the address book has it, keyed by its lookup key ([uid]). */
data class Kontakt(
    val uid: String,
    val anzeigename: String? = null,
    val nachname: String? = null,
    val vorname: String? = null,
    val weitere: String? = null,
    val praefix: String? = null,
    val suffix: String? = null,
    val telefone: List<Eintrag> = emptyList(),
    val mails: List<Eintrag> = emptyList(),
    val adressen: List<Adresse> = emptyList(),
    val firma: String? = null,
    val abteilung: String? = null,
    val titel: String? = null,
    /** `yyyy-MM-dd`; anything else is left out. */
    val geburtstag: String? = null,
    val notiz: String? = null,
    val webseiten: List<Eintrag> = emptyList(),
    /** A PHOTO's JPEG, base64; [fotoVonHomes] when it is the face Homes adds (a `homes-` group or X-HOMES). */
    val foto: String? = null,
    val fotoVonHomes: Boolean = false,
)

/**
 * A contact as a vCard 3.0 (RFC 2426) for Homes' mirror (`POST /napi/kontakte`,
 * homes#210): written by the app itself, not Android's 2.1 export, whose
 * QUOTED-PRINTABLE and CHARSET parameters the box's Radicale may refuse.
 * Every card carries `UID:<lookup key>`, by which Homes tells a changed
 * contact from a new one. Text values escaped (backslash, comma, semicolon,
 * newline as `\n`), lines folded at 75 octets without splitting a character,
 * CRLF line ends. Pure.
 */
object Vcard {

    private const val CRLF = "\r\n"

    /** [k] as one card, ending in CRLF so cards can be concatenated. */
    fun karte(k: Kontakt): String {
        val zeilen = ArrayList<String>()
        zeilen += "BEGIN:VCARD"
        zeilen += "VERSION:3.0"
        zeilen += "UID:${text(k.uid)}"
        zeilen += "FN:${text(anzeigename(k))}"
        zeilen += "N:" + listOf(k.nachname, k.vorname, k.weitere, k.praefix, k.suffix).joinToString(";") { text(it.orEmpty()) }
        k.telefone.filter { it.wert.isNotBlank() }.forEach { zeilen += "TEL${typen(it.typen)}:${text(it.wert)}" }
        k.mails.filter { it.wert.isNotBlank() }.forEach { zeilen += "EMAIL${typen(listOf("internet") + it.typen)}:${text(it.wert)}" }
        k.adressen.forEach { a ->
            val teile = listOf(a.postfach, a.zusatz, a.strasse, a.ort, a.region, a.plz, a.land)
            if (teile.any { !it.isNullOrBlank() }) zeilen += "ADR${typen(a.typen)}:" + teile.joinToString(";") { text(it.orEmpty()) }
        }
        if (!k.firma.isNullOrBlank() || !k.abteilung.isNullOrBlank()) {
            zeilen += "ORG:" + listOfNotNull(k.firma.orEmpty(), k.abteilung?.takeIf { it.isNotBlank() }).joinToString(";") { text(it) }
        }
        k.titel?.takeIf { it.isNotBlank() }?.let { zeilen += "TITLE:${text(it)}" }
        k.geburtstag?.trim()?.takeIf { DATUM.matches(it) }?.let { zeilen += "BDAY:$it" }
        k.notiz?.takeIf { it.isNotBlank() }?.let { zeilen += "NOTE:${text(it)}" }
        k.webseiten.filter { it.wert.isNotBlank() }.forEach { zeilen += "URL:${text(it.wert.trim())}" }
        k.foto?.takeIf { !k.fotoVonHomes }?.let { zeilen += "PHOTO;ENCODING=b;TYPE=JPEG:$it" }
        zeilen += "END:VCARD"
        return zeilen.joinToString("") { falte(it) + CRLF }
    }

    /**
     * [k] without the links Homes adds to each household card: a URL that
     * came in a `homes-` group, or whose path is `/kontakte/<uid>/dokumente`,
     * `…/nachrichten` or `…/fotos` on the paired Homes address [homes]. Homes
     * drops them from a card it is sent anyway; the person's own URLs stay.
     */
    fun ohneHomesLinks(k: Kontakt, homes: String?): Kontakt = k.copy(webseiten = k.webseiten.filterNot { homesLink(it, homes) })

    private fun homesLink(w: Eintrag, homes: String?): Boolean {
        if (w.gruppe?.startsWith("homes-", ignoreCase = true) == true) return true
        val basis = homes?.trim()?.trimEnd('/')?.ifEmpty { null } ?: return false
        val url = w.wert.trim()
        return url.startsWith("$basis/", ignoreCase = true) && HOMES_LINK.matches(url.substring(basis.length))
    }

    /** FN is required: the display name, else the name's parts, the company, a number or an address. */
    fun anzeigename(k: Kontakt): String =
        k.anzeigename?.takeIf { it.isNotBlank() }
            ?: listOf(k.praefix, k.vorname, k.weitere, k.nachname, k.suffix).filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { null }
            ?: k.firma?.takeIf { it.isNotBlank() }
            ?: k.telefone.firstOrNull { it.wert.isNotBlank() }?.wert
            ?: k.mails.firstOrNull { it.wert.isNotBlank() }?.wert
            ?: "Ohne Namen"

    /** `;TYPE=cell,voice`, or nothing; types are tokens the app chose, upper case as RFC 2426 writes them. */
    private fun typen(t: List<String>): String = sauber(t).let { if (it.isEmpty()) "" else ";TYPE=" + it.joinToString(",") }

    private fun sauber(t: List<String>) = t.map { it.trim().uppercase() }.filter { TYP.matches(it) }.distinct()

    /**
     * A number as Homes compares two (homes `caldav.TelSchluessel`, exactly): its international
     * digits; a national one (0171 …) in the calling code [land], a trunk 0 after the code dropped
     * (+49 (0)171 …); "" without digits.
     */
    fun telSchluessel(v: String, land: String = LAND): String {
        val s = v.trim().removePrefix("tel:").trim()
        var d = s.filter { it in '0'..'9' }
        var intl = s.startsWith("+")
        if (!intl && d.startsWith("00")) d = d.substring(2).also { intl = true }
        return when {
            intl && d.startsWith(land) -> land + d.substring(land.length).trimStart('0')
            intl || d.isEmpty() -> d
            else -> land + d.trimStart('0')
        }
    }

    /**
     * [t] with each number ([telSchluessel]) once (Android joins Google's, WhatsApp's, Signal's
     * rows into one contact): the line that says most stays where it is, as Homes ranks it — the
     * main one, then the more kinds, then the one written +…; the first of equals.
     */
    fun einmalJeNummer(t: List<Eintrag>, land: String = LAND): List<Eintrag> {
        fun rang(e: Eintrag) = sauber(e.typen).let { 2 * it.size + (if ("PREF" in it) 100 else 0) } + (if (e.wert.trim().startsWith("+")) 1 else 0)
        val schluessel = t.map { telSchluessel(it.wert, land) }
        val best = HashMap<String, Int>()
        schluessel.forEachIndexed { i, k -> if (k.isNotEmpty() && best[k].let { it == null || rang(t[i]) > rang(t[it]) }) best[k] = i }
        return t.filterIndexed { i, _ -> schluessel[i].isEmpty() || best[schluessel[i]] == i }
    }

    /** A text value: backslash, comma and semicolon escaped, any line break as `\n`. */
    fun text(s: String): String =
        s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;")
            .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")

    /**
     * [zeile] folded: at most 75 octets (UTF-8) a line, each continuation
     * starting with one space, never inside a character. Without the CRLFs.
     */
    fun falte(zeile: String): String {
        val out = StringBuilder()
        var laenge = 0
        var i = 0
        while (i < zeile.length) {
            val cp = zeile.codePointAt(i)
            val n = Character.charCount(cp)
            val oktette = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (laenge + oktette > MAX) {
                out.append(CRLF).append(' ')
                laenge = 1
            }
            out.append(zeile, i, i + n)
            laenge += oktette
            i += n
        }
        return out.toString()
    }

    /** How many cards [text] holds. */
    fun anzahl(text: String): Int = Regex("(?im)^BEGIN:VCARD\\s*$").findAll(text).count()

    private const val MAX = 75

    /** The calling code a national number is read in: Homes' default (its mirror compares in it too). */
    private const val LAND = "49"
    private val DATUM = Regex("""\d{4}-\d{2}-\d{2}""")
    private val HOMES_LINK = Regex("""/kontakte/[^/?#]+/(dokumente|nachrichten|fotos)/?""")
    private val TYP = Regex("""[A-Z][A-Z0-9-]*""")
}
