package xyz.photocleaner

import android.app.Application
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import okhttp3.OkHttpClient
import xyz.photocleaner.api.PhotosApi
import xyz.photocleaner.data.AppDatabase
import xyz.photocleaner.data.CleanupRepository
import xyz.photocleaner.data.Settings
import xyz.photocleaner.session.GPhotosSession

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Never persist WebView debug/remote-inspection in a shipping build.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        Graph.init(this)
    }

    /**
     * Image loading for Google's photo hosts.
     *
     * Thumbnails on `lh3.googleusercontent.com` are private to the account: a browser
     * fetches them successfully only because it attaches the Google session cookie
     * automatically. A plain HTTP client gets nothing back, which renders as a blank
     * card. So requests to Google hosts — and only those — carry the cookie from the
     * WebView's jar, plus the matching User-Agent and Referer.
     *
     * The cookie is read at request time and handed straight to Google. It is never
     * copied into app storage, logged, or sent anywhere else.
     */
    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val builder = request.newBuilder()
                    .header("User-Agent", GPhotosSession.USER_AGENT)

                if (isGooglePhotoHost(request.url.host)) {
                    val cookie = runCatching {
                        CookieManager.getInstance().getCookie(request.url.toString())
                    }.getOrNull()
                    if (!cookie.isNullOrEmpty()) builder.header("Cookie", cookie)
                    builder.header("Referer", "${GPhotosSession.ORIGIN}/")
                }
                chain.proceed(builder.build())
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .crossfade(true)
            // Thumbnails are cached on disk so re-reviewing a month is not a re-download.
            // This lives in the app's private storage and is cleared on sign-out.
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .build()
    }

    private fun isGooglePhotoHost(host: String): Boolean =
        host.endsWith(".googleusercontent.com") ||
            host.endsWith(".ggpht.com") ||
            host.endsWith(".google.com")
}

/**
 * Minimal manual dependency graph.
 *
 * A DI framework would earn its keep on a larger app; here it would only add a
 * dependency to audit. Everything is constructed once and shared.
 */
object Graph {
    private lateinit var appContext: Context

    val session: GPhotosSession by lazy { GPhotosSession(appContext) }
    val api: PhotosApi by lazy { PhotosApi(session) }
    val settings: Settings by lazy { Settings(appContext) }
    private val db: AppDatabase by lazy { AppDatabase.get(appContext) }
    val repository: CleanupRepository by lazy {
        CleanupRepository(api, db.decisionDao(), db.libraryIndexDao(), settings)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Full local wipe: Google session, encrypted database, settings and image cache. */
    suspend fun wipeEverything() {
        session.signOut()
        settings.clear()
        AppDatabase.wipe(appContext)
        runCatching { appContext.cacheDir.resolve("image_cache").deleteRecursively() }
    }
}
