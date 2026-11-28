package cloud.dopp.homes.data

import java.net.URLEncoder

/**
 * Every call the app makes to Homes, as data (laib ADR 0022: Homes' `/napi` is
 * this app's API). An [Endpoint] names its method, its path, the query
 * parameters and body fields it sends — in the order the client passes their
 * values — and the reply shapes whose keys the client reads. The client never
 * spells any of these itself: `NapiRequest.kt` turns an endpoint into a
 * request, and the parsers read keys through the `…Json` objects below.
 *
 * `NapiContractTest` holds all of it to Homes' handlers, file and line.
 */
object Napi {

    /** Homes reads the device token from this header, after this scheme (`bearerOf`). */
    const val AUTH_HEADER = "Authorization"
    const val AUTH_SCHEME = "Bearer"

    /** The header value that carries [token] (`homes_device_…`). */
    fun bearer(token: String): String = "$AUTH_SCHEME $token"

    /** The root every path shares. A tool's rows path must sit under it, see [TOOL_ROWS]. */
    const val ROOT = "/napi/"

    /** HEAD and PATCH are the resumable upload's (`/napi/sicherung/uploads/{id}`, #194); PUT and DELETE the Homes account's writes. */
    enum class Method { GET, POST, HEAD, PATCH, PUT, DELETE }

    /** The resumable upload's offset: a reply header of HEAD, a request header of PATCH (`/napi/sicherung/uploads/{id}`, #194). */
    const val UPLOAD_OFFSET = "Upload-Offset"

    /**
     * A restore's download (`/napi/sicherung/letzte/{quelle}/{pfad...}`, homes#209):
     * the request header that asks for the bytes from where the phone stopped
     * (`bytes=<n>-`), and the reply headers that say where they start and which
     * file they are of — a backup replaced on the box has another ETag.
     */
    const val RANGE = "Range"
    const val CONTENT_RANGE = "Content-Range"
    const val ETAG = "ETag"

    /** A PATCH's body: the next bytes of the file, as they stand on the phone. */
    const val OFFSET_STREAM = "application/offset+octet-stream"

    /**
     * Where a backup comes from, as Homes names it (`quelle`, #194). `sms` is
     * the phone's SMS, MMS and call log, age-encrypted on the phone (laib ADR 0029).
     */
    enum class Quelle(val wire: String) { WHATSAPP("whatsapp"), SIGNAL("signal"), SMS("sms"), SOZIAL("sozial") }

    /** The body of `POST /napi/kontakte`: the contacts as concatenated vCards. */
    const val VCARD = "text/vcard"

    /** The Homes account's request header: the state the phone has, Homes' last [ETAG] (#206); `*` on a write: a new entry. */
    const val IF_NONE_MATCH = "If-None-Match"

    /** A write of the Homes account: only over this version, the entry's [FeedIcal.ETAG] (owner, 2026-11-05). */
    const val IF_MATCH = "If-Match"

    /** An appointment the phone writes back, as one VCALENDAR. */
    const val ICAL = "text/calendar"

    /**
     * One call. [query] and [body] are the names the client sends, in the order
     * it passes the values; [allowed] closes a parameter to the values Homes
     * takes; [reply] lists the shapes whose keys the client reads.
     */
    class Endpoint(
        val method: Method,
        val path: String,
        val query: List<String> = emptyList(),
        val allowed: Map<String, List<String>> = emptyMap(),
        val body: List<String> = emptyList(),
        val reply: List<Any> = emptyList(),
        val accept: String? = null,
        val events: List<String> = emptyList(),
        /** Request headers the client sends, by name; [NapiRequest] sends no others. */
        val headers: List<String> = emptyList(),
        /** Reply headers the client reads. */
        val replyHeaders: List<String> = emptyList(),
    ) {
        /**
         * [path] with [values] for [query], in order; a null value leaves its
         * parameter out. A value outside [allowed] is refused here, before Homes
         * would refuse it.
         */
        fun target(vararg values: String?): String {
            require(values.size <= query.size) { "$path takes ${query.size} query values" }
            val q = query.zip(values.toList()).mapNotNull { (name, value) ->
                if (value == null) return@mapNotNull null
                allowed[name]?.let { require(value in it) { "$name=$value: Homes takes only $it" } }
                name + "=" + URLEncoder.encode(value, "UTF-8")
            }
            return if (q.isEmpty()) path else path + "?" + q.joinToString("&")
        }

        /** The body: [body]'s names with [values], in order; a null value leaves its field out. */
        fun fields(vararg values: Any?): Map<String, Any> {
            require(values.size == body.size) { "$path takes ${body.size} body fields" }
            val out = LinkedHashMap<String, Any>()
            body.zip(values.toList()).forEach { (name, value) -> if (value != null) out[name] = value }
            return out
        }

        /**
         * This endpoint at [concrete], one of its paths with every `{…}` filled; null when it is not one.
         * A last segment `{name...}` takes the rest of the path, one segment or more (Go's ServeMux).
         */
        fun at(concrete: String): Endpoint? {
            val want = path.split('/')
            val have = concrete.substringBefore('?').split('/')
            val rest = want.last().let { it.startsWith("{") && it.endsWith("...}") }
            val fits = (want.size == have.size || (rest && have.size > want.size)) && want.indices.all { i ->
                want[i] == have[i] || (want[i].startsWith("{") && have[i].isNotEmpty())
            } && (!rest || have.drop(want.size).all { it.isNotEmpty() })
            return if (fits) Endpoint(method, concrete, query, allowed, body, reply, accept, events, headers, replyHeaders) else null
        }

        /**
         * This endpoint with its `{…}` segments filled by [values], in order, each one URL-encoded;
         * a `{name...}` value keeps its `/`, each segment between encoded.
         */
        fun with(vararg values: String): Endpoint {
            val rest = values.iterator()
            fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
            val filled = path.split('/').joinToString("/") { seg ->
                if (seg.startsWith("{")) {
                    require(rest.hasNext()) { "$path takes more values" }
                    val v = rest.next()
                    if (seg.endsWith("...}")) v.split('/').joinToString("/") { enc(it) } else enc(v)
                } else {
                    seg
                }
            }
            require(!rest.hasNext()) { "$path takes fewer values" }
            return requireNotNull(at(filled)) { "$filled is not $path" }
        }

        override fun toString() = "$method $path"
    }

    /** The domains Homes draws as a card. */
    val CARD_DOMAINS: Set<String> = setOf("sensor", "binary_sensor", "cover", "light", "switch", "climate", "media_player", "lock")

    // --- the replies, by the keys the app reads ---------------------------------

