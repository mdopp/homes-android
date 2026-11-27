package cloud.dopp.homes.data

import cloud.dopp.homes.data.Napi.CellSchemaJson
import cloud.dopp.homes.data.Napi.ToolDefJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * One entry of Homes' **tool catalog** (`GET /napi/defs/tool`, #70/#72; Homes'
 * `internal/napi/tools.go`). A tool describes itself declaratively — where its
 * items live, which fields fill which cell role — so a tool Homes adds becomes a
 * home-screen widget with **no native code and no app rebuild**.
 *
 * [apiPath] is the `/napi/…` path Homes declares — see [ToolDefs.napiPath].
 */
data class ToolDef(
    /** `tool-id` — the stable key a widget instance is bound to (e.g. `task`). */
    val id: String,
    /** `tool-label` — the German display name (e.g. "Aufgabe"). */
    val label: String,
    /** Item-list path under `/napi/`, or null when the tool has none (or names another). */
    val apiPath: String?,
    /** `tool-actions` — the declared action ids (plain strings, ADR 0011). */
    val actions: List<String>,
    /**
     * `tool-actions-titled` (#169) — `id → title`, where the title may be null
     * ("no title"). Empty when the def sends the key not at all.
     *
     * The titles travel in their **own** key rather than inside [actions] for a
     * reason worth keeping: [stringList] takes only `String` entries out of a
     * JSONArray, so a list of objects there would parse to an empty list, then
     * `schema.actions.filter { it in actions }` would empty too — and **every**
     * button of **every** tool would vanish in already-installed apps. Silently.
     */
    val actionTitles: Map<String, String?> = emptyMap(),
    /** `tool-cell-schema` — the role→field mapping the row renderer reads. */
    val schema: ToolCellSchema,
    /**
     * `tool-action-params` (#90) — per action id, the callback's
     * `param name → source`. A source with a leading `$` names a **field of the
     * rendered row**, anything else is a literal; that marker is the only thing
     * telling `{"status": "done"}` (literal) from `{"entity_id": "$id"}` (field).
     * This is what lets a generic renderer build a `/napi/action-callback` body
     * without knowing the tool — see `ToolCells.resolveParams`.
     */
    val actionParams: Map<String, Map<String, String>> = emptyMap(),
) {
    /**
     * Can this tool be offered as a **list widget** (archetype A)?
     *
     * Two hard requirements, both seen in the shipped defs: it must have items to
     * fetch (`note` declares no `tool-api-path`) and its schema must name a
     * `title` field (`energy`/`home` ship an empty `{}` schema — they are bespoke
     * browser cards, not list rows). Offering either would pin a permanently
     * blank widget, so they are excluded from the picker instead.
     */
    val isListable: Boolean
        get() = apiPath != null && schema.title != null

    /**
     * Is this tool offered as a widget? A list it can fill, and not one the
     * owner took off the offer ([ToolDefs.NOT_OFFERED]). A widget placed before
     * keeps working.
     */
    val isOffered: Boolean
        get() = isListable && id !in ToolDefs.NOT_OFFERED && id !in ToolDefs.AUSGEBAUT
}

/**
 * The **closed** cell-role vocabulary of `tool-cell-schema` (ADR 0011). A value is
 * always a plain field name (or, for [actions], a declared action id) — never
 * markup. Roles outside this vocabulary are browser-only and are skipped, not
 * guessed, so an unknown key can never crash the widget.
 */
data class ToolCellSchema(
    /** Primary line. */
    val title: String? = null,
    /** Secondary line. */
    val subtitle: String? = null,
    /** Muted detail fields, joined into one line. */
    val meta: List<String> = emptyList(),
    /** Small status chip — the `badge` role, or its `state` alias. */
    val badge: String? = null,
    /** Leading glyph field. */
    val icon: String? = null,
    /** Tap targets: ids that must exist in the def's `tool-actions`. */
    val actions: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = title == null && subtitle == null && meta.isEmpty() &&
            badge == null && icon == null && actions.isEmpty()

    companion object {
        val EMPTY = ToolCellSchema()
    }
}

/**
 * Parsing for the `.tool` catalog and for a tool's item payload. Pure — only
 * `org.json`, no Android types — so the whole discovery path (catalog shape,
 * `/napi/` paths, schema vocabulary, item extraction) is JVM-unit-tested
 * without an emulator.
 */
object ToolDefs {

