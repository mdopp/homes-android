package cloud.dopp.homes.data

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The one OkHttp the app has: every call shares its connection pool and
 * threads, and each caller names only its timeouts, in seconds. A client
 * from [client] is cheap; keep it in a `lazy` beside its calls. Each call
 * carries the phone's language ([Sprache]).
 */
object HomesHttp {
    private val shared: OkHttpClient by lazy { OkHttpClient.Builder().addInterceptor(Sprache).build() }

    /** OkHttp's own default for a timeout not named. */
    private const val STANDARD = 10L

    fun client(connect: Long = STANDARD, read: Long = STANDARD, write: Long = STANDARD): OkHttpClient =
        shared.newBuilder()
            .connectTimeout(connect, TimeUnit.SECONDS)
            .readTimeout(read, TimeUnit.SECONDS)
            .writeTimeout(write, TimeUnit.SECONDS)
            .build()

    /** [client] with [interceptor] before each of its calls: [cloud.dopp.homes.verbindung.Verbindung] hears how they end. */
    fun mit(client: OkHttpClient, interceptor: Interceptor): OkHttpClient = client.newBuilder().addInterceptor(interceptor).build()

    /** A reply body as a JSON object, or null when it is none. */
    fun json(body: String?): JSONObject? = runCatching { JSONObject(body.orEmpty()) }.getOrNull()

    /**
     * [key] of [o] as Homes writes a sentence or a word (`fehler`, `grund`):
     * trimmed; null when [o] is none, or the key is absent, JSON null or blank.
     */
    fun text(o: JSONObject?, key: String): String? =
        if (o == null || o.isNull(key)) null else o.optString(key).trim().ifBlank { null }

    /** Homes' sentence under [key] in a reply [body]: its `{"fehler": …}`. */
    fun satz(body: String?, key: String): String? = text(json(body), key)
}