    /** `GET /napi/assistent`: the assistant's name, the box's one setting. */
    object AssistentJson {
        const val NAME = "name"
    }

    object BoxHomeJson { const val GUT = "gut" } // `GET /napi/box/home`'s: all is well on the box (owner 2026-11-27, the claim)
    /** `GET /napi/whoami`: who owns the device token. */
    object WhoamiJson {
        const val UID = "uid"
    }

    /** One entity as a card: the state path, the active list and the stream's frames carry it. */
    object CardJson {
        const val ENTITY_ID = "entity_id"
        const val NAME = "name"
        const val DOMAIN = "domain"
        const val DEVICE_CLASS = "device_class"
        const val STATE = "state"
        const val UNIT = "unit"
        const val BRIGHTNESS = "brightness"
        const val CURRENT_POSITION = "current_position"
        const val CURRENT_TEMPERATURE = "current_temperature"
        const val SUPPORTED_FEATURES = "supported_features"
        const val TEMPERATURE = "temperature"
        const val MIN_TEMP = "min_temp"
        const val MAX_TEMP = "max_temp"
        const val TARGET_TEMP_STEP = "target_temp_step"
        const val SUPPORTED_COLOR_MODES = "supported_color_modes"

        /** The thing's own page on Homes, a path (`/raeume/<entity_id>`): what a tap opens. */
        const val PAGE = "page"

        /**
         * The thing in the family's words where Homes has its own, as its web page says them
         * (owner, 2026-11-14: „Bitte in der App und auf der Webseite gleich“): today a printer's,
         * „druckt · Schwarz 42 %“. Absent for everything else; shown instead of [STATE].
         */
        const val TEXT = "text"

        /** Why it needs a look, a few German words („nicht erreichbar“, „Papier leer“; homes-android#290); absent while it needs none. */
        const val ACHTUNG = "achtung"
    }

    /** `GET /napi/portal/state`: `{ok, card}`. */
    object StateJson {
        const val CARD = "card"
    }

    /** `POST /napi/upload`: `{ok, id}` (the name Homes placed), or `{ok: false, error}`. */
    object PhotoJson {
        const val OK = "ok"
        const val ID = "id"
        const val ERROR = "error"
    }

    /** `GET /napi/portal/favorites`: `{ok, favorites: [card]}`, the person's ☆ things in their order. */
    object FavoritesJson {
        const val FAVORITES = "favorites"
    }

    /** `GET /napi/portal/energy`: `{ok, energy: {flow: [meter], totals: [meter + today]}}`. */
    object EnergyJson {
        const val OK = "ok"
        const val ENERGY = "energy"
        const val FLOW = "flow"
        const val TOTALS = "totals"
        const val LABEL = "label"
        const val STATE = "state"
        const val UNIT = "unit"
        const val SENSE = "sense"
        const val ENTITY_ID = "entity_id"

        /** A total's kWh since the house's midnight; absent when HA's history does not say. */
        const val TODAY = "today"

        /** How full the battery is: a meter whose state is the percent; absent without one (mdopp/homes#228). */
        const val CHARGE = "charge"
    }

    /** `GET /napi/portal/start/addable`: `{ok, rooms: [{room, cards: [device]}]}`. */
    object AddableJson {
        const val ROOMS = "rooms"
        const val ROOM = "room"
        const val CARDS = "cards"
        const val ENTITY_ID = "entity_id"
        const val NAME = "name"
        const val DOMAIN = "domain"
        const val DEVICE_CLASS = "device_class"
    }

    /** The stream's frame: `card_state` `{entity_id, card}`. */
    object EventJson {
        const val ENTITY_ID = "entity_id"
        const val CARD = "card"
    }

    /** The contacts tool of `/napi/defs/tool`: Homes lists it, the app no longer offers it (owner, 2026-11-02). */
    const val TOOL_CONTACTS = "contacts"

    /** The reminders tool of `/napi/defs/tool`: not offered either (owner, 2026-11-03: reminders go into the phone's calendar). */
    const val TOOL_TASKS = "task"

    /** Dokumente and Notizen, gone as widgets (owner 2026-11-27: „Kann ganz weg“); a placed one says so. */
    const val TOOL_DOC = "doc"
    const val TOOL_NOTE = "note"

    /** The frame the stream carries, by its SSE event name. */
    const val CARD_STATE = "card_state"

    /**
     * The stream's other frame (#209, homes#223): the household's shared data
     * changed, `{teil: kontakte|kalender}`; it carries no data, the Homes
     * account syncs that part.
     */
    const val GEMEINSAM_FRAME = "gemeinsam"

    /** A `gemeinsam` frame's payload. */
    object GemeinsamFrameJson {
        const val TEIL = "teil"
    }

    /** The values of a `gemeinsam` frame's `teil`. */
    const val TEIL_KONTAKTE = "kontakte"
    const val TEIL_KALENDER = "kalender"

    /** `GET /napi/defs/tool`: `{ok, defs: [def]}`. */
    object ToolDefJson {
        const val DEFS = "defs"
        const val TOOL_ID = "tool-id"
        const val TOOL_LABEL = "tool-label"
        const val TOOL_API_PATH = "tool-api-path"
        const val TOOL_CELL_SCHEMA = "tool-cell-schema"
        const val TOOL_ACTIONS = "tool-actions"
        const val TOOL_ACTIONS_TITLED = "tool-actions-titled"
        const val TOOL_ACTION_PARAMS = "tool-action-params"

        /** An entry of `tool-actions-titled`. */
        const val ID = "id"
        const val TITLE = "title"
    }

    /**
     * The roles a `tool-cell-schema` may name (ADR 0011): a closed vocabulary the
     * app reads, wider than the roles Homes' catalog uses today.
     */
    object CellSchemaJson {
        const val TITLE = "title"
        const val SUBTITLE = "subtitle"
        const val META = "meta"
        const val BADGE = "badge"
        const val STATE = "state"
        const val ICON = "icon"
        const val ACTIONS = "actions"
    }

    /** A tool's row: the keys the app reads beside the ones its `tool-cell-schema` names. */
    object ToolRowJson {
        /** The item's own page on Homes, a path: what a tap opens. */
        const val PAGE = "page"
    }

    /** A refused `POST /napi/action-callback`: `{ok: false, reason}`. */
    object ActionJson {
        const val REASON = "reason"
    }

    /**
     * `POST /napi/merken` (Share → Homes, homes#120): the note Homes kept, or
     * `{error}` with a German sentence when it kept none.
     */
    /** `art` of a kept recipe (owner 2026-11-27: shared recipes show in the app again). */
    const val ART_REZEPT = "rezept"

