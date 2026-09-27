package xyz.photocleaner.net

import okhttp3.Interceptor
import okhttp3.Response
import xyz.photocleaner.session.GPhotosSession

/**
 * Attaches the Google session cookie to requests for Google's media hosts, and only
 * those. Install it with `addNetworkInterceptor`, never `addInterceptor`.
 *
 * An application interceptor runs once per call, and OkHttp keeps a hand-set Cookie
 * header when it follows a redirect to another host (it strips only Authorization).
 * A network interceptor runs for every hop, so each one is judged by its own host,
 * and a redirect off Google arrives with no cookie at all.
 *
 * [cookieFor] reads the WebView jar at request time; nothing is stored here.
 */
class GoogleSessionInterceptor(private val cookieFor: (url: String) -> String?) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()
            .header("User-Agent", GPhotosSession.USER_AGENT)
            .removeHeader("Cookie")
            .removeHeader("Referer")

        if (request.url.isHttps && isGoogleMediaHost(request.url.host)) {
            val cookie = cookieFor(request.url.toString())
            if (!cookie.isNullOrEmpty()) builder.header("Cookie", cookie)
            builder.header("Referer", "${GPhotosSession.ORIGIN}/")
        }
        return chain.proceed(builder.build())
    }

    companion object {
        fun isGoogleMediaHost(host: String): Boolean =
            host.endsWith(".googleusercontent.com") ||
                host.endsWith(".ggpht.com") ||
                host.endsWith(".google.com")
    }
}
