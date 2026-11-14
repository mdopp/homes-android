package cloud.dopp.homes.data

import android.content.Context
import cloud.dopp.homes.data.Napi.ActionJson
import cloud.dopp.homes.data.Napi.AddableJson
import cloud.dopp.homes.data.Napi.CardJson
import cloud.dopp.homes.data.Napi.EnergyJson
import cloud.dopp.homes.data.Napi.FavoritesJson
import cloud.dopp.homes.data.Napi.MerkenJson
import cloud.dopp.homes.data.Napi.StateJson
import cloud.dopp.homes.data.Napi.WhoamiJson
import cloud.dopp.homes.verbindung.Verbindung
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The widgets' client for Homes' `/napi`, with the device token (`homes_device_…`)
 * from [TokenStore]. Every call is an endpoint of [Napi]; the request comes from
 * [request], the keys of the reply from Napi's `…Json` objects. All calls are
 * blocking and must run off the main thread (a widget provider's goAsync thread).
 */
class ApiClient(private val ctx: Context) {

    /** Each answer, and each request that got none, tells [Verbindung] about the line. */
    private val http = Verbindung.gemeldet(ctx, HTTP)

    /** No server configured yet — the app must be connected first. */
    class NotConfiguredException : Exception("no server configured")

    /** No device token stored yet — the app must be paired first. */
    class NotPairedException : Exception("no device token")

    /** Homes refused a sensitive action (garage/door open) without confirm. */
    class SensitiveException : Exception("sensitive action needs confirmation")

    /**
     * How `POST /napi/action-callback` answered a tool-row action (#90). The three
     * refusals Homes distinguishes need three different reactions, so they
     * are separate values rather than one boolean:
     *
     * - [CONFIRM_REQUIRED] — destructive action, unconfirmed (403): ask, then
     *   re-send with `confirmed=true`.
     * - [FORBIDDEN] — admin-only action (403). On `/napi/` this is **always** the
     *   answer, because `Remote-Groups` is not trustworthy there — so there is
     *   nothing to retry and no dialog to show.
     * - [UNKNOWN_ACTION] — the id isn't registered (404): leave the row alone.
     */
    enum class ActionOutcome { OK, CONFIRM_REQUIRED, FORBIDDEN, UNKNOWN_ACTION, FAILED }

    /**
     * What Share → Homes came to (homes#120): [Kept] — Homes has the note, [said]
     * is its line, [note] its page on Homes or null, [already] when the link
     * was kept before; [Refused] — Homes' own sentence why not; [NotPaired];
     * [Unreachable] — no answer in time.
     */
    sealed class Merken {
        data class Kept(val said: String, val note: String?, val already: Boolean) : Merken()
        data class Refused(val sentence: String) : Merken()
        object NotPaired : Merken()
        object Unreachable : Merken()
    }

    /** What became of one photo shared to Homes (`POST /napi/upload`). */
    sealed class Upload {
        /** Homes placed it under [id] in the folder Paperless consumes. */
        data class Stored(val id: String) : Upload()
        data class Refused(val sentence: String) : Upload()
        object NotPaired : Upload()
        object Unreachable : Upload()
    }

    private fun base(): String =
        ServerStore.baseUrl(ctx)?.trimEnd('/') ?: throw NotConfiguredException()

    private fun token(): String = TokenStore.get(ctx) ?: throw NotPairedException()

    /** [endpoint]'s request to the paired Homes with the device token; none while the screen is off ([ScreenGate]). */
    private fun authed(endpoint: Napi.Endpoint, target: String = endpoint.path, body: okhttp3.RequestBody? = null): Request {
        ScreenGate.check(ctx)
        return endpoint.request(base(), token(), target, body)
    }

    /** One entity's live card via `/napi/portal/state`; null on any refusal. */
    fun getCard(entityId: String): Card? {
        http.newCall(authed(Napi.STATE, Napi.STATE.target(entityId))).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val card = JSONObject(body).optJSONObject(StateJson.CARD) ?: return null
            return parseCardJson(card)
        }
    }

    /**
     * What a device widget can show, for the config picker and the app-icon menu
     * via `/napi/portal/start/addable`: only what Homes draws as a card — Homes
     * offers cameras there too, but a device widget could never render one.
     */
    fun listAddable(): List<Device> {
        http.newCall(authed(Napi.ADDABLE)).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            return DeviceSearch.pickable(parseAddable(JSONObject(body)))
        }
    }

    /**
     * Run a scoped HA service on one entity. `service` is dotted
     * (`light.toggle`, `cover.open_cover`). Throws [SensitiveException] on
     * Homes' 403 confirm gate; returns true on success.
     */
    fun call(
        entityId: String,
        service: String,
        confirmed: Boolean = false,
        data: JSONObject? = null,
    ): Boolean {
        val body = Napi.HA_CALL.json(entityId, service, data, if (confirmed) true else null).asBody()
        http.newCall(authed(Napi.HA_CALL, body = body)).execute().use { resp ->
            if (resp.code == 403) throw SensitiveException()
            return resp.isSuccessful
        }
    }

    /**
     * Register this device's watch set via `POST /napi/portal/watch` ([watchBody]):
     * Homes then streams `card_state` for exactly these entities over
     * `/napi/portal/events`.
     *
     * Returns Homes' status (503 while Home Assistant is down, which
     * [cloud.dopp.homes.realtime.RealtimeProtocol.watchRetryMs] retries), or 0
     * when no request went out (no server, no token, no network). Never throws.
     */
    fun postWatch(body: JSONObject): Int {
        return try {
            http.newCall(authed(Napi.WATCH, body = body.asBody())).execute().use { it.code }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Share → Homes (homes#120): [text] as the phone shared it, to `POST /napi/merken`;
     * Homes finds the link in it and keeps the page as a note. Blocking, up to
     * 90 s (Homes reads the page, then its model sums it up): call off the main
     * thread. Never throws.
     */
    fun merken(text: String): Merken = try {
        val body = Napi.MERKEN.json(text).asBody()
        slow.newCall(authed(Napi.MERKEN, body = body)).execute().use { resp -> merkenOutcome(resp.code, resp.body?.string()) }
    } catch (e: NotPairedException) {
        Merken.NotPaired
    } catch (e: NotConfiguredException) {
        Merken.NotPaired
    } catch (e: Exception) {
        Merken.Unreachable
    }

    /**
     * A photo shared to Homes: [bytes] as [filename] of type [mime], one
     * `POST /napi/upload`. Blocking, up to 90 s for the answer; call off the
     * main thread. Never throws.
     */
    fun upload(bytes: ByteArray, filename: String, mime: String): Upload = try {
        slow.newCall(authed(Napi.UPLOAD, body = uploadBody(bytes, filename, mime))).execute()
            .use { resp -> uploadOutcome(resp.code, resp.body?.string()) }
    } catch (e: NotPairedException) {
        Upload.NotPaired
    } catch (e: NotConfiguredException) {
        Upload.NotPaired
    } catch (e: Exception) {
        Upload.Unreachable
    }

    /** The house energy picture via `/napi/portal/energy`, raw for the widget to keep ([parseEnergy]); null on any failure. */
    fun energyBody(): String? = getBody(Napi.ENERGY, Napi.ENERGY.path)

    /**
     * The person's favorite devices via `GET /napi/portal/favorites`: the
     * things they pinned with ☆ on Homes, as cards in their order — the start
     * screen's first rows. The raw body, so the screen can keep it for its next
     * start; parse it with [parseFavorites]. Null on any failure — never throws.
     */
    fun favoritesBody(): String? = getBody(Napi.FAVORITES, Napi.FAVORITES.path)

    /**
     * The tool catalog via `GET /napi/defs/tool` (#72): every tool Homes offers,
     * with its label, rows path, actions and cell schema. This is what lets a
     * new tool appear as an addable widget without an app rebuild.
     *
     * Returns the raw body so the caller can also persist it as the offline
     * fallback for the picker; parse it with [ToolDefs.parseCatalog]. Null on any
     * error (not paired, offline) — never throws.
     */
    fun toolCatalogBody(): String? = getBody(Napi.TOOL_DEFS, Napi.TOOL_DEFS.path)

    /**
     * The rows behind one tool, from the path its catalog entry names (a
     * [Napi.TOOL_ROWS] path). The generic widget renders these through the
     * tool's `tool-cell-schema` — no per-tool code here. A tool without such a
     * path yields nothing.
     */
    fun fetchToolRows(def: ToolDef): List<JSONObject> {
        val rows = def.apiPath?.let { Napi.TOOL_ROWS.at(it) } ?: return emptyList()
        return ToolDefs.rows(getBody(rows, rows.path))
    }

    /**
     * Run one declared tool action via `POST /napi/action-callback` (#90) with the
     * body [ToolDefs.actionBody] builds — the params already resolved from the
     * rendered row through the catalog's `tool-action-params`, so nothing here
     * knows what a task is.
     *
     * Never throws: a transport failure is [ActionOutcome.FAILED], because a
     * widget button that crashes the provider is worse than one that does nothing.
     */
    fun postActionCallback(
        actionId: String,
        params: JSONObject?,
        confirmed: Boolean = false,
    ): ActionOutcome {
        return try {
            val body = ToolDefs.actionBody(actionId, params, confirmed).asBody()
            http.newCall(authed(Napi.ACTION_CALLBACK, body = body)).execute().use { resp ->
                actionOutcome(resp.code, resp.body?.string())
            }
        } catch (e: Exception) {
            ActionOutcome.FAILED
        }
    }

    /** GET [endpoint] at [target], returning the body, or null on any failure. */
    private fun getBody(endpoint: Napi.Endpoint, target: String): String? {
        return try {
            http.newCall(authed(endpoint, target)).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** addable: `{ ok, rooms: [ {room, cards:[ {entity_id, name, domain, device_class} ]} ] }`. */
    private fun parseAddable(root: JSONObject): List<Device> {
        val out = mutableListOf<Device>()
        val rooms = root.optJSONArray(AddableJson.ROOMS) ?: return out
        for (i in 0 until rooms.length()) {
            val roomObj = rooms.optJSONObject(i) ?: continue
            val room = roomObj.optString(AddableJson.ROOM).ifBlank { null }
            val cards = roomObj.optJSONArray(AddableJson.CARDS) ?: continue
            for (j in 0 until cards.length()) {
                val c = cards.optJSONObject(j) ?: continue
                val eid = c.optString(AddableJson.ENTITY_ID)
                if (eid.isBlank()) continue
                out.add(
                    Device(
                        entityId = eid,
                        name = c.optString(AddableJson.NAME).ifBlank { eid },
                        domain = c.optString(AddableJson.DOMAIN).ifBlank { eid.substringBefore(".") },
                        deviceClass = c.optString(AddableJson.DEVICE_CLASS).ifBlank { null },
                        room = room,
                    ),
                )
            }
        }
        return out.sortedWith(compareBy({ it.room ?: "￿" }, { it.name }))
    }

    companion object {
        /** One client per process: its connection pool and threads are shared by every call. */
        private val HTTP: OkHttpClient by lazy {
            // Uploads (camera #36) can be a few MB; give the write side room.
            HomesHttp.client(connect = 10, read = 15, write = 30)
        }

        /** Share → Homes waits for a page and the house model: a longer read. */
        private val slow: OkHttpClient by lazy {
            HomesHttp.client(connect = 10, read = 90, write = 30)
        }

        /**
         * Ask the Homes at [server] whose [token] this is: `GET /napi/whoami`.
         * The pairing stores a token only when this answers 200 with a `uid`;
         * returns the status (200 only with a uid), or 0 when nothing came back.
         */
        fun whoami(server: String, token: String): Int {
            return try {
                HTTP.newCall(Napi.WHOAMI.request(server, token)).execute().use { resp ->
                    val uid = if (resp.isSuccessful) {
                        runCatching { JSONObject(resp.body?.string().orEmpty()).optString(WhoamiJson.UID) }.getOrNull()
                    } else {
                        null
                    }
                    if (resp.isSuccessful && uid.isNullOrBlank()) 0 else resp.code
                }
            } catch (e: Exception) {
                0
            }
        }

        /**
         * Build a [Card] from Homes' card JSON (the same shape on
         * `/napi/portal/state`, `/napi/portal/active` and the stream's
         * `card_state` frame, #48). Pure (only `org.json`), so the stream and JVM
         * tests can call it without an [ApiClient] instance or the network.
         */
        fun parseCardJson(o: JSONObject): Card = Card(
            entityId = o.optString(CardJson.ENTITY_ID),
            name = o.optString(CardJson.NAME).ifBlank { o.optString(CardJson.ENTITY_ID) },
            domain = o.optString(CardJson.DOMAIN).ifBlank { o.optString(CardJson.ENTITY_ID).substringBefore(".") },
            deviceClass = o.optString(CardJson.DEVICE_CLASS).ifBlank { null },
            state = o.optString(CardJson.STATE).ifBlank { null },
            unit = o.optString(CardJson.UNIT).ifBlank { null },
            brightness = o.optInt(CardJson.BRIGHTNESS, -1).takeIf { it >= 0 },
            position = o.optInt(CardJson.CURRENT_POSITION, -1).takeIf { it in 0..100 },
            temperature = o.optDouble(CardJson.CURRENT_TEMPERATURE, Double.NaN).takeIf { !it.isNaN() },
            // HA's capability bitmask (#92). Absent ⇒ null ⇒ "capability unproven",
            // which the lock chooser reads as "no latch".
            supportedFeatures = o.optInt(CardJson.SUPPORTED_FEATURES, -1).takeIf { it >= 0 },
            page = o.optString(CardJson.PAGE).ifBlank { null },
            setpoint = o.optDouble(CardJson.TEMPERATURE, Double.NaN).takeIf { !it.isNaN() },
            minTemp = o.optDouble(CardJson.MIN_TEMP, Double.NaN).takeIf { !it.isNaN() },
            maxTemp = o.optDouble(CardJson.MAX_TEMP, Double.NaN).takeIf { !it.isNaN() },
            tempStep = o.optDouble(CardJson.TARGET_TEMP_STEP, Double.NaN).takeIf { !it.isNaN() && it > 0 },
            colorModes = stringList(o.optJSONArray(CardJson.SUPPORTED_COLOR_MODES)),
            text = o.optString(CardJson.TEXT).trim().ifBlank { null },
        )

        /**
         * The `/napi/portal/favorites` reply, `{ok, favorites: [card]}`, as
         * [Card]s in the person's order; a row without an `entity_id` is
         * skipped. Anything else is not Homes' answer: null.
         */
        internal fun parseFavorites(body: String): List<Card>? {
            val arr = runCatching { JSONObject(body.trim()).optJSONArray(FavoritesJson.FAVORITES) }.getOrNull()
                ?: return null
            val out = ArrayList<Card>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString(CardJson.ENTITY_ID).isBlank()) continue
                out.add(parseCardJson(o))
            }
            return out
        }

        /** `{ok, energy: {flow, totals, charge}}` → [Energy], or null when Homes says it has none. */
        internal fun parseEnergy(root: JSONObject): Energy? {
            if (!root.optBoolean(EnergyJson.OK, false)) return null
            val e = root.optJSONObject(EnergyJson.ENERGY) ?: return null
            val legs = mutableListOf<EnergyFlow>()
            e.optJSONArray(EnergyJson.FLOW)?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    legs.add(
                        EnergyFlow(
                            label = o.optString(EnergyJson.LABEL),
                            watts = o.optString(EnergyJson.STATE).toDoubleOrNull(),
                            unit = o.optString(EnergyJson.UNIT).ifBlank { "W" },
                            sense = o.optString(EnergyJson.SENSE),
                            entityId = o.optString(EnergyJson.ENTITY_ID).ifBlank { null },
                        ),
                    )
                }
            }
            val totals = mutableListOf<EnergyTotal>()
            e.optJSONArray(EnergyJson.TOTALS)?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val entityId = o.optString(EnergyJson.ENTITY_ID)
                    totals.add(
                        EnergyTotal(
                            label = o.optString(EnergyJson.LABEL),
                            value = o.optString(EnergyJson.STATE).toDoubleOrNull(),
                            unit = o.optString(EnergyJson.UNIT).ifBlank { "kWh" },
                            // Homes' totals carry no key of their own: EnergyStyle
                            // classifies a slot from its entity id and label.
                            key = entityId,
                            entityId = entityId.ifBlank { null },
                            today = if (o.has(EnergyJson.TODAY)) o.optDouble(EnergyJson.TODAY).takeUnless { it.isNaN() } else null,
                        ),
                    )
                }
            }
            // how full the battery is, when Homes says it (a meter whose state is the percent)
            val charge = e.optJSONObject(EnergyJson.CHARGE)
            val pct = charge?.optString(EnergyJson.STATE)?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
            return Energy(legs, totals, pct, charge?.optString(EnergyJson.ENTITY_ID)?.ifBlank { null })
        }

        /** A JSON string array (e.g. `supported_color_modes`) as a lower-case list. */
        internal fun stringList(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            val out = ArrayList<String>(a.length())
            for (i in 0 until a.length()) {
                val s = a.optString(i).trim().lowercase()
                if (s.isNotEmpty()) out.add(s)
            }
            return out
        }

        /** The `POST /napi/portal/watch` body, `{"entity_ids":[…]}` (#48). */
        fun watchBody(entityIds: List<String>): JSONObject = Napi.WATCH.json(JSONArray(entityIds))

        /**
         * Classify an `/napi/action-callback` reply (#90). Both refusals Homes can
         * give are **403**, so the status alone is ambiguous — the `reason` in the
         * body is what separates "ask and retry" from "you may never do this". An
         * unreadable body degrades to [ActionOutcome.FAILED] rather than to a
         * confirm dialog for an action we can't name. Pure → JVM-testable.
         */
        fun merkenOutcome(code: Int, body: String?): Merken {
            val o = HomesHttp.json(body)
            if (code in 200..299 && o != null) {
                val note = o.optString(MerkenJson.NOTE).takeIf { it.startsWith("https://") }
                return Merken.Kept(o.optString(MerkenJson.SAID), note, o.optBoolean(MerkenJson.ALREADY))
            }
            val sentence = o?.optString(MerkenJson.ERROR).orEmpty()
            return when {
                code == 401 -> Merken.NotPaired // Homes' {"error":"unauthorized"} is no sentence for a person
                sentence.isNotBlank() -> Merken.Refused(sentence)
                code == 404 -> Merken.Refused("Diese Box merkt sich noch keine geteilten Links.")
                else -> Merken.Refused("Homes konnte das gerade nicht merken ($code). Später noch einmal teilen.")
            }
        }

        /** The multipart body of `POST /napi/upload`: the name, then the file (Homes' `upload.go`). */
        fun uploadBody(bytes: ByteArray, filename: String, mime: String): MultipartBody {
            val (nameField, fileField) = Napi.UPLOAD.body
            return MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(nameField, filename)
                .addFormDataPart(fileField, filename, bytes.toRequestBody(mime.toMediaType()))
                .build()
        }

        /**
         * Homes' answer to one upload, in words for the person. Homes says why
         * in a code (`busy`, `insufficient_storage` …), so the sentence comes
         * from the status.
         */
        fun uploadOutcome(code: Int, body: String?): Upload {
            val o = HomesHttp.json(body)
            if (code in 200..299 && o != null && o.optBoolean(Napi.PhotoJson.OK)) {
                return Upload.Stored(o.optString(Napi.PhotoJson.ID))
            }
            return when (code) {
                401 -> Upload.NotPaired
                403 -> Upload.Refused("Homes nimmt von diesem Telefon nichts an. Koppel es in der App neu.")
                404 -> Upload.Refused("Diese Box hat keinen Ablageordner für Fotos eingerichtet.")
                408 -> Upload.Refused("Das Hochladen hat zu lange gedauert. Teile das Foto im WLAN noch einmal.")
                413 -> Upload.Refused("Das Foto ist zu groß für Homes (höchstens 25 MB).")
                415 -> Upload.Refused("Dieses Bildformat nimmt Homes nicht an.")
                429 -> Upload.Refused("Homes legt gerade andere Dateien ab. Teile das Foto gleich noch einmal.")
                503 -> Upload.Refused("Der Ablageordner auf der Box ist gerade nicht erreichbar. Später noch einmal teilen.")
                507 -> Upload.Refused("Auf der Box ist kein Platz mehr für Fotos.")
                else -> Upload.Refused("Homes konnte das Foto nicht ablegen ($code). Später noch einmal teilen.")
            }
        }

        fun actionOutcome(code: Int, body: String?): ActionOutcome {
            if (code in 200..299) return ActionOutcome.OK
            val reason = try {
                if (body.isNullOrBlank()) "" else JSONObject(body).optString(ActionJson.REASON)
            } catch (e: Exception) {
                ""
            }
            return when {
                code == 403 && reason == CONFIRM_REQUIRED -> ActionOutcome.CONFIRM_REQUIRED
                code == 403 -> ActionOutcome.FORBIDDEN
                code == 404 || reason == UNKNOWN_ACTION -> ActionOutcome.UNKNOWN_ACTION
                else -> ActionOutcome.FAILED
            }
        }

        /** The refusal reasons of `/napi/action-callback` the app tells apart. */
        private const val CONFIRM_REQUIRED = "confirm_required"
        private const val UNKNOWN_ACTION = "unknown_action"
    }
}