    object MerkenJson {
        const val TITLE = "titel"
        const val KIND = "art"
        const val SAID = "kurz"
        const val ALREADY = "schon"
        const val PARTIAL = "teilweise"
        const val NOTE = "notiz"
        const val ERROR = "error"
    }

    /**
     * `GET /napi/sicherung/stand` (#194): per source what reached the box,
     * `{quellen: {whatsapp: {zuletzt, dateien, bytes, wartend}, …}, frei_bytes}`;
     * `{fehler}` with a German sentence when the box has no archive (503).
     */
    object StandJson {
        const val QUELLEN = "quellen"
        const val ZULETZT = "zuletzt"
        const val DATEIEN = "dateien"
        const val BYTES = "bytes"
        const val WARTEND = "wartend"
        const val FREI_BYTES = "frei_bytes"
        const val FEHLER = "fehler"
    }

    /** `POST /napi/sicherung/fehlend`: the paths of the files Homes does not have yet. */
    object FehlendJson {
        const val FEHLEND = "fehlend"
        const val FEHLER = "fehler"
    }

    /** The body of `POST /napi/sicherung/fehlend`: one entry of `dateien`. */
    object DateiJson {
        const val PFAD = "pfad"
        const val GROESSE = "groesse"
    }

    /** `POST /napi/sicherung/uploads`: `{id, offset}`, or `{fehler}`. */
    object UploadJson {
        const val ID = "id"
        const val OFFSET = "offset"
        const val FEHLER = "fehler"
    }

    /**
     * A PATCH's reply: `{offset, fertig}`; `{fehler, offset}` on a mismatch (409)
     * or after a partial write (408, 507); `{fehler}` otherwise.
     */
    object TeilJson {
        const val OFFSET = "offset"
        const val FERTIG = "fertig"
        const val FEHLER = "fehler"
    }

    /** `GET /napi/sicherung/schluessel`: the person's archive key, `{empfaenger: "age1…"}`, or `{fehler}` (404). */
    object SchluesselJson {
        const val EMPFAENGER = "empfaenger"
        const val FEHLER = "fehler"
    }

    /**
     * `GET /napi/sicherung/letzte` (homes#209): what a new phone can get back,
     * `{quellen: {signal: [{pfad, groesse, geaendert}], whatsapp: […]}}`
     * (`geaendert` epoch ms; a WhatsApp medium as `Media/…`), or `{fehler}`.
     */
    object LetzteJson {
        const val QUELLEN = "quellen"
        const val PFAD = "pfad"
        const val GROESSE = "groesse"
        const val GEAENDERT = "geaendert"
        const val FEHLER = "fehler"
    }

    /** A restore's download refused: `{fehler}`, e.g. 423 while the archive is closed (a medium). */
    private object LetzteDateiJson {
        const val FEHLER = "fehler"
    }

    /** `POST /napi/kontakte`: `{kontakte, neu, geaendert, entfernt}`, or `{fehler}` (409 for no card). */
    object KontakteJson {
        const val KONTAKTE = "kontakte"
        const val NEU = "neu"
        const val GEAENDERT = "geaendert"
        const val ENTFERNT = "entfernt"
        const val FEHLER = "fehler"
    }

    /**
     * `GET /napi/kontakte/korrekturen` (Kontakt prüfen lassen, owner 2026-11-07):
     * the accepted changes of the person's own contacts, each with the UID the
     * app sent („Kontakte übertragen“: the lookup key) and per change its number
     * [NR], its field [ART], before and after; a number's or e-mail's kind and whether it is
     * the main one, an address's parts (the street one line), a photo's JPEG [BILD] (base64).
     */
    object KorrekturenJson {
        const val KORREKTUREN = "korrekturen"
        const val ID = "id"
        const val UID = "uid"
        const val NAME = "name"
        const val AENDERUNGEN = "aenderungen"
        const val NR = "nr"
        const val ART = "art"
        const val VORHER = "vorher"
        const val NACHHER = "nachher"
        const val VORHER_ART = "vorher_art"
        const val NACHHER_ART = "nachher_art"
        const val VORHER_HAUPT = "vorher_haupt"
        const val NACHHER_HAUPT = "nachher_haupt"
        const val VORHER_ADRESSE = "vorher_adresse"
        const val NACHHER_ADRESSE = "nachher_adresse"
        const val BILD = "bild"
        const val STRASSE = "strasse"
        const val PLZ = "plz"
        const val ORT = "ort"
        const val LAND = "land"
        const val FEHLER = "fehler"
    }

    /** `POST /napi/kontakte/korrekturen/{id}`: `{ok}`, or `{fehler}` (404: nothing of it waits any more). */
    object KorrekturErledigtJson {
        const val OK = "ok"
        const val FEHLER = "fehler"
    }

    /**
     * `POST /napi/kontakte/fragen` (Daten erfragen, owner 2026-11-28): `{ok, kontakt, vorname, link, text, fehlt,
     * telefon?, neu?}`; 404 `{ok: false, error, anlegen: true}` not in the household's address book, 409
     * `{kandidaten: [{kontakt, uid}]}` more than one fits, any other refusal `{error}`, a German sentence.
     */
    object FragenJson {
        const val OK = KorrekturErledigtJson.OK
        const val KONTAKT = "kontakt"
        const val VORNAME = "vorname"
        const val LINK = "link"
        const val TEXT = "text"
        const val FEHLT = "fehlt"
        const val TELEFON = "telefon"
        const val NEU = "neu"
        const val ANLEGEN = "anlegen"
        const val KANDIDATEN = "kandidaten"
        const val UID = "uid"
        const val ERROR = MerkenJson.ERROR
    }

    /**
     * `POST /napi/kontakte/einladen` (Teilen: Kontaktdaten eintragen lassen, owner 2026-11-28): `{ok, link, text,
     * gueltig_bis}`, a one-time link a person fills in their contact on; a refusal (403 not an admin, 503, 502)
     * `{ok: false, error}`, a German sentence.
     */
    object EinladenJson {
        const val OK = KorrekturErledigtJson.OK
        const val LINK = FragenJson.LINK
        const val TEXT = FragenJson.TEXT
        const val GUELTIG_BIS = "gueltig_bis"
        const val ERROR = MerkenJson.ERROR
    }

