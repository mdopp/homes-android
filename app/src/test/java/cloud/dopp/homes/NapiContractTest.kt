package cloud.dopp.homes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * The app calls Homes the way Homes answers (#176, laib ADR 0022: Homes' /napi
 * is the app's API).
 *
 * `data/Napi.kt` declares every call as data — method, path, the query
 * parameters and body fields it sends, the reply keys it reads — and the
 * client builds its requests from that data alone. This test holds the data
 * to [HOMES], a copy of what Homes' handlers read and send, each row naming the
 * Homes file and lines it was copied from (mdopp/homes at 8c1251e). When Homes
 * changes a handler, the row changes with it, in the same breath.
 *
 * The rows of Daten sichern (`/napi/sicherung/…`, #194) are copied from
 * Homes' PR for homes#207 (mdopp/homes#208 at ad8c269, not merged yet): when it
 * merges, the commit above moves on and the lines are checked again. The
 * archive key and the contacts (laib ADR 0029) are copied from mdopp/homes
 * main at 7c88b6c (the merge of homes#210); Standortverlauf from main at
 * 145ec05 (the merge of homes#212). The favorites and the energy totals'
 * `today` (owner, 2026-11-02) from mdopp/homes#213 at a709b9f. A module's
 * `ziel` and `stand` of `/napi/einrichtung` from mdopp/homes#310 at 9a8d1f3.
 *
 * The declarations are read by reflection: every static field of `Napi` is
 * looked at, so a path declared outside an endpoint is caught too. The
 * sources are read as text for what reflection cannot see: that no other file
 * spells a path, a key or the auth header by hand, and which pages the app
 * opens in the browser.
 */
class NapiContractTest {

    /** What one Homes handler reads and sends. [query] maps a parameter to its closed set of values, or null for any. */
    class Route(
        val method: String,
        val path: String,
        val at: String,
        val query: Map<String, Set<String>?> = emptyMap(),
        val body: Set<String> = emptySet(),
        val required: Set<String> = emptySet(),
        val sends: Set<String> = emptySet(),
        val accept: String? = null,
        val events: List<String> = emptyList(),
        val headers: Set<String> = emptySet(),
    ) {
        override fun toString() = "$method $path ($at)"
    }

    companion object {
        /** A card: internal/napi/home.go:164-193, its control attributes home.go:43-45, its page :177. */
        val CARD = setOf(
            "entity_id", "name", "domain", "state", "page", "device_class", "unit", "updated_at_ms",
            "supported_features", "brightness", "color_temp", "rgb_color", "color_mode", "supported_color_modes",
            "current_position", "temperature", "current_temperature", "hvac_action", "target_temp_step", "min_temp",
            "max_temp", "hvac_modes", "volume_level", "media_title", "media_artist",
        )

        /** One energy meter: internal/napi/energy.go:87-100, labelled at :145, :155, :183, :192. */
        val METER = setOf("entity_id", "name", "state", "device_class", "unit", "label", "sense")

        /** One module of `/napi/kann`: `modul.KannEins`, internal/modul/modul.go:234-248, `Angebot` :41-47, `Wunsch` :181-184. */
        val KANN_MODUL = setOf(
            "name", "titel", "an", "backend", "standard", "warum", "grund", "bietet", "admins", "spur_id", "spur",
            "wunsch", "stand", "seit", "anfordern", "widgets", "teilen", "konto", "sicherung", "mehr",
        )

        /** `/napi/einrichtung`'s answer: `einrichten.Antwort`, `NapiModul`, `NapiApp`, `NapiAufgabe` and the ziele's keys. */
        val EINRICHTUNG = setOf(
            "version", "offen", "module", "ziele", "name", "titel", "icon", "oeffnen", "web", "ziel", "stand", "app", "nur_web",
            "aufgabe", "paket", "store", "erledigt", "am", "wie", "einrichten", "einkauf", "zeitleiste", "scannen",
        )

        /** Every /napi route Homes serves, as its handler reads and answers it. */
        val HOMES = listOf(
            Route("GET", "/napi/assistent", "internal/napi/napi.go:104-106", sends = setOf("name")),
            Route("GET", "/napi/whoami", "internal/napi/napi.go:181-190", sends = setOf("uid", "via")),
            Route(
                "GET", "/napi/box/home",
                "internal/napi/napi.go:85-108 (prefix: internal/app/app.go:17; updates apart: napi.go:105, 224-235)",
                sends = setOf(
                    "servicesUp", "servicesFailed", "servicesDown", "pendingApprovals", "pendingUpdates",
                    "state", "drift", "lastHeard", "operator",
                ),
            ),
            Route(
                "GET", "/napi/box/services", "internal/napi/napi.go:109-127",
                sends = setOf("services", "name", "activeState", "subState", "health", "detail"),
            ),
            Route(
                "GET", "/napi/box/approvals", "internal/napi/napi.go:128-144, the row napi.go:238-244",
                sends = setOf("approvals", "id", "service", "title", "description", "created_at", "status"),
            ),
            Route(
                "GET", "/napi/box/upgrades", "internal/napi/napi.go:145-156, the row napi.go:238-244",
                sends = setOf("upgrades", "id", "service", "title", "description", "created_at"),
            ),
            Route(
                "POST", "/napi/box/services/{name}/operate", "internal/napi/napi.go:157-172",
                body = setOf("action"), required = setOf("action"), sends = setOf("ok", "name", "action"),
            ),
            Route("POST", "/napi/box/approvals/{id}/approve", "internal/napi/napi.go:176-179"),
            Route("POST", "/napi/box/approvals/{id}/deny", "internal/napi/napi.go:176-180"),
            Route(
                "POST", "/napi/ha/call", "internal/napi/home.go:84-140",
                body = setOf("entity_id", "service", "data", "confirmed"),
                required = setOf("entity_id", "service"), sends = setOf("ok", "error"),
            ),
            Route(
                "GET", "/napi/portal/state", "internal/napi/home.go:195-217",
                query = mapOf("entity_id" to null), required = setOf("entity_id"),
                sends = setOf("ok", "error", "card") + CARD,
            ),
            Route(
                "GET", "/napi/portal/energy",
                "internal/napi/energy.go:49-81, today :67 and :83-125 (homes#213); charge :73-76 and :89-107 (mdopp/homes#228 at b97b1c2, not merged yet)",
                sends = setOf("ok", "error", "energy", "flow", "totals", "circuits", "today", "charge") + METER,
            ),
            Route(
                "GET", "/napi/portal/entity-history", "internal/napi/home.go:219-252, ranges home.go:47",
                query = mapOf("entity_id" to null, "range" to setOf("24h", "48h", "7d")),
                required = setOf("entity_id"), sends = setOf("ok", "error", "history", "t", "state"),
            ),
            Route(
                "GET", "/napi/portal/active", "internal/napi/home.go:254-291, the same set as active_set: live.go:324-350",
                sends = setOf("ok", "error", "active", "room") + CARD,
            ),
            Route(
                "GET", "/napi/portal/favorites", "internal/napi/home.go:199-244, registered :62 (homes#213)",
                sends = setOf("ok", "error", "favorites") + CARD,
            ),
            Route("GET", "/napi/portal/camera/{entity_id}/snapshot", "internal/napi/camera.go:27-53"),
            Route(
                "GET", "/napi/portal/start/addable", "internal/napi/camera.go:54-104",
                sends = setOf(
                    "ok", "error", "rooms", "room", "cards", "entity_id", "name", "domain", "state", "device_class",
                    "automations",
                ),
            ),
            Route(
                "POST", "/napi/portal/watch", "internal/napi/live.go:106-145",
                body = setOf("entity_ids", "active"), required = setOf("entity_ids"), sends = setOf("ok", "error"),
            ),
            Route(
                "GET", "/napi/portal/events",
                "internal/napi/live.go:147-180, card_state live.go:432-434, active_set live.go:376-397; " +
                    "gemeinsam {teil: kontakte|kalender} (mdopp/homes main at e5d208c, the merge of homes#224 for homes#223) live.go:471-518",
                sends = setOf("entity_id", "card", "entity_ids", "teil") + CARD, accept = "text/event-stream",
                events = listOf("card_state", "active_set", "gemeinsam"),
            ),
            Route(
                "GET", "/napi/defs/tool", "internal/napi/tools.go:77-102",
                sends = setOf(
                    "ok", "kind", "defs", "tool-id", "tool-label", "tool-api-path", "tool-cell-schema", "tool-actions",
                    "tool-actions-titled", "tool-action-params", "id", "title",
                ),
            ),
            Route(
                "POST", "/napi/action-callback",
                "internal/napi/tools.go:276-336 (confirmed: named at :276, read by no action Homes has)",
                body = setOf("action_id", "params", "confirmed"), required = setOf("action_id"),
                sends = setOf("ok", "reason"),
            ),
            Route(
                "GET", "/napi/portal/tasks", "internal/napi/tools.go:55, 121-177, the page :165",
                query = mapOf("done" to setOf("1")),
                sends = setOf("ok", "error", "tasks", "id", "page", "title", "status", "due"),
            ),
            Route(
                "GET", "/napi/portal/contacts", "internal/napi/tools.go:58, 179-218, the page :210",
                sends = setOf("ok", "error", "contacts", "id", "page", "name", "phone", "email", "birthday"),
            ),
            Route(
                "GET", "/napi/portal/documents", "internal/napi/tools.go:61, 220-249, the page :244",
                sends = setOf("ok", "error", "documents", "id", "title", "created", "correspondent", "link", "page"),
            ),
            Route(
                "GET", "/napi/portal/notes", "internal/napi/tools.go:64, 251-265",
                sends = setOf("ok", "error", "notes", "id", "page", "title", "changed"),
            ),
            Route(
                "POST", "/napi/upload", "internal/napi/upload.go:144-145, the parts upload.go:214-232",
                body = setOf("filename", "file"), required = setOf("file"), sends = setOf("ok", "error", "id", "files"),
            ),
            Route(
                "POST", "/napi/merken", "internal/napi/merken.go:49-94, registered napi.go:233 (homes#120)",
                body = setOf("text"), required = setOf("text"),
                sends = setOf("titel", "art", "kurz", "schon", "teilweise", "notiz", "error"),
            ),
            // Daten sichern (homes#208 at ad8c269, routes sicherung.go:112-118; errors {fehler} :121-124).
            Route(
                "GET", "/napi/sicherung/stand", "internal/napi/sicherung.go:283-291, the shape :208-220; sozial since homes#341",
                sends = setOf("quellen", "whatsapp", "signal", "sms", "sozial", "zuletzt", "dateien", "bytes", "wartend", "frei_bytes", "fehler"),
            ),
            Route(
                "POST", "/napi/sicherung/fehlend", "internal/napi/sicherung.go:301-339, an entry :294-297",
                body = setOf("quelle", "dateien", "pfad", "groesse"), required = setOf("quelle", "dateien"),
                sends = setOf("fehlend", "fehler"),
            ),
            Route(
                "POST", "/napi/sicherung/uploads", "internal/napi/sicherung.go:479-539",
                body = setOf("quelle", "pfad", "groesse", "geaendert"), required = setOf("quelle", "pfad", "groesse"),
                sends = setOf("id", "offset", "fehler"),
            ),
            Route(
                "HEAD", "/napi/sicherung/uploads/{id}", "internal/napi/sicherung.go:541-555",
                headers = setOf("Upload-Offset"),
            ),
            Route(
                "PATCH", "/napi/sicherung/uploads/{id}", "internal/napi/sicherung.go:588-676",
                sends = setOf("offset", "fertig", "fehler"), headers = setOf("Upload-Offset"),
            ),
            // The archive key and the contacts (mdopp/homes main at 7c88b6c, the merge of homes#210).
            Route(
                "GET", "/napi/sicherung/schluessel", "internal/napi/sicherung.go:304-318, registered :129",
                sends = setOf("empfaenger", "fehler"),
            ),
            Route(
                "POST", "/napi/kontakte",
                "internal/napi/kontakte.go:49-122 (text/vcard :65-69), registered napi.go:242, the reply internal/caldav/spiegel.go:277-282",
                sends = setOf("kontakte", "neu", "geaendert", "entfernt", "fehler"),
            ),
            // Kontakt prüfen lassen (owner 2026-11-07 and 2026-11-08; mdopp/homes feat/kontakt-v2, homes#304; registered korrekturen.go:101-107, napi.go:329)
            Route(
                "GET", "/napi/kontakte/korrekturen",
                "internal/napi/korrekturen.go:54-71, a correction :33-38 and its change :28-31, the change internal/kontaktantwort/felder.go:246-259, an address felder.go:14-21 (strasse the one street line, Zeilenweise)",
                sends = setOf(
                    "korrekturen", "id", "uid", "name", "aenderungen", "nr", "art", "vorher", "nachher", "vorher_art", "nachher_art",
                    "vorher_haupt", "nachher_haupt", "vorher_adresse", "nachher_adresse", "strasse", "plz", "ort", "land", "bild", "fehler",
                ),
            ),
            Route(
                "POST", "/napi/kontakte/korrekturen/{id}", "internal/napi/korrekturen.go:75-99",
                body = setOf("geschrieben"), sends = setOf("ok", "fehler"),
            ),
            // Sicherung zurückholen (mdopp/homes main at 433fb1a, the merge of homes#219 for homes#209; registered sicherung.go:131-132).
            Route(
                "GET", "/napi/sicherung/letzte", "internal/napi/sicherung_letzte.go:211-220, an entry :105-109",
                sends = setOf("quellen", "signal", "whatsapp", "pfad", "groesse", "geaendert", "fehler"),
            ),
            Route(
                "GET", "/napi/sicherung/letzte/{quelle}/{pfad...}",
                "internal/napi/sicherung_letzte.go:222-256 (http.ServeContent: Range, Content-Range; ETag :254), media :258-307",
                sends = setOf("fehler"), headers = setOf("Range", "Content-Range", "ETag"),
            ),
            // The Homes account (mdopp/homes main at 7adc744, the merge of homes#220, homes#218): vCard and iCalendar, refusals {fehler}.
            // Writable (owner 2026-11-05): mdopp/homes main at 2b48e4a, the merge of homes#278; ?schreibbar=1 and the writes; the app reads the ETag header.
            // Pictures (owner 2026-11-05): mdopp/homes main at b587744, the merge of homes#287; a card's Immich face (homes-foto.PHOTO,
            // X-HOMES=immich) and the Fotos link added internal/pages/kontakt_sprung.go:185-241, dropped again on a write :255-277.
            Route(
                "GET", "/napi/gemeinsam/kontakte",
                "internal/napi/gemeinsam.go:85-105 (schreibbar :86, each card through Karte :100), the person and refusals :63-82, " +
                    "ETag :198-213, registered :54, napi.go:328; the face and links internal/pages/kontakt_sprung.go:185-241",
                query = mapOf("schreibbar" to setOf("1")), sends = setOf("fehler"), headers = setOf("If-None-Match", "ETag"),
            ),
            Route(
                "GET", "/napi/gemeinsam/kalender",
                "internal/napi/gemeinsam.go:109-120 (schreibbar :111), the stream internal/caldav/gemeinsam.go, ETag :198-213, registered :55",
                query = mapOf("schreibbar" to setOf("1")), sends = setOf("fehler"), headers = setOf("If-None-Match", "ETag"),
            ),
            Route(
                "PUT", "/napi/gemeinsam/kalender/{kalender}/{uid}",
                "internal/napi/gemeinsam.go:130-176 (412 :155-159), internal/caldav/schreiben.go, registered :56-59",
                sends = setOf("fehler"), headers = setOf("If-Match", "If-None-Match", "ETag"),
            ),
            Route(
                "PUT", "/napi/gemeinsam/kontakte/{uid}", "internal/napi/gemeinsam.go:130-176, registered :56-59",
                sends = setOf("fehler"), headers = setOf("If-Match", "If-None-Match", "ETag"),
            ),
            Route(
                "DELETE", "/napi/gemeinsam/kalender/{kalender}/{uid}", "internal/napi/gemeinsam.go:130-176, the Papierkorb :180-196",
                sends = setOf("fehler"), headers = setOf("If-Match"),
            ),
            Route(
                "DELETE", "/napi/gemeinsam/kontakte/{uid}", "internal/napi/gemeinsam.go:130-176, the Papierkorb :180-196",
                sends = setOf("fehler"), headers = setOf("If-Match"),
            ),
            // Standortverlauf (mdopp/homes main at 145ec05, the merge of homes#212).
            Route(
                "GET", "/napi/standort",
                "internal/napi/standort.go:115-122 (refusals :88-112), registered :84, napi.go:249",
                sends = setOf("verbunden", "seit", "fehler", "grund"),
            ),
            Route(
                "POST", "/napi/standort",
                "internal/napi/standort.go:126-177, the point :49-56, registered :85, napi.go:249",
                body = setOf("punkte"), sends = setOf("angenommen", "verworfen", "fehler", "grund"),
            ),
            // Standortverlauf übernehmen (mdopp/homes#234 at adc0e27, not merged yet; registered standort_import.go:109-117,
            // standort.go:90; refusals {fehler} sicherung.go, {fehler, grund} standort.go standortFehler).
            Route(
                "POST", "/napi/standort/import", "internal/napi/standort_import.go:190-257",
                body = setOf("name", "groesse"), required = setOf("name", "groesse"),
                sends = setOf("id", "offset", "fehler", "grund"),
            ),
            Route(
                "HEAD", "/napi/standort/import/{id}", "internal/napi/standort_import.go:260-276",
                headers = setOf("Upload-Offset"),
            ),
            Route(
                "PATCH", "/napi/standort/import/{id}", "internal/napi/standort_import.go:280-356",
                sends = setOf("offset", "fertig", "fehler"), headers = setOf("Upload-Offset"),
            ),
            Route(
                "GET", "/napi/standort/import/{id}", "internal/napi/standort_import.go:426-441, the state :75-89",
                sends = setOf(
                    "name", "groesse", "stand", "format", "gelesen", "punkte", "besuche", "von", "bis",
                    "text", "fehler", "grund", "geaendert",
                ),
            ),
            // What the box can do (homes ADR 0001 §2, §10): GET mdopp/homes main at 572fb33 (the merge of homes#254),
            // with bietet, spur_id, spur, wunsch, anfordern and the POST from mdopp/homes#256 at 4936cb1, not merged yet;
            // registered napi.go:226-227, the entry internal/modul/modul.go:222-248, a refused device napi.go:236.
            Route(
                "GET", "/napi/kann", "internal/napi/kann.go:30-62 (ETag :43-49), not_found without modules :31-34 and napi.go:275-277",
                // dokumente_adresse, the device's person's own (owner 2026-11-10): mdopp/homes#338, kann.go:36-38 and modul.go's Kann
                sends = setOf("version", "module", "app", "dokumente_adresse") + KANN_MODUL + setOf("ok", "reason"),
                headers = setOf("If-None-Match", "ETag"),
            ),
            Route(
                "POST", "/napi/kann/anfordern", "internal/napi/kann.go:85-127, the reply the module's entry :122-126",
                body = setOf("modul"), required = setOf("modul"),
                sends = KANN_MODUL + setOf("ok", "reason", "error"),
            ),
            // Teilen → Musik (owner 2026-11-04): mdopp/homes main at e46de44 (the merge of homes#270);
            // registered with the musik module, 404 without it.
            Route(
                "POST", "/napi/musik", "internal/napi/napi.go:317, the handler internal/musik/musik.go:96-148",
                body = setOf("file"), required = setOf("file"), sends = setOf("ok", "dateien", "error", "reason"),
            ),
            // The person's modules and setup tasks (owner 2026-11-04): mdopp/homes main at cc8a5ea (the merge of homes#269);
            // registered napi.go:243-244, not_found napi.go:334.
            Route(
                "GET", "/napi/einrichtung",
                "internal/napi/einrichtung.go:18-45, the answer internal/einrichten/einrichten.go:242-307, ziele cmd/homes/module.go:351-357, ziel and stand :359-368",
                sends = EINRICHTUNG + setOf("ok", "reason"),
            ),
            Route(
                "POST", "/napi/einrichtung/apps", "internal/napi/einrichtung.go:28-44, the GET's answer :44",
                body = setOf("pakete"), sends = EINRICHTUNG + setOf("ok", "reason", "error"),
            ),
        )

        /** Where Homes reads the token: `bearerOf`, internal/napi/napi.go:208-211. */
        const val HOMES_AUTH_HEADER = "Authorization"
        const val HOMES_AUTH_SCHEME = "Bearer"

        /** The domains Homes draws as a card: `cards.Domains`, internal/cards/pages.go:21-25. */
        val HOMES_CARD_DOMAINS = setOf("sensor", "binary_sensor", "cover", "light", "switch", "climate", "media_player", "lock")

        /** The rows paths Homes' tool catalog names: internal/napi/tools.go:81-93. */
        val HOMES_TOOL_PATHS = setOf("/napi/portal/contacts", "/napi/portal/documents", "/napi/portal/notes", "/napi/portal/tasks")

        /** The cell roles Homes' tool catalog uses: internal/napi/tools.go:82-94. */
        val HOMES_CELL_ROLES = setOf("title", "meta", "actions")

        /**
         * Homes' pages (cmd/homes/main.go:61-75). Not "/" and not "/healthz": "/"
         * and every path Homes does not serve land on Start (main.go:77), so a
         * wrong page would never fail loudly — only this list catches it.
         */
        val PAGES = setOf("/start", "/raeume", "/energie", "/kalender", "/notizen", "/box", "/box/geraete", "/pair-device", "/standort", "/daten-sichern", "/meine-daten")

        /** What the widgets and the pairing need: each must be declared. */
        val NEEDED = setOf(
            "GET /napi/assistent", "GET /napi/whoami",
            "POST /napi/ha/call", "GET /napi/portal/state", "GET /napi/portal/energy",
            "GET /napi/portal/favorites", "GET /napi/portal/start/addable",
            "POST /napi/portal/watch", "GET /napi/portal/events",
            "GET /napi/defs/tool", "POST /napi/action-callback", "GET /napi/portal/{tool}", "POST /napi/upload",
            "POST /napi/merken",
            "GET /napi/sicherung/stand", "POST /napi/sicherung/fehlend", "POST /napi/sicherung/uploads",
            "HEAD /napi/sicherung/uploads/{id}", "PATCH /napi/sicherung/uploads/{id}",
            "GET /napi/sicherung/schluessel", "POST /napi/kontakte",
            "GET /napi/sicherung/letzte", "GET /napi/sicherung/letzte/{quelle}/{pfad...}",
            "GET /napi/standort", "POST /napi/standort",
            "POST /napi/standort/import", "HEAD /napi/standort/import/{id}",
            "PATCH /napi/standort/import/{id}", "GET /napi/standort/import/{id}",
            "GET /napi/gemeinsam/kontakte", "GET /napi/gemeinsam/kalender",
            "PUT /napi/gemeinsam/kalender/{kalender}/{uid}", "PUT /napi/gemeinsam/kontakte/{uid}",
            "DELETE /napi/gemeinsam/kalender/{kalender}/{uid}", "DELETE /napi/gemeinsam/kontakte/{uid}",
            "GET /napi/kann", "POST /napi/kann/anfordern",
            "GET /napi/einrichtung", "POST /napi/einrichtung/apps", "POST /napi/musik",
        )

        const val NAPI = "cloud.dopp.homes.data.Napi"
        const val NAPI_FILE = "app/src/main/java/cloud/dopp/homes/data/Napi.kt"

        /** The one OkHttpClient, copied per caller's timeouts. */
        const val HTTP_FILE = "app/src/main/java/cloud/dopp/homes/data/HomesHttp.kt"

        /** The one file that turns an endpoint into an HTTP request. */
        const val REQUEST_FILE = "app/src/main/java/cloud/dopp/homes/data/NapiRequest.kt"

        /** The files that read Homes' replies: their keys come from Napi, never spelled here. */
        val PARSERS = listOf(
            "app/src/main/java/cloud/dopp/homes/data/ApiClient.kt",
            "app/src/main/java/cloud/dopp/homes/data/BoxApiClient.kt",
            "app/src/main/java/cloud/dopp/homes/data/Assistent.kt",
            "app/src/main/java/cloud/dopp/homes/data/ToolDefs.kt",
            "app/src/main/java/cloud/dopp/homes/realtime/RealtimeProtocol.kt",
            "app/src/main/java/cloud/dopp/homes/data/SicherungClient.kt",
            "app/src/main/java/cloud/dopp/homes/data/StandortClient.kt",
            "app/src/main/java/cloud/dopp/homes/data/StandortImportClient.kt",
            "app/src/main/java/cloud/dopp/homes/data/GemeinsamClient.kt",
            "app/src/main/java/cloud/dopp/homes/data/KannClient.kt",
            "app/src/main/java/cloud/dopp/homes/kann/Kann.kt",
            "app/src/main/java/cloud/dopp/homes/kann/KannStore.kt",
            "app/src/main/java/cloud/dopp/homes/einrichtung/Einrichtung.kt",
            "app/src/main/java/cloud/dopp/homes/einrichtung/EinrichtungLeser.kt",
            "app/src/main/java/cloud/dopp/homes/hochladen/MusikClient.kt",
        )

        private val literal = Regex(""""((?:[^"\\]|\\.)*)"""")

        /** Two paths are the same route: equal segments, a `{…}` on either side matches one segment. */
        fun same(a: String, b: String): Boolean {
            val x = a.substringBefore('?').trimEnd('/').split('/')
            val y = b.substringBefore('?').trimEnd('/').split('/')
            return x.size == y.size && x.indices.all { i ->
                x[i] == y[i] || (x[i].startsWith("{") && y[i].isNotEmpty()) || (y[i].startsWith("{") && x[i].isNotEmpty())
            }
        }

        /** A line without its comment: KDoc and `//` lines quote JSON for the reader, not the wire. */
        fun code(line: String): String {
            val t = line.trim()
            if (t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")) return ""
            val i = line.indexOf("// ")
            return if (i >= 0) line.substring(0, i) else line
        }

        /** Every string literal in the main sources' code, with where it stands. */
        fun literals(files: List<File> = RepoFiles.mainKotlin()): List<Pair<String, String>> = files.flatMap { f ->
            f.readLines().flatMapIndexed { i, line ->
                literal.findAll(code(line)).map { "${RepoFiles.relative(f)}:${i + 1}" to it.groupValues[1] }.toList()
            }
        }

        fun file(rel: String) = File(RepoFiles.root, rel)
    }

    // --- Napi's declarations, read by reflection --------------------------------

    /** One declared call, as plain values. */
    class Declared(
        val name: String,
        val method: String,
        val path: String,
        val query: List<String>,
        val allowed: Map<String, List<String>>,
        val body: List<String>,
        val reads: Set<String>,
        val accept: String?,
        val events: List<String>,
        val headers: List<String>,
        val replyHeaders: List<String>,
    ) {
        override fun toString() = "$name ($method $path)"
    }

    private val napi: Class<*> by lazy { Class.forName(NAPI) }

    /** Every static field of [c] but its INSTANCE, by name. */
    private fun statics(c: Class<*> = napi): Map<String, Any?> =
        c.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.name != "INSTANCE" }
            .associate { f -> f.isAccessible = true; f.name to f.get(null) }

    /** The keys a reply shape names: its constant strings. */
    private fun keysOf(shape: Any): Set<String> =
        statics(shape.javaClass).values.filterIsInstance<String>().toSet()

    @Suppress("UNCHECKED_CAST")
    private val declared: List<Declared> by lazy {
        statics().filter { (_, v) -> v != null && v.javaClass.simpleName == "Endpoint" }.map { (name, e) ->
            e!!
            Declared(
                name = name,
                method = Reach.get(e, "method").toString(),
                path = Reach.get(e, "path") as String,
                query = Reach.get(e, "query") as List<String>,
                allowed = Reach.get(e, "allowed") as Map<String, List<String>>,
                body = Reach.get(e, "body") as List<String>,
                reads = (Reach.get(e, "reply") as List<Any>).filter { it.javaClass.simpleName != "CellSchemaJson" }
                    .flatMap { keysOf(it) }.toSet(),
                accept = Reach.get(e, "accept") as String?,
                events = runCatching { Reach.get(e, "events") as List<String> }.getOrDefault(emptyList()),
                headers = Reach.get(e, "headers") as List<String>,
                replyHeaders = Reach.get(e, "replyHeaders") as List<String>,
            )
        }
    }

    /**
     * The Homes routes a declared call reaches: its own; for a path with a `{…}`
     * Homes does not have as such, the routes it is filled in to — for the tool
     * rows, the paths Homes' catalog names, not every route that shape matches.
     */
    private fun routesOf(d: Declared): List<Route> {
        val exact = HOMES.filter { it.method == d.method && it.path == d.path }
        if (exact.isNotEmpty()) return exact
        val reached = HOMES.filter { it.method == d.method && same(d.path, it.path) }
        return if (d.name == "TOOL_ROWS") reached.filter { it.path in HOMES_TOOL_PATHS } else reached
    }

    private fun failures(check: (Declared, Route) -> List<String>): List<String> =
        declared.flatMap { d -> routesOf(d).flatMap { r -> check(d, r).map { "$d against $r: $it" } } }

    @Test
    fun napiDeclaresItsCallsAsEndpoints() {
        assertTrue("Napi declares no endpoint (method, path, query, body, reply)", declared.isNotEmpty())
    }

    @Test
    fun everyCallIsAHomesRouteWithItsMethod() {
        assertTrue("no endpoints", declared.isNotEmpty())
        val wrong = declared.filter { routesOf(it).isEmpty() }
        assertTrue("not served by Homes with that method:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun everyQueryParameterIsOneHomesReads() {
        assertTrue("no endpoints", declared.isNotEmpty())
        val wrong = failures { d, r ->
            val out = mutableListOf<String>()
            d.query.filter { it !in r.query.keys }.forEach { out += "Homes reads no query parameter '$it'" }
            r.required.filter { it in r.query.keys && it !in d.query }.forEach { out += "Homes needs '$it'" }
            for ((k, values) in r.query) {
                if (values == null || k !in d.query) continue
                val sent = d.allowed[k]
                if (sent == null) {
                    out += "'$k' takes only $values in Homes; the app must declare which it sends"
                } else {
                    sent.filter { it !in values }.forEach { out += "Homes refuses $k=$it" }
                }
            }
            out
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun everyBodyFieldIsOneHomesReads() {
        assertTrue("no endpoints", declared.isNotEmpty())
        val wrong = failures { d, r ->
            d.body.filter { it !in r.body }.map { "Homes reads no body field '$it'" } +
                r.required.filter { it in r.body && it !in d.body }.map { "Homes needs body field '$it'" }
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun everyReplyKeyTheAppReadsIsOneHomesSends() {
        assertTrue("no endpoints", declared.isNotEmpty())
        val wrong = failures { d, r -> d.reads.filter { it !in r.sends }.map { "Homes never sends '$it'" } }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    /** The resumable upload's offset header goes and comes where Homes reads and sends it. */
    @Test
    fun everyHeaderIsOneHomesReadsOrSends() {
        assertTrue("no endpoints", declared.isNotEmpty())
        val wrong = failures { d, r -> (d.headers + d.replyHeaders).filter { it !in r.headers }.map { "Homes has no header '$it' here" } }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
        val upload = declared.filter { it.path.startsWith("/napi/sicherung/uploads/") }.associateBy { it.method }
        assertEquals(setOf("HEAD", "PATCH"), upload.keys)
        assertEquals("HEAD reads the offset", listOf("Upload-Offset"), upload.getValue("HEAD").replyHeaders)
        assertEquals("PATCH sends the offset", listOf("Upload-Offset"), upload.getValue("PATCH").headers)
    }

    @Test
    fun theStreamAsksForAndReadsWhatHomesSends() {
        val events = declared.singleOrNull { it.method == "GET" && it.path == "/napi/portal/events" }
        assertTrue("no events endpoint", events != null)
        val route = HOMES.single { it.path == "/napi/portal/events" }
        assertEquals(route.accept, events!!.accept)
        // Homes also sends active_set, but only to a watch that asks for it; the app no longer does
        assertEquals("the frames the app reads", listOf("card_state", "gemeinsam"), events.events)
        assertTrue("a frame Homes does not send", route.events.containsAll(events.events))
    }

    /**
     * What Homes added for the app (#178, homes#30, homes#213) is read: the card's
     * and the tool row's page, a favorite's page, and a total's today.
     */
    @Test
    fun theAppReadsHomesNewFields() {
        val byName = declared.associateBy { it.name }
        assertTrue("a card's page is not read", "page" in byName.getValue("STATE").reads)
        assertTrue("a favorite's page is not read", "page" in byName.getValue("FAVORITES").reads)
        assertTrue("a tool row's page is not read", "page" in byName.getValue("TOOL_ROWS").reads)
        assertTrue("a total's today is not read", "today" in byName.getValue("ENERGY").reads)
    }

    @Test
    fun theAppDeclaresEveryCallItNeeds() {
        val have = declared.map { "${it.method} ${it.path}" }.toSet()
        val missing = NEEDED.filter { it !in have }
        assertTrue("not declared in Napi: $missing", missing.isEmpty())
    }

    @Test
    fun theToolRowsAreThePathsHomesCatalogNames() {
        val rows = declared.singleOrNull { it.name == "TOOL_ROWS" }
        assertTrue("Napi declares no TOOL_ROWS", rows != null)
        assertEquals("GET", rows!!.method)
        HOMES_TOOL_PATHS.forEach { p ->
            assertTrue("$p is not under ${rows.path}", same(p, rows.path))
            assertTrue("$p is not a GET route of Homes", HOMES.any { it.method == "GET" && it.path == p })
        }
    }

    @Test
    fun theCellRolesHomesUsesAreOnesTheAppReads() {
        val shape = napi.declaredClasses.firstOrNull { it.simpleName == "CellSchemaJson" }
        assertTrue("Napi declares no CellSchemaJson", shape != null)
        val roles = keysOf(shape!!.getField("INSTANCE").get(null))
        assertTrue("roles the app does not read: ${HOMES_CELL_ROLES - roles}", roles.containsAll(HOMES_CELL_ROLES))
    }

    @Test
    fun theDeviceWidgetOffersOnlyWhatHomesDrawsAsACard() {
        assertEquals(HOMES_CARD_DOMAINS, statics()["CARD_DOMAINS"])
    }

    /** No path hides in Napi outside an endpoint — a glued `ROOT + "…"` included. */
    @Test
    fun napiHoldsNoPathOutsideAnEndpoint() {
        val stray = statics().filter { (name, v) -> v is String && v.startsWith("/napi") && name != "ROOT" }
        assertTrue("paths that are not endpoints: $stray", stray.isEmpty())
        assertEquals("/napi/", statics()["ROOT"])
    }

    @Test
    fun theTokenGoesWhereHomesReadsIt() {
        val s = statics()
        assertEquals(HOMES_AUTH_HEADER, s["AUTH_HEADER"])
        assertEquals(HOMES_AUTH_SCHEME, s["AUTH_SCHEME"])
    }

    // --- the sources, read as text ----------------------------------------------

    /** One file builds every request from an endpoint: its method, its auth header, its target. */
    @Test
    fun onlyNapiRequestBuildsARequest() {
        val req = file(REQUEST_FILE)
        assertTrue("$REQUEST_FILE does not exist", req.isFile)
        val text = req.readText()
        assertTrue("the method is not the endpoint's", text.contains(".method(method.name"))
        assertTrue("the token is not sent the way Homes reads it", text.contains(".header(Napi.AUTH_HEADER, Napi.bearer("))
        // A request is built from a builder (new or copied) or not at all, and only
        // NapiRequest holds one; the auth header's name and scheme live in Napi.
        val elsewhere = RepoFiles.mainKotlin().filter { RepoFiles.relative(it) != REQUEST_FILE }.flatMap { f ->
            val rel = RepoFiles.relative(f)
            // HomesHttp copies the one OkHttpClient with newBuilder(), a client and no request
            val banned = listOf(".header(", ".addHeader(", ".method(") +
                (if (rel == HTTP_FILE) emptyList() else listOf(".newBuilder()")) +
                (if (rel == NAPI_FILE) emptyList() else listOf("\"Bearer", "\"Authorization\""))
            // OkHttp's Request.Builder; WorkManager's WorkRequest.Builder (the backup job) is no HTTP request
            val builder = Regex("""(?<![A-Za-z])Request\.Builder""")
            f.readLines().mapIndexedNotNull { i, line ->
                val c = code(line)
                (banned.firstOrNull { c.contains(it) } ?: builder.find(c)?.value)?.let { "$rel:${i + 1}: $it" }
            }
        }
        assertTrue("requests built by hand:\n" + elsewhere.joinToString("\n"), elsewhere.isEmpty())
    }

    @Test
    fun noFileButNapiSpellsAPath() {
        val wrong = literals().filter { (at, s) -> s.contains("/napi") && !at.startsWith("$NAPI_FILE:") }
            .map { (at, s) -> "$at: \"$s\"" }
        assertTrue("paths spelled outside Napi:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    /**
     * No tail waits to be glued to a prefix: a directory of Homes' (`portal/`,
     * with or without its slash) or a leaf with its slash (`/upload`). A bare
     * word such as `upload` may be a file name; a path glued from it could still
     * not be called, since only an endpoint becomes a request.
     */
    @Test
    fun noPathIsGluedFromAPrefixAndATail() {
        val dirs = listOf("portal/", "box/", "defs/", "ha/")
        val leaves = listOf("/upload", "/action-callback", "/assistent", "/whoami")
        val glued = literals().filter { (_, s) -> dirs.any { s.startsWith(it) || s.startsWith("/$it") } || leaves.any { s.startsWith(it) } }
            .map { (at, s) -> "$at: \"$s\"" }
        assertTrue("relative /napi tails:\n" + glued.joinToString("\n"), glued.isEmpty())
    }

    /** The parsers read and write keys through Napi's names; a key spelled by hand is unchecked. */
    @Test
    fun theClientNeverSpellsAKeyItself() {
        val keys = HOMES.flatMap { it.sends + it.body + it.query.keys }.toSet()
        val access = Regex("""\.(opt\w*|put|has|get(String|Int|Long|Double|Boolean|JSONObject|JSONArray))\(\s*"""")
        val wrong = PARSERS.map(::file).flatMap { f ->
            f.readLines().flatMapIndexed { i, line ->
                val c = code(line)
                val at = "${RepoFiles.relative(f)}:${i + 1}"
                val byAccess = if (access.containsMatchIn(c)) listOf("$at: ${c.trim()}") else emptyList()
                val byName = literal.findAll(c).map { it.groupValues[1] }.filter { it in keys }
                    .map { "$at: \"$it\"" }.toList()
                byAccess + byName
            }
        }.distinct()
        assertTrue("keys spelled by hand:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    /** No query string is written by hand either: it comes from the endpoint's parameters. */
    @Test
    fun noQueryIsWrittenByHand() {
        val wrong = literals().filter { (_, s) -> Regex("""[?&][a-z_]+=""").containsMatchIn(s) }
            .map { (at, s) -> "$at: \"$s\"" }
        assertTrue("query strings by hand:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    /**
     * Every page the app opens is one Homes serves. A page path may stand only
     * in HomesPages and HomesConfig, and each one there must be Homes'.
     */
    @Test
    fun thePagesTheAppOpensAreHomes() {
        val page = Regex("""^/[a-z][a-z0-9_-]*(/[a-z0-9_-]+)*$""")
        val homes = setOf(
            "app/src/main/java/cloud/dopp/homes/widget/HomesPages.kt",
            "app/src/main/java/cloud/dopp/homes/HomesConfig.kt",
        )
        val pages = literals().filter { (_, s) -> page.matches(s) && !s.startsWith("/napi") }
        assertTrue("no page found at all", pages.isNotEmpty())
        val misplaced = pages.filter { (at, _) -> homes.none { at.startsWith("$it:") } }
        assertTrue("pages outside HomesPages/HomesConfig: $misplaced", misplaced.isEmpty())
        val foreign = pages.filter { (_, s) -> s !in PAGES }
        assertTrue("pages Homes does not serve: $foreign", foreign.isEmpty())
        assertTrue("the pairing page is not opened", pages.any { it.second == "/pair-device" })
    }

    @Test
    fun theTableItselfReadsAsHomesWritesIt() {
        HOMES.forEach { r ->
            assertTrue(r.toString(), r.method in setOf("GET", "POST", "HEAD", "PATCH", "PUT", "DELETE"))
            assertTrue(r.toString(), r.path.startsWith("/napi/"))
            assertTrue(r.toString(), r.at.startsWith("internal/napi/"))
        }
        assertEquals(HOMES.size, HOMES.map { "${it.method} ${it.path}" }.toSet().size)
        assertTrue(same("/napi/portal/camera/{x}/snapshot", "/napi/portal/camera/{entity_id}/snapshot"))
        assertTrue(!same("/napi/portal/camera/snapshot", "/napi/portal/camera/{entity_id}/snapshot"))
        assertTrue(same("/napi/portal/{tool}", "/napi/portal/tasks"))
        assertTrue(!same("/napi/portal/{tool}", "/napi/portal/start/addable"))
    }
}
