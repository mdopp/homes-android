package cloud.dopp.homes.data

import android.content.Context
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * Read-only: the assistant's name (`/napi/assistent`) and whether all is well (`/napi/box/home`'s `gut`).
 *
 * Blocking, off the main thread; null on any failure (no server, no token,
 * 401, network). Nothing here changes anything.
 */
class BoxApiClient(private val ctx: Context) {

    /** One token-authed GET of [endpoint], as JSON; never throws. */
    private fun get(endpoint: Napi.Endpoint): JSONObject? =
        bytes(endpoint)?.let { runCatching { JSONObject(it.toString(Charsets.UTF_8)) }.getOrNull() }

    /** The bytes [endpoint] answers, at most [cap] of them, or null; never throws. */
    private fun bytes(endpoint: Napi.Endpoint, cap: Long = MAX_REPLY_BYTES): ByteArray? {
        if (!ScreenGate.open(ctx)) return null
        val base = ServerStore.baseUrl(ctx)?.trimEnd('/') ?: return null
        val token = TokenStore.get(ctx) ?: return null
        return try {
            http.newCall(endpoint.request(base, token)).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val src = resp.body?.source() ?: return null
                // more than the cap → refuse it whole rather than cut it
                if (src.request(cap + 1)) return null
                src.readByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** `GET /napi/assistent` → the assistant's name, or null. */
    fun getAssistent(): String? = get(Napi.ASSISTENT)?.let { Assistent.parse(it) }
    /** `GET /napi/box/home` says all is well: true only for its `gut: true`; false, absent (an older Homes) or failed: false. */
    fun gut(): Boolean = get(Napi.BOX_HOME)?.optBoolean(Napi.BoxHomeJson.GUT, false) == true

    private companion object {
        /** No reply here is larger; a bigger one is refused, never half read. */
        const val MAX_REPLY_BYTES = 1L shl 20

        val http: OkHttpClient by lazy {
            HomesHttp.client(connect = 10, read = 15)
        }
    }
}