    /**
     * Kontakt austauschen (owner 2026-11-28; homes `internal/napi/teilen.go`): `POST /napi/teilen` → 201 `{ok, id,
     * link, gueltig_bis}`, `GET /napi/teilen/{id}` → `{ok, geoeffnet, eingetragen, gueltig_bis}`, `PUT` and `DELETE`
     * → `{ok}`; a refusal `{ok: false, error}` (403, 404 not the person's, 410 over, 429 too many, 400, 503).
     */
    object TeilenNeuJson {
        const val OK = KorrekturErledigtJson.OK
        const val ID = "id"
        const val LINK = FragenJson.LINK
        const val GUELTIG_BIS = EinladenJson.GUELTIG_BIS
        const val ERROR = MerkenJson.ERROR
    }

    /** `GET /napi/teilen/{id}`. */
    object TeilenStandJson {
        const val OK = KorrekturErledigtJson.OK
        const val GEOEFFNET = "geoeffnet"
        const val EINGETRAGEN = "eingetragen"
        const val GUELTIG_BIS = EinladenJson.GUELTIG_BIS
        const val ERROR = MerkenJson.ERROR
    }

    /** `PUT` and `DELETE /napi/teilen/{id}`: `{ok}`, or the refusal. */
    object TeilenOkJson {
        const val OK = KorrekturErledigtJson.OK
        const val ERROR = MerkenJson.ERROR
    }

    /** A `PUT /napi/teilen/{id}`'s `karte`: exactly the released fields (`geburtstag` `YYYY-MM-DD` or `--MM-DD`). */
    object TeilenKarte {
        const val NAME = "name"
        const val MOBIL = "mobil"
        const val TELEFON = "telefon"
        const val MAIL = "mail"
        const val ADRESSE = "adresse"
        const val GEBURTSTAG = "geburtstag"
        const val FIRMA = "firma"
    }

    /** A `PUT /napi/teilen/{id}`'s `wlan`. */
    object TeilenWlan {
        const val SSID = "ssid"
        const val KENNWORT = "kennwort"
        const val OFFEN = "offen"
    }

    /**
     * A refused `GET /napi/gemeinsam/…` (the Homes account, #206): `{fehler}`
     * with Homes' German sentence; the answer itself is vCard or iCalendar.
     */
    object GemeinsamJson {
        const val FEHLER = "fehler"
    }

    /**
     * The Homes account's calendar stream (#206): the properties of each
     * VCALENDAR beside the standard ones — the calendar's id (the last part
     * of its path in Radicale; [ERINNERUNGEN] for the reminders) and its kind.
     */
    object FeedIcal {
        const val NAME = "X-WR-CALNAME"
        const val KALENDER = "X-HAUS-KALENDER"
        const val ART = "X-HAUS-ART"
        const val ERINNERUNGEN = "erinnerungen"

        /** With [SCHREIBBAR_QUERY]: a calendar the phone writes into, and each entry's (and card's) version. */
        const val SCHREIBBAR = "X-HOMES-SCHREIBBAR"
        const val ETAG = "X-HOMES-ETAG"
    }

    /** The feeds as a phone that writes back reads them: series whole, each entry with its [FeedIcal.ETAG]. */
    private const val SCHREIBBAR_QUERY = "schreibbar"

    /**
     * `GET /napi/standort` (Standortverlauf, owner 2026-11-02): whether the
     * person connected Reitti on Homes, `{verbunden, seit}` (RFC 3339, absent
     * when not); `{fehler, grund}` when the box has no Reitti (503).
     */
    object StandortStandJson {
        const val VERBUNDEN = "verbunden"
        const val SEIT = "seit"
        const val FEHLER = "fehler"
        const val GRUND = "grund"
    }

    /**
     * `POST /napi/standort`: `{angenommen, verworfen}` once Reitti has the
     * points; else `{fehler, grund}` and the batch stays on the phone.
     */
    object StandortJson {
        const val ANGENOMMEN = "angenommen"
        const val VERWORFEN = "verworfen"
        const val FEHLER = "fehler"
        const val GRUND = "grund"
    }

    /** One entry of `punkte` in the body of `POST /napi/standort`. */
    object PunktJson {
        const val ZEIT = "zeit"
        const val BREITE = "breite"
        const val LAENGE = "laenge"
        const val GENAUIGKEIT = "genauigkeit"
        const val HOEHE = "hoehe"
        const val TEMPO = "tempo"
    }

    /** Why Homes kept nothing (`grund` of `/napi/standort`): no Reitti token on Homes yet. */
    const val GRUND_KEIN_SCHLUESSEL = "kein_schluessel"

    /** Reitti refused the person's token: a new one on Homes. */
    const val GRUND_SCHLUESSEL_ABGELEHNT = "schluessel_abgelehnt"

    /** Reitti did not take the points now. */
    const val GRUND_REITTI_WEG = "reitti_weg"

    /**
     * `POST /napi/standort/import` (a shared location history, owner
     * 2026-11-03): `{id, offset}`, or `{fehler, grund}` — 409
     * [GRUND_KEIN_SCHLUESSEL] before the person connected Reitti on Homes.
     */
    object ImportJson {
        const val ID = "id"
        const val OFFSET = "offset"
        const val FEHLER = "fehler"
        const val GRUND = "grund"
    }

    /**
     * `GET /napi/standort/import/{id}`: how far the import got. [STAND] is one
     * of `hochladen` (the file still arriving), [IMPORT_LAEUFT], [IMPORT_FERTIG], [IMPORT_FEHLER];
     * [GELESEN] of [GROESSE] bytes Homes has read, [PUNKTE] Reitti took;
     * at the end [TEXT] (done) or [FEHLER] with [GRUND], each one German sentence.
     */
    object ImportStandJson {
        const val STAND = "stand"
        const val GELESEN = "gelesen"
        const val GROESSE = "groesse"
        const val PUNKTE = "punkte"
        const val TEXT = "text"
        const val FEHLER = "fehler"
        const val GRUND = "grund"
    }

    /** Homes reads the file and hands it to Reitti. */
    const val IMPORT_LAEUFT = "laeuft"
    const val IMPORT_FERTIG = "fertig"
    const val IMPORT_FEHLER = "fehler"

    /**
     * `GET /napi/kann` (homes ADR 0001 §2, §10): what this box can do, `{version, module: [ModulJson], app: AngebotJson,
     * [KannEigenJson]}`. Fields are only ever added (version 1); a Homes without the path answers 404 `not_found`.
     */
    object KannJson {
        const val VERSION = "version"
        const val MODULE = "module"
        const val APP = "app"
    }
    object KannEigenJson { const val DOKUMENTE_ADRESSE = "dokumente_adresse" } // `/napi/kann`'s, the device's person's own: <login>+doc+@<domain> (homes#338)