    /**
     * Tools Homes lists that the app does not offer as a widget: contacts
     * (owner, 2026-11-02) and reminders (2026-11-03, they go into the phone's
     * calendar). A placed one keeps working.
     */
    val NOT_OFFERED = setOf(Napi.TOOL_CONTACTS, Napi.TOOL_TASKS)

    /**
     * Tools whose widget the app took out (owner 2026-11-27: „Widget für Notizen brauche ich nicht mehr“,
     * Dokumente „kann ganz weg“): never offered, and a placed one fetches nothing and says to remove it.
     */
    val AUSGEBAUT = setOf(Napi.TOOL_DOC, Napi.TOOL_NOTE)

    /**
     * A def's declared data path, as the app may call it: Homes names its rows
     * under `/napi/portal/…` (its `tool-api-path`), and only such a path is
     * ever sent the device token. A path elsewhere on the server, a relative
     * one or an absolute URL is refused (null) — the def gets no rows rather
     * than a guessed address.
     */
    fun napiPath(declared: String?): String? {
        val raw = declared?.trim().orEmpty()
        if (raw.isEmpty() || "://" in raw || ".." in raw) return null
        if (!raw.startsWith(NAPI_PREFIX) || raw.length == NAPI_PREFIX.length) return null
        return raw
    }

    /**
     * Parse `GET /napi/defs/tool` → `{ok, kind:"tool", defs:[…]}` into [ToolDef]s,
     * keeping Homes' order. A bare array is accepted too.
     * Malformed input → empty list; a single unparseable def is skipped, never
     * fatal (one bad plugin must not take the whole picker down).
     */
    fun parseCatalog(body: String?): List<ToolDef> {
        if (body.isNullOrBlank()) return emptyList()
        return try {
            val t = body.trim()
            val root =
                if (t.startsWith("[")) JSONObject().put(ToolDefJson.DEFS, JSONArray(t)) else JSONObject(t)
            val arr = root.optJSONArray(ToolDefJson.DEFS) ?: return emptyList()
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseDef(it) } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** One catalog row → [ToolDef], or null when it carries no usable `tool-id`. */
    fun parseDef(o: JSONObject): ToolDef? {
        val id = o.optString(ToolDefJson.TOOL_ID).trim()
        if (id.isEmpty()) return null
        val label = o.optString(ToolDefJson.TOOL_LABEL).ifBlank { id }
        val actions = stringList(o.opt(ToolDefJson.TOOL_ACTIONS))
        val schema = parseSchema(o.optJSONObject(ToolDefJson.TOOL_CELL_SCHEMA))
        return ToolDef(
            id = id,
            label = label,
            apiPath = napiPath(o.optString(ToolDefJson.TOOL_API_PATH)),
            actions = actions,
            // The schema may only reference ids the def actually declares — an
            // action id we can't dispatch is dropped rather than shown dead.
            schema = schema.copy(actions = schema.actions.filter { it in actions }),
            actionParams = parseActionParams(o.optJSONObject(ToolDefJson.TOOL_ACTION_PARAMS)),
            actionTitles = parseActionTitles(o.optJSONArray(ToolDefJson.TOOL_ACTIONS_TITLED)),
        )
    }

    /**
     * Read `tool-action-params` into `action id → (param → source)`. Both levels
     * must be plain strings — a nested object or a number is not a source we can
     * resolve — and a bare `$` names no field (the server lints it out too), so
     * such an entry is dropped. An action left without a usable mapping simply
     * gets no button; the row still renders.
     */
    fun parseActionParams(o: JSONObject?): Map<String, Map<String, String>> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, Map<String, String>>()
        val ids = o.keys()
        while (ids.hasNext()) {
            val id = ids.next()
            val mapping = o.optJSONObject(id) ?: continue
            val params = LinkedHashMap<String, String>()
            var usable = true
            val keys = mapping.keys()
            while (keys.hasNext()) {
                val param = keys.next()
                val source = (mapping.opt(param) as? String)?.trim().orEmpty()
                if (param.isBlank() || source.isEmpty() || source == FIELD_PREFIX) {
                    usable = false
                    break
                }
                params[param] = source
            }
            if (usable && params.isNotEmpty()) out[id] = params
        }
        return out
    }

