package xyz.photocleaner

import android.app.Application
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebView
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import xyz.photocleaner.api.PhotosApi
import xyz.photocleaner.data.AppDatabase
import xyz.photocleaner.data.CleanupRepository
import xyz.photocleaner.data.Settings
import xyz.photocleaner.net.GoogleSessionInterceptor
import xyz.photocleaner.session.GPhotosSession
import java.io.File

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Must run before anything creates a WebView, which would lock these files.
        Graph.finishPendingWipe(this)
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
        return ImageLoader.Builder(this)
            .okHttpClient(Graph.httpClient)
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

}

/**
 * Minimal manual dependency graph.
 *
 * A DI framework would earn its keep on a larger app; here it would only add a
 * dependency to audit. Everything is constructed once and shared.
 */
object Graph {
    private lateinit var appContext: Context

    /**
     * Shared HTTP client for everything that fetches media from Google: images,
     * video playback and video downloads for sharing.
     *
     * Thumbnails and video streams on Google's media hosts are private to the
     * account: a browser loads them only because it attaches the session cookie
     * automatically. Requests to Google hosts — and only those — carry the cookie
     * from the WebView jar, read at request time and never stored by this app.
     * See [GoogleSessionInterceptor] for why it must be a network interceptor.
     */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addNetworkInterceptor(
                GoogleSessionInterceptor { url ->
                    runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                },
            )
            .build()
    }

    /**
     * For work that must outlive the screen that started it. A confirmed delete runs
     * as a series of batches; cancelling it halfway because the user navigated away
     * would leave Google having trashed items this device never recorded.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    /** Marks that the WebView's own storage still has to be deleted, on next start. */
    private const val WIPE_MARKER = "pending_webview_wipe"

    /**
     * Full local wipe — Google session, encrypted database, settings and caches —
     * then a restart into a fresh process.
     *
     * The restart is what makes this safe. Screens, viewmodels and the repository all
     * hold the database that was just deleted; carrying on in this process would
     * write new verdicts through those stale handles into a file whose key is gone.
     */
    suspend fun wipeEverything() {
        // Written first, so the WebView storage is removed even if a step below fails.
        runCatching { File(appContext.filesDir, WIPE_MARKER).createNewFile() }
        session.signOut()
        settings.clear()
        AppDatabase.wipe(appContext)
        runCatching { appContext.cacheDir.resolve("image_cache").deleteRecursively() }
        xyz.photocleaner.ui.ShareActions.clearCache(appContext)
        restart()
    }

    /**
     * Deletes the WebView's data directory if a wipe asked for it.
     *
     * Clearing cookies and web storage through the WebView APIs is asynchronous, and
     * the restart could cut it short. Deleting the directory before any WebView
     * exists is complete and cannot race.
     */
    fun finishPendingWipe(context: Context) {
        val marker = File(context.filesDir, WIPE_MARKER)
        if (!marker.exists()) return
        runCatching { File(context.dataDir, "app_webview").deleteRecursively() }
        runCatching { context.cacheDir.resolve("WebView").deleteRecursively() }
        marker.delete()
    }

    private fun restart() {
        val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) appContext.startActivity(launch)
        Runtime.getRuntime().exit(0)
    }
}