    /**
     * One module of `/napi/kann`, and the answer of `POST /napi/kann/anfordern`:
     * on or off, why not ([GRUND], German), what it would bring ([BIETET]),
     * and its trace ([SPUR_ID]: fehlt, aus, or its wish's state; [SPUR] the
     * German words beside it) and whether it offers „Hier anfordern“ ([ANFORDERN]).
     */
    object ModulJson {
        const val NAME = "name"
        const val TITEL = "titel"
        const val AN = "an"
        const val GRUND = "grund"
        const val BIETET = "bietet"
        const val ADMINS = "admins"
        const val SPUR_ID = "spur_id"
        const val SPUR = "spur"
        const val ANFORDERN = "anfordern"
    }

    /** What a module offers the app, and `app`, the union of those on: the app's own keys per list. */
    object AngebotJson {
        const val WIDGETS = "widgets"
        const val TEILEN = "teilen"
        const val KONTO = "konto"
        const val SICHERUNG = "sicherung"
        const val MEHR = "mehr"
    }

    /** `GET /napi/einrichtung` (owner 2026-11-04), beside KannJson's version and module; an older Homes: 404. */
    object EinrichtungJson {
        const val OFFEN = "offen"
        const val ZIELE = "ziele"
        const val EINKAUF = "einkauf"
        const val ZEITLEISTE = "zeitleiste"
        const val SCANNEN = "scannen"

        /** The start screen's areas by key (owner 2026-11-27, homes-android#290): `{stand?, neu?}`. */
        const val BEREICHE = "bereiche"
        const val STAND = ImportStandJson.STAND
        const val NEU = "neu"
    }

    /** One module there: a [WEB] address, or its own app (KannJson's app: name, [PAKET], [STORE]) and [AUFGABE]. */
    object EinrichtungModulJson {
        const val NAME = "name"
        const val TITEL = ModulJson.TITEL
        const val ICON = "icon"
        const val OEFFNEN = "oeffnen" // the app lists it under „Öffnen“, in the answer's order (homes#300)
        const val WEB = "web"
        const val ZIEL = "ziel" // what the row's tap opens instead of WEB, per person: the timeline itself (homes#310)
        const val STAND = ImportStandJson.STAND // one line under the title: „Zuletzt aufgezeichnet heute 08:40“ (homes#310)
        const val NUR_WEB = "nur_web"
        const val PAKET = "paket"
        const val STORE = "store"
        const val AUFGABE = "aufgabe" // its TITEL, [ERLEDIGT] and [EINRICHTEN]
        const val ERLEDIGT = "erledigt"
        const val EINRICHTEN = "einrichten"
        const val DRINGEND = "dringend" // the task's: something waits for it (a decision, a missing key), homes-android#290
        const val NEU = EinrichtungJson.NEU // what is new in it in this release, one line
    }

    /** `POST /napi/musik` (owner 2026-11-04): `{ok, dateien}`, or `{ok: false, error, dateien}` with a German sentence. */
    object MusikJson {
        const val OK = PhotoJson.OK
        const val DATEIEN = StandJson.DATEIEN
        const val ERROR = MerkenJson.ERROR
    }

    /** A refused `POST /napi/kann/anfordern`: `{error}`, a German sentence (401 says `unauthorized`). */
    object AnfordernJson {
        const val ERROR = MerkenJson.ERROR
    }

    /**
     * The app's keys in `/napi/kann`'s lists (homes ADR 0001 §2): the widget
     * types (`tool:` and a tool def's id), the share targets, the Mehr rows.
     */
    object KannKey {
        const val GERAET = "geraet"
        const val ENERGIE = "energie"
        const val TOOL = "tool:"
        const val MERKEN = "merken"
        const val DOKUMENT = "dokument"
        const val STANDORT_IMPORT = "standort-import"
        const val MUSIK = "musik"
        const val DATEN_SICHERN = "daten-sichern"
        const val STANDORTVERLAUF = "standortverlauf"
        const val ARCHIV = "archiv"
        const val KONTAKT_FRAGEN = "kontakt-fragen" // „Daten erfragen“'s component key: on while the kontakte module is (kann/Teilen.kt)
        const val AUSLOESER = "ausloeser" // the geraete module's Mehr row: NFC tags and the charger (homes ADR 0004)
    }

    /**
     * The `/napi/ausloeser` calls' envelope (homes ADR 0004 §1, §2): `{ok: true, …}`; a refusal `{ok: false,
     * error}` with a German sentence (409 a name taken, 404 a tag HA lacks), a 404 `{ok: false, reason: not_found}`
     * when the box has no Home Assistant.
     */
    object AusloeserJson {
        const val OK = PhotoJson.OK
        const val ERROR = MerkenJson.ERROR
        const val REASON = ActionJson.REASON
    }

    /**
     * `POST /napi/ausloeser`: `{ok, name}`, the tag's name in HA, `""` when HA does not know it; a tag with an
     * action of Homes' own (§4, §8) adds its [AKTION] and [TEXT], the sentence the phone shows („Der Scanner
     * startet …“); `scanner-fragen` adds [OEFFNEN], the address on Homes the phone opens (Dokumente's Scannen).
     */
    object GemeldetJson {
        const val NAME = "name"
        const val AKTION = "aktion"
        const val TEXT = "text"
        const val OEFFNEN = "oeffnen"
    }

    /** `POST /napi/ausloeser/tag`: `{ok, tag, uri, aktion}`, the tag's id in HA, what the phone writes onto it, what it does. */
    object NeuerTagJson {
        const val TAG = "tag"
        const val URI = "uri"
        const val AKTION = GemeldetJson.AKTION
    }

    /**
     * `GET /napi/ausloeser/tags`: `{ok, tags: [{tag, name, aktion, automationen: [{id, name}]}], vorschlaege,
     * aktionen: [{id, titel}]}`, HA's tag registry with the automations that trigger on each tag (§8), names to
     * offer (§2), and the actions this box offers a tag, in order, HA's (`""`) first (§4, §8); per tag its last
     * run [ZULETZT] `{wann, person?, text}` (§9), absent before the first.
     */
    object TagsJson {
        const val TAGS = "tags"
        const val TAG = NeuerTagJson.TAG
        const val NAME = GemeldetJson.NAME
        const val AKTION = GemeldetJson.AKTION
        const val AUTOMATIONEN = AutomationenJson.AUTOMATIONEN
        const val VORSCHLAEGE = "vorschlaege"
        const val AKTIONEN = "aktionen"
        const val ID = "id"
        const val TITEL = ModulJson.TITEL
        const val ZULETZT = "zuletzt"
        const val WANN = "wann"
        const val PERSON = "person"
        const val TEXT = GemeldetJson.TEXT
    }