    /**
     * Read `tool-actions-titled` into `id → title` (#169). Always a list of
     * objects by contract — never "sometimes a string" — so there is one shape to
     * parse. A blank or absent title is stored as null, which the sheet reads as
     * "use the fixed label"; an entry without a usable id is skipped rather than
     * guessed at.
     */
    private fun parseActionTitles(arr: org.json.JSONArray?): Map<String, String?> {
        if (arr == null) return emptyMap()
        val out = LinkedHashMap<String, String?>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString(ToolDefJson.ID).trim()
            if (id.isEmpty()) continue
            out[id] = o.optString(ToolDefJson.TITLE).trim().ifBlank { null }
        }
        return out
    }

    /** The marker that a `tool-action-params` source is a row field, not a literal. */
    const val FIELD_PREFIX = "$"

    /**
     * Read the closed role vocabulary out of a `tool-cell-schema` object. Unknown
     * roles are ignored; a role whose value has the wrong shape (an object where a
     * field name belongs, a number, …) degrades to "absent". An empty/absent
     * schema yields [ToolCellSchema.EMPTY] — the marker that this tool is a
     * bespoke browser card, not a list.
     */
    fun parseSchema(o: JSONObject?): ToolCellSchema {
        if (o == null) return ToolCellSchema.EMPTY
        return ToolCellSchema(
            title = single(o, CellSchemaJson.TITLE),
            subtitle = single(o, CellSchemaJson.SUBTITLE),
            meta = stringList(o.opt(CellSchemaJson.META)),
            // `badge` and `state` are the two spellings of the same chip role.
            badge = single(o, CellSchemaJson.BADGE) ?: single(o, CellSchemaJson.STATE),
            icon = single(o, CellSchemaJson.ICON),
            actions = stringList(o.opt(CellSchemaJson.ACTIONS)),
        )
    }

    /**
     * Pull the item rows out of a tool's rows reply. Homes wraps the list under
     * the tool's own key (`tasks`, `contacts`, `documents`, `notes`) next to
     * `ok`, so the reply's one array is the list — a new tool with a new key
     * renders too. A bare array works as well. Bounded by [cap]; malformed → empty.
     */
    fun rows(body: String?, cap: Int = MAX_ROWS): List<JSONObject> {
        if (body.isNullOrBlank()) return emptyList()
        return try {
            val t = body.trim()
            val arr = if (t.startsWith("[")) JSONArray(t) else firstArray(JSONObject(t))
            if (arr == null) return emptyList()
            val out = ArrayList<JSONObject>(minOf(arr.length(), cap))
            for (i in 0 until arr.length()) {
                if (out.size >= cap) break
                arr.optJSONObject(i)?.let { out.add(it) }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Body for `POST /napi/action-callback` ([Napi.ACTION_CALLBACK]):
     * `{"action_id": …, "params": {…}}`, plus `confirmed` on the retry after a
     * 403 confirm gate. Pure so the wire shape is unit-testable.
     *
     * The **params** are action-specific (`task.set_status` wants
     * `entity_id`+`status`), and the catalog declares where each one comes
     * from — see [ToolDef.actionParams] — so a generic renderer resolves them
     * off the row it drew instead of hardcoding the tool.
     */
    fun actionBody(actionId: String, params: JSONObject?, confirmed: Boolean = false): JSONObject =
        Napi.ACTION_CALLBACK.json(actionId, params ?: JSONObject(), if (confirmed) true else null)

    /** Where Homes serves everything a device token may read. */
    private val NAPI_PREFIX = Napi.ROOT

    /** Hard cap on rendered rows — a RemoteViews collection stays bounded. */
    const val MAX_ROWS = 40

    /** The reply's first array: the rows, whatever the tool calls them. */
    private fun firstArray(root: JSONObject): JSONArray? {
        val keys = root.keys()
        while (keys.hasNext()) {
            (root.opt(keys.next()) as? JSONArray)?.let { return it }
        }
        return null
    }

    /** A single-field role: a plain string, or the first entry of a list. */
    private fun single(o: JSONObject, key: String): String? = when (val v = o.opt(key)) {
        is String -> v.trim().ifBlank { null }
        is JSONArray -> stringList(v).firstOrNull()
        else -> null
    }

    /** A list-valued role: an array of strings, or one bare string. Blanks dropped. */
    private fun stringList(v: Any?): List<String> = when (v) {
        is String -> listOfNotNull(v.trim().ifBlank { null })
        is JSONArray -> (0 until v.length())
            .mapNotNull { (v.opt(it) as? String)?.trim()?.ifBlank { null } }
        else -> emptyList()
    }
}
