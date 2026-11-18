package cloud.dopp.homes.data

import android.os.Build
import android.os.LocaleList
import okhttp3.Interceptor
import okhttp3.Response
import java.util.Locale

/**
 * The phone's languages on every call to Homes, as `Accept-Language` (the
 * owner's call, 2026-11-18: the language follows the client, no picker):
 * Homes answers in the first of de, en, es and fr these weights name, and
 * in German otherwise. [HomesHttp] puts it before every call.
 */
object Sprache : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val wert = acceptLanguage(systemSprachen())
        val req = chain.request()
        return chain.proceed(if (wert.isEmpty()) req else req.newBuilder().header("Accept-Language", wert).build())
    }

    /** The system's languages, the preferred first. */
    private fun systemSprachen(): List<Locale> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val liste = LocaleList.getDefault()
            List(liste.size()) { liste[it] }
        } else {
            listOf(Locale.getDefault())
        }

    /** `de-DE,en-US;q=0.9,fr;q=0.8`: each language once, in order, its weight falling by 0.1; at most ten. */
    fun acceptLanguage(sprachen: List<Locale>): String =
        sprachen.map { it.toLanguageTag() }.filter { it != "und" }.distinct().take(MAX)
            .mapIndexed { i, tag -> if (i == 0) tag else "$tag;q=0.${MAX - i}" }
            .joinToString(",")

    private const val MAX = 10
}