    /** `GET /napi/automationen?q=` (§8): `{ok, automationen: [{id, name, an}]}`, HA's automations to link a tag to. */
    object AutomationenJson {
        const val AUTOMATIONEN = "automationen"
        const val ID = TagsJson.ID
        const val NAME = GemeldetJson.NAME
        const val AN = ModulJson.AN
    }

    /**
     * A tag's automation changed (§8, `…/verbinden|loesen|neu`): `{ok, satz}`; without `bestaetigt` the sentence to
     * show first and [BESTAETIGEN] (with [FREIGABE_NOETIG] for one who is no admin); confirmed by a non-admin
     * [WARTET] and the wish's id [FREIGABE]; an admin's new automation the Bote's reply [BOTE].
     */
    object AenderungJson {
        const val SATZ = "satz"
        const val BESTAETIGEN = "bestaetigen"
        const val FREIGABE_NOETIG = "freigabe_noetig"
        const val WARTET = "wartet"
        const val FREIGABE = "freigabe"
        const val BOTE = "bote"
    }

    /** `GET /napi/ausloeser/freigabe/{id}`: `{ok, stand, satz, wunsch}`, where a wish stands ([STAND_WARTET] …). */
    object FreigabeJson {
        const val STAND = ImportStandJson.STAND
        const val SATZ = AenderungJson.SATZ
        const val WUNSCH = "wunsch"
    }

    /** A wish's `stand`: still waiting; the others (`freigegeben`, `abgelehnt`, `verfallen`) are ends, said in its `satz`. */
    const val STAND_WARTET = AenderungJson.WARTET

    /** The body field that confirms a change (§8): absent, Homes only says what it would do. */
    private const val BESTAETIGT = "bestaetigt"
    private const val AUTOMATION = "automation"

    /** A 404's `reason`: here, the box has no Home Assistant (module geraete off). */
    const val REASON_NOT_FOUND = "not_found"

    /** What a report is (`art`); of the charger whether it charges now (`zustand`) and how (`ladeart`). */
    enum class Art(val wire: String) { NFC("nfc"), LADEN("laden") }
    enum class Ladeart(val wire: String) { KABELLOS("kabellos"), KABEL("kabel"), USB("usb"), UNBEKANNT("") }
    const val ZUSTAND_AN = "an"
    const val ZUSTAND_AUS = "aus"

    // --- the calls -------------------------------------------------------------

    /** The household assistant's name („Homes“ when the box sets none). */
    val ASSISTENT = Endpoint(Method.GET, "/napi/assistent", reply = listOf(AssistentJson))

    val BOX_HOME = Endpoint(Method.GET, "/napi/box/home", reply = listOf(BoxHomeJson)) // only whether all is well, for the claim
    /** Who owns a device token: the pairing checks a new token here before storing it. */
    val WHOAMI = Endpoint(Method.GET, "/napi/whoami", reply = listOf(WhoamiJson))

    /** Switch one entity; a 403 asks for `confirmed`. */
    val HA_CALL = Endpoint(Method.POST, "/napi/ha/call", body = listOf("entity_id", "service", "data", "confirmed"))

    /** One entity's card. */
    val STATE = Endpoint(Method.GET, "/napi/portal/state", query = listOf("entity_id"), reply = listOf(StateJson, CardJson))

    val ENERGY = Endpoint(Method.GET, "/napi/portal/energy", reply = listOf(EnergyJson))

    /** The person's favorite devices, the start screen's first rows. */
    val FAVORITES = Endpoint(Method.GET, "/napi/portal/favorites", reply = listOf(FavoritesJson, CardJson))

    /** What the app-icon menu offers. */
    val ADDABLE = Endpoint(Method.GET, "/napi/portal/start/addable", reply = listOf(AddableJson))

    /** Which entities this device's widgets show; 503 while HA is down. */
    val WATCH = Endpoint(Method.POST, "/napi/portal/watch", body = listOf("entity_ids"))

    /** The live stream: `card_state` frames, and `gemeinsam` for the Homes account. */
    val EVENTS = Endpoint(
        Method.GET, "/napi/portal/events",
        reply = listOf(EventJson, CardJson, GemeinsamFrameJson), accept = "text/event-stream",
        events = listOf(CARD_STATE, GEMEINSAM_FRAME),
    )

    /** The tool catalog. */
    val TOOL_DEFS = Endpoint(Method.GET, "/napi/defs/tool", reply = listOf(ToolDefJson, CellSchemaJson))

    /** A tool's rows, at the path its catalog entry names ([Endpoint.at]). */
    val TOOL_ROWS = Endpoint(Method.GET, "/napi/portal/{tool}", reply = listOf(ToolRowJson))

    /** A tool row's action. */
    val ACTION_CALLBACK = Endpoint(
        Method.POST, "/napi/action-callback",
        body = listOf("action_id", "params", "confirmed"), reply = listOf(ActionJson),
    )

    /**
     * Share → Homes, a photo (owner, 2026-11-02: „Foto machen und mit der Homes
     * App teilen“): multipart, the name, then the file; Homes puts it into the
     * share Paperless consumes. One file a request.
     */
    val UPLOAD = Endpoint(Method.POST, "/napi/upload", body = listOf("filename", "file"), reply = listOf(PhotoJson))

    /** Share → Homes: what the phone shared, kept as a note when a link is in it. */
    val MERKEN = Endpoint(Method.POST, "/napi/merken", body = listOf("text"), reply = listOf(MerkenJson))

    // --- Daten sichern (#194, laib ADR 0029; Homes' side is homes#207) ---

    /** What reached the box per source, and the room left. */
    val SICHERUNG_STAND = Endpoint(Method.GET, "/napi/sicherung/stand", reply = listOf(StandJson))

    /** Which of these WhatsApp media Homes does not have yet: at most [FEHLEND_MAX] per call. */
    val SICHERUNG_FEHLEND = Endpoint(
        Method.POST, "/napi/sicherung/fehlend",
        body = listOf("quelle", "dateien"), reply = listOf(FehlendJson),
    )

    /** The most files one `fehlend` call asks about. */
    const val FEHLEND_MAX = 5000

    /**
     * Start a resumable upload, or resume the partial one of the same source, path, size and
     * mtime (`geaendert`, epoch ms): WhatsApp's msgstore keeps its name daily and can keep its size.
     */
    val SICHERUNG_UPLOADS = Endpoint(
        Method.POST, "/napi/sicherung/uploads",
        body = listOf("quelle", "pfad", "groesse", "geaendert"), reply = listOf(UploadJson),
    )

    /** How far an upload got: 204 with [UPLOAD_OFFSET], 404 when Homes does not know it. */
    val SICHERUNG_UPLOAD_STAND = Endpoint(
        Method.HEAD, "/napi/sicherung/uploads/{id}", replyHeaders = listOf(UPLOAD_OFFSET),
    )

    /** The next bytes of an upload, at [UPLOAD_OFFSET]: at most 16 MiB a request. */
    val SICHERUNG_UPLOAD_TEIL = Endpoint(
        Method.PATCH, "/napi/sicherung/uploads/{id}", reply = listOf(TeilJson), headers = listOf(UPLOAD_OFFSET),
    )

    /**
     * The person's archive Briefkasten as an age X25519 recipient: SMS and
     * calls are encrypted to it on the phone before they go up as `quelle`
     * `sms` (laib ADR 0029). 404 with Homes' sentence when there is none.
     */
    val SICHERUNG_SCHLUESSEL = Endpoint(Method.GET, "/napi/sicherung/schluessel", reply = listOf(SchluesselJson))

    /** Sicherung zurückholen (homes#209): the newest backup per source the box keeps for a new phone. */
    val SICHERUNG_LETZTE = Endpoint(Method.GET, "/napi/sicherung/letzte", reply = listOf(LetzteJson))

    /**
     * One of those files from [RANGE] on: 206 with [CONTENT_RANGE] (200 when Homes
     * sends it whole), and its [ETAG]. A WhatsApp medium comes only while the
     * person's archive is open (423 with Homes' sentence).
     */
    val SICHERUNG_LETZTE_DATEI = Endpoint(
        Method.GET, "/napi/sicherung/letzte/{quelle}/{pfad...}",
        reply = listOf(LetzteDateiJson), headers = listOf(RANGE), replyHeaders = listOf(CONTENT_RANGE, ETAG),
    )

    /**
     * The phone's contacts into the person's own address book „Handy-Kontakte“
     * on the box: every card, each with a UID, as [VCARD]; Homes mirrors the set.
     */
    val KONTAKTE = Endpoint(Method.POST, "/napi/kontakte", reply = listOf(KontakteJson))

    /**
     * Daten erfragen (owner 2026-11-28): a shared card's name, numbers and addresses; Homes finds the contact in the
     * household's address book ([FragenJson.UID] the one picked, `anlegen` makes it first) and answers the link
     * that asks its person for what is missing, with the words to send.
     */
    val KONTAKT_FRAGEN = Endpoint(
        Method.POST, "/napi/kontakte/fragen", body = listOf("name", "telefon", "mail", "anlegen", "uid"), reply = listOf(FragenJson),
    )

    /** Teilen → „Kontaktdaten eintragen lassen“ (owner 2026-11-28): a one-time link, fetched only when chosen. */
    val KONTAKT_EINLADEN = Endpoint(Method.POST, "/napi/kontakte/einladen", reply = listOf(EinladenJson))

    /** Kontakt austauschen: a session, its link for the other phone; `vorname` the name its page shows. */
    val TEILEN_NEU = Endpoint(Method.POST, "/napi/teilen", body = listOf("vorname"), reply = listOf(TeilenNeuJson))

    /** What the session's page shows, all of it each time: a part left out or null is taken back. */
    val TEILEN_SETZEN = Endpoint(
        Method.PUT, "/napi/teilen/{id}", body = listOf("vorname", "karte", "wlan", "eintragen"), reply = listOf(TeilenOkJson),
    )

    /** Whether the page was opened and the form sent. */
    val TEILEN_STAND = Endpoint(Method.GET, "/napi/teilen/{id}", reply = listOf(TeilenStandJson))

    /** „Beenden“: the page says it ended. */
    val TEILEN_ENDE = Endpoint(Method.DELETE, "/napi/teilen/{id}", reply = listOf(TeilenOkJson))

    /**
     * The Homes account (#206): the household's shared contacts, as [VCARD].
     * 304 when [IF_NONE_MATCH] names the state Homes has.
     */
    /** The accepted corrections of the person's own contacts, for the phone to write (owner 2026-11-07). */
    val KORREKTUREN = Endpoint(Method.GET, "/napi/kontakte/korrekturen", reply = listOf(KorrekturenJson))

    /** What the phone wrote of one: `{geschrieben: [nr…]}`; every other accepted change of it it could not. */
    val KORREKTUR_ERLEDIGT = Endpoint(
        Method.POST, "/napi/kontakte/korrekturen/{id}", body = listOf("geschrieben"), reply = listOf(KorrekturErledigtJson),
    )

    val GEMEINSAM_KONTAKTE = Endpoint(
        Method.GET, "/napi/gemeinsam/kontakte", query = listOf(SCHREIBBAR_QUERY), allowed = mapOf(SCHREIBBAR_QUERY to listOf("1")),
        reply = listOf(GemeinsamJson), headers = listOf(IF_NONE_MATCH), replyHeaders = listOf(ETAG),
    )

    /**
     * The Homes account: the household's shared calendars and the reminders,
     * as iCalendar, `text/calendar`, one VCALENDAR per calendar ([FeedIcal]); a month back and
     * a year ahead, series whole with their changed instances and alarms ([SCHREIBBAR_QUERY]).
     */
    val GEMEINSAM_KALENDER = Endpoint(
        Method.GET, "/napi/gemeinsam/kalender", query = listOf(SCHREIBBAR_QUERY), allowed = mapOf(SCHREIBBAR_QUERY to listOf("1")),
        reply = listOf(GemeinsamJson), headers = listOf(IF_NONE_MATCH), replyHeaders = listOf(ETAG),
    )

    /**
     * The Homes account, writable (owner, 2026-11-05): one appointment ([ICAL]) or card ([VCARD]) by its UID, new
     * ([IF_NONE_MATCH] `*`) or over its [FeedIcal.ETAG] ([IF_MATCH]); Homes' new [ETAG]. 412: Homes' version wins.
     */
    val GEMEINSAM_TERMIN = Endpoint(
        Method.PUT, "/napi/gemeinsam/kalender/{kalender}/{uid}", reply = listOf(GemeinsamJson),
        headers = listOf(IF_MATCH, IF_NONE_MATCH), replyHeaders = listOf(ETAG),
    )
    val GEMEINSAM_KARTE = Endpoint(
        Method.PUT, "/napi/gemeinsam/kontakte/{uid}", reply = listOf(GemeinsamJson),
        headers = listOf(IF_MATCH, IF_NONE_MATCH), replyHeaders = listOf(ETAG),
    )

    /** A delete from the Homes account, over its version: into Homes' Papierkorb (30 days). */
    val GEMEINSAM_TERMIN_WEG = Endpoint(
        Method.DELETE, "/napi/gemeinsam/kalender/{kalender}/{uid}", reply = listOf(GemeinsamJson), headers = listOf(IF_MATCH),
    )
    val GEMEINSAM_KARTE_WEG = Endpoint(Method.DELETE, "/napi/gemeinsam/kontakte/{uid}", reply = listOf(GemeinsamJson), headers = listOf(IF_MATCH))

    /** Standortverlauf: whether the person connected Reitti on Homes. */
    val STANDORT_STAND = Endpoint(Method.GET, "/napi/standort", reply = listOf(StandortStandJson))

    /**
     * Standortverlauf: a batch of points, `{punkte: [PunktJson]}`, which Homes
     * passes on to the person's Reitti.
     */
    val STANDORT = Endpoint(Method.POST, "/napi/standort", body = listOf("punkte"), reply = listOf(StandortJson))

    // --- Standortverlauf übernehmen (owner 2026-11-03): a history shared to the app ---

    /**
     * Start the upload of a shared history file ([name] as the phone has it,
     * its size in bytes), or resume the one of the same name and size: its id
     * and how much Homes has. While its import runs the offset is the size.
     */
    val STANDORT_IMPORT = Endpoint(
        Method.POST, "/napi/standort/import", body = listOf("name", "groesse"), reply = listOf(ImportJson),
    )

    /** How far the upload got: 204 with [UPLOAD_OFFSET], 404 when Homes does not know it. */
    val STANDORT_IMPORT_OFFSET = Endpoint(
        Method.HEAD, "/napi/standort/import/{id}", replyHeaders = listOf(UPLOAD_OFFSET),
    )

    /** The next bytes, at [UPLOAD_OFFSET]: at most 16 MiB a request; the last starts the import. */
    val STANDORT_IMPORT_TEIL = Endpoint(
        Method.PATCH, "/napi/standort/import/{id}", reply = listOf(TeilJson), headers = listOf(UPLOAD_OFFSET),
    )

    /** How far the import got, and at the end what came over or why not. */
    val STANDORT_IMPORT_STAND = Endpoint(Method.GET, "/napi/standort/import/{id}", reply = listOf(ImportStandJson))

    // --- what the box can do (homes ADR 0001) ---

    /**
     * The box's modules and what the app may offer; [ETAG] for next time,
     * 304 when [IF_NONE_MATCH] names it.
     */
    val KANN = Endpoint(
        Method.GET, "/napi/kann", reply = listOf(KannJson, ModulJson, AngebotJson, KannEigenJson),
        headers = listOf(IF_NONE_MATCH), replyHeaders = listOf(ETAG),
    )

    /** A wish for an off module, for the device's person: the module's entry with its new state. */
    val KANN_ANFORDERN = Endpoint(
        Method.POST, "/napi/kann/anfordern", body = listOf("modul"),
        reply = listOf(ModulJson, AngebotJson, AnfordernJson),
    )

    /** Songs and ZIPs shared to Homes' music, one `file` part each (the Hochladen job); up to 4 GB a file. */
    val MUSIK = Endpoint(Method.POST, "/napi/musik", body = listOf("file"), reply = listOf(MusikJson))

    /** The person's modules and setup tasks (owner 2026-11-04); `/apps` ticks those whose app the phone has. */
    val EINRICHTUNG = Endpoint(Method.GET, "/napi/einrichtung", reply = listOf(KannJson, EinrichtungJson, EinrichtungModulJson))
    val EINRICHTUNG_APPS = Endpoint(Method.POST, "/napi/einrichtung/apps", body = listOf("pakete"), reply = EINRICHTUNG.reply)

    // --- Auslöser (homes ADR 0004 §1): the phone reports, HA runs the automation ---

    /** An NFC tag touched (`art`, `tag`) or the charger (`art`, `zustand`, `ladeart`); Homes fires HA's event. */
    val AUSLOESER = Endpoint(
        Method.POST, "/napi/ausloeser", body = listOf("art", "tag", "zustand", "ladeart"), reply = listOf(AusloeserJson, GemeldetJson),
    )

    /**
     * A tag named in HA's registry (§2): without `tag` a new id, with it that id named or renamed (the tag
     * itself is not written again); its id and the URI the phone writes onto a new one.
     */
    val AUSLOESER_TAG = Endpoint(
        Method.POST, "/napi/ausloeser/tag", body = listOf("name", "tag", "aktion"), reply = listOf(AusloeserJson, NeuerTagJson),
    )

    /** A tag removed from HA, name and id: automations that use it no longer run. */
    val AUSLOESER_TAG_WEG = Endpoint(Method.DELETE, "/napi/ausloeser/tag/{tag}", reply = listOf(AusloeserJson))

    /** HA's tags, each `{tag, name}`, and the names to offer, for the Auslöser screen. */
    val AUSLOESER_TAGS = Endpoint(
        Method.GET, "/napi/ausloeser/tags", reply = listOf(AusloeserJson, TagsJson),
    )

    // --- a tag's automations (homes ADR 0004 §8): the sentence first, then confirmed ---

    /** HA's automations by name, [q] a part of it, to link a tag to. */
    val AUTOMATIONEN = Endpoint(Method.GET, "/napi/automationen", query = listOf("q"), reply = listOf(AusloeserJson, AutomationenJson))

    /** The tag as one more trigger of an automation; Homes keeps the config before for „Lösen“. */
    val TAG_VERBINDEN = Endpoint(
        Method.POST, "/napi/ausloeser/tag/{tag}/verbinden", body = listOf(AUTOMATION, BESTAETIGT), reply = listOf(AusloeserJson, AenderungJson),
    )

    /** The tag out of an automation: its config before back. */
    val TAG_LOESEN = Endpoint(
        Method.POST, "/napi/ausloeser/tag/{tag}/loesen", body = listOf(AUTOMATION, BESTAETIGT), reply = listOf(AusloeserJson, AenderungJson),
    )

    /** A new automation for the tag, as one sentence, passed to the Bote. */
    val TAG_NEU = Endpoint(
        Method.POST, "/napi/ausloeser/tag/{tag}/neu", body = listOf(AenderungJson.SATZ, BESTAETIGT), reply = listOf(AusloeserJson, AenderungJson),
    )

    /** Where a wish stands that waits for an admin; polled while the Auslöser screen is open, never in the background. */
    val TAG_FREIGABE = Endpoint(Method.GET, "/napi/ausloeser/freigabe/{id}", reply = listOf(AusloeserJson, FreigabeJson))
}
