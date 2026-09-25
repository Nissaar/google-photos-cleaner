package xyz.photocleaner.session

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Owns the authenticated Google Photos web session.
 *
 * Design contract, and the reason the app is built this way:
 *   - Google session cookies live only in the WebView's own [CookieManager] jar.
 *   - This class never reads them, never copies them, and never writes them anywhere.
 *   - All Google traffic is issued by [bridge.js] from inside the page's origin.
 *   - Kotlin can only invoke named RPCs and receive already-parsed results.
 *
 * The practical consequence: a bug (or a future contributor) in the Kotlin layer
 * cannot leak a Google credential, because the credential is not reachable from here.
 */
class GPhotosSession(private val appContext: Context) {

    enum class State {
        UNKNOWN,
        SIGNED_OUT,
        READY,

        /**
         * Google could not be reached. Distinct from [SIGNED_OUT] on purpose: the
         * session is probably fine, and treating a dead network as a sign-out sent
         * people to the login page every time they opened the app offline.
         */
        OFFLINE,
    }

    private val _state = MutableStateFlow(State.UNKNOWN)
    val state: StateFlow<State> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Result<String>>>()
    private val idSeq = AtomicLong(0)

    /** Serialises RPCs: Google rate-limits aggressive parallelism, and order matters for paging. */
    private val rpcLock = Mutex()

    @Volatile private var worker: WebView? = null

    /** Whether the worker's *current* page has the bridge. Reset on every new page. */
    @Volatile private var bridgeInstalled = false

    // Where the worker's page load stands. Written on the main thread by its client.
    @Volatile private var workerLoading = false
    @Volatile private var workerLoadFailed = false
    @Volatile private var workerFinishedAt = 0L

    /**
     * WebViews whose current page failed to load. Their error page still carries the
     * Photos URL, and injecting the bridge into it reported "no session" — which is
     * how being offline used to sign people out. Main thread only.
     */
    private val failedLoads: MutableSet<WebView> = Collections.newSetFromMap(WeakHashMap())

    /**
     * The signed-in account index, taken from the Photos path prefix (`/u/1/` -> 1).
     * Image URLs must carry the matching `authuser`, or they resolve against the
     * wrong account and come back empty.
     */
    @Volatile var authUser: Int = 0
        private set

    private fun rememberAccountPath(path: String?) {
        val index = path?.let { Regex("""/u/(\d+)/""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        if (index != null) authUser = index
    }

    private var bridgeJs: String = ""

    companion object {
        const val ORIGIN = "https://photos.google.com"
        const val BRIDGE_NAME = "gpcBridge"

        /** How long a finished page gets for its bridge to install before it is judged. */
        private const val SETTLE_MS = 1_500L

        /**
         * Signed out, photos.google.com serves a marketing landing page whose only
         * sign-in affordance is buried in a hamburger menu. Go straight to Google's
         * sign-in form instead, and hand it back to Photos afterwards.
         *
         * `service=lh2` is Google Photos' service id. If a session already exists this
         * URL redirects immediately to the library, so it is safe as a general entry point.
         */
        const val LOGIN_URL =
            "https://accounts.google.com/ServiceLogin" +
                "?service=lh2&continue=https%3A%2F%2Fphotos.google.com%2F"

        /**
         * Google refuses account sign-in from anything it can identify as an embedded
         * WebView, keyed largely on the "; wv" token in the default User-Agent.
         * Overriding the UA removes that token entirely (per Android's UA-reduction
         * notes, a custom UA is used verbatim and the wv marker is not re-added).
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.6778.104 Mobile Safari/537.36"

        /**
         * Google-owned suffixes the WebView may navigate to. Everything else is refused.
         *
         * This has to cover the whole sign-in journey — which hops through country
         * domains, consent pages and CDNs. Too narrow an allowlist silently breaks
         * buttons like "Skip" on the profile-picture step, because the navigation is
         * blocked with no visible error.
         */
        private val ALLOWED_SUFFIXES = listOf(
            ".google.com",
            ".googleusercontent.com",
            ".gstatic.com",
            ".googleapis.com",
            ".ggpht.com",
            ".youtube.com",
            ".android.com",
        )

        private val GOOGLE_COUNTRY_DOMAIN = Regex("""^(www\.)?google(\.[a-z]{2,3}){1,2}$""")

        fun isAllowedHost(host: String?): Boolean {
            // Empty host means about:blank, data: or a JS-driven navigation — those
            // never leave the current origin, so let the WebView handle them.
            if (host.isNullOrEmpty()) return true
            val h = host.lowercase()
            if (h == "google.com" || h == "youtube.com") return true
            if (ALLOWED_SUFFIXES.any { h.endsWith(it) }) return true
            return GOOGLE_COUNTRY_DOMAIN.matches(h)
        }
    }

    private fun loadBridgeJs(): String {
        if (bridgeJs.isEmpty()) {
            bridgeJs = appContext.assets.open("bridge.js").bufferedReader().use { it.readText() }
        }
        return bridgeJs
    }

    /** Applies the hardened settings shared by the worker and the login WebView. */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(web: WebView, onPageFinished: (String?) -> Unit = {}) {
        web.settings.apply {
            javaScriptEnabled = true          // required: the whole API runs in-page
            domStorageEnabled = true          // Google Photos needs it to boot
            userAgentString = USER_AGENT
            // Harden everything we do not need.
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            // Google's account pages open some steps (including "Skip" on the profile
            // picture prompt) as a new window. With popups disabled those taps simply
            // did nothing, so allow them and fold them back into this same WebView below.
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = true
            setGeolocationEnabled(false)
            saveFormData = false
            databaseEnabled = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            androidx.webkit.WebSettingsCompat.setSafeBrowsingEnabled(web.settings, true)
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        attachBridge(web)

        // Redirect any popup back into the main WebView rather than opening a real
        // second window, so no navigation is silently dropped.
        web.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message,
            ): Boolean {
                val relay = WebView(view.context)
                relay.settings.javaScriptEnabled = true
                relay.settings.userAgentString = USER_AGENT
                relay.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        request?.url?.let { url ->
                            if (isAllowedHost(url.host)) view.loadUrl(url.toString())
                        }
                        relay.destroy()
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = relay
                resultMsg.sendToTarget()
                return true
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view?.let(failedLoads::remove)
                if (view === worker) {
                    workerLoading = true
                    workerLoadFailed = false
                    bridgeInstalled = false
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                // Sub-resource failures are routine; only the page itself matters here.
                if (view == null || request?.isForMainFrame != true) return
                failedLoads += view
                if (view === worker) workerLoadFailed = true
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val host = request?.url?.host
                // Refuse to navigate anywhere that is not part of the Google sign-in
                // or Photos flow. Stops an injected/redirected page from driving the
                // session somewhere unexpected.
                return !isAllowedHost(host)
            }

            /**
             * The WebView renderer runs in its own process and can be killed under
             * memory pressure. If this returns false, Android kills the whole app —
             * so handle it, drop the dead view, and let the session rebuild on demand.
             */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?,
            ): Boolean {
                if (view === worker) {
                    worker = null
                    bridgeInstalled = false
                    workerLoading = false
                    _state.value = State.UNKNOWN
                    // Any in-flight RPCs will never be answered; fail them now rather
                    // than leaving their callers hanging until timeout.
                    failAllPending("RENDERER_GONE")
                }
                view?.destroy()
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val host = Uri.parse(url ?: "").host
                if (view != null && host == "photos.google.com" && view !in failedLoads) {
                    view.evaluateJavascript(loadBridgeJs(), null)
                }
                if (view === worker) {
                    workerLoading = false
                    workerFinishedAt = SystemClock.elapsedRealtime()
                }
                onPageFinished(url)
            }
        }
    }

    private fun attachBridge(web: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // Origin-scoped injection: `gpcBridge` only exists on photos.google.com.
            // This is strictly safer than addJavascriptInterface, which is exposed to
            // every frame of every origin the WebView happens to load.
            WebViewCompat.addWebMessageListener(
                web,
                BRIDGE_NAME,
                setOf(ORIGIN),
            ) { view, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, _ ->
                if (!isMainFrame) return@addWebMessageListener
                if (sourceOrigin.toString().trimEnd('/') != ORIGIN) return@addWebMessageListener
                message.data?.let { onBridgeMessage(view, it) }
            }
        } else {
            // Fallback for very old WebView providers. Guarded by the navigation
            // allowlist above, which keeps non-Google origins out of this WebView.
            web.addJavascriptInterface(LegacyBridge { onBridgeMessage(web, it) }, BRIDGE_NAME)
        }
    }

    private class LegacyBridge(private val sink: (String) -> Unit) {
        @android.webkit.JavascriptInterface
        fun postMessage(data: String) = sink(data)
    }

    /**
     * Marks the session live and forces the cookie jar to disk.
     *
     * Without the flush, cookies exist only in memory and are lost when the process
     * is killed — which meant signing in again on every launch.
     */
    private fun markReady() {
        _state.value = State.READY
        runCatching { CookieManager.getInstance().flush() }
    }

    private fun failAllPending(code: String) {
        val inFlight = pending.keys.toList()
        inFlight.forEach { id ->
            pending.remove(id)?.complete(Result.failure(SessionException(code)))
        }
    }

    private fun onBridgeMessage(from: WebView, raw: String) {
        // Only the worker carries out calls. The login WebView runs the bridge too (it
        // loads the same pages), but its word says nothing about the worker's page —
        // trusting it marked the worker ready while its page had no bridge at all.
        if (from !== worker) return
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val id = obj["id"]?.jsonPrimitive?.content ?: return

        if (id == "__installed") {
            bridgeInstalled = true
            val ready = runCatching {
                val probe = json.parseToJsonElement(obj["data"]!!.jsonPrimitive.content).jsonObject
                rememberAccountPath(probe["path"]?.jsonPrimitive?.content)
                probe["ready"]?.jsonPrimitive?.content == "true"
            }.getOrDefault(false)
            // "Not ready" is not proof of a sign-out on its own; ensureReady decides that.
            if (ready) markReady()
            return
        }

        val deferred = pending.remove(id) ?: return
        val ok = obj["ok"]?.jsonPrimitive?.content == "true"
        if (ok) {
            deferred.complete(Result.success(obj["data"]?.jsonPrimitive?.content ?: "null"))
        } else {
            val err = obj["error"]?.jsonPrimitive?.content ?: "RPC_FAILED"
            if (err == "NO_SESSION") _state.value = State.SIGNED_OUT
            deferred.complete(Result.failure(SessionException(err)))
        }
    }

    /** Creates (once) the long-lived, invisible WebView that carries out API calls. */
    private suspend fun ensureWorker(): WebView = withContext(Dispatchers.Main) {
        worker?.let { return@withContext it }
        val web = WebView(appContext)
        configure(web)
        worker = web
        loadWorker(web)
        web
    }

    /** Main thread. Flags are set here, not only in onPageStarted, which fires later. */
    private fun loadWorker(web: WebView) {
        workerLoading = true
        workerLoadFailed = false
        bridgeInstalled = false
        web.loadUrl("$ORIGIN/")
    }

    /**
     * True once a signed-in Photos page with a live bridge is available.
     *
     * Sets the state to match what it found, and only calls it a sign-out on real
     * evidence: a Photos page that loaded, was reloaded with the current cookies, and
     * still had no session. A failed load or a timeout is [State.OFFLINE] instead.
     */
    suspend fun ensureReady(timeoutMs: Long = 45_000): Boolean {
        val web = ensureWorker()
        // A page that failed earlier will not recover on its own; start it again.
        withContext(Dispatchers.Main) { if (workerLoadFailed) loadWorker(web) }

        var reloaded = false
        val outcome: State? = withTimeoutOrNull(timeoutMs) {
            while (true) {
                if (probe(web)) return@withTimeoutOrNull State.READY
                if (workerLoadFailed) return@withTimeoutOrNull State.OFFLINE

                // Give a finished page a moment for the bridge to install and report.
                val settled = !workerLoading &&
                    SystemClock.elapsedRealtime() - workerFinishedAt > SETTLE_MS
                if (settled) {
                    if (reloaded) return@withTimeoutOrNull State.SIGNED_OUT
                    // Loaded without a session. It may predate a sign-in that just
                    // finished in the login WebView — load it again with today's cookies.
                    reloaded = true
                    withContext(Dispatchers.Main) { loadWorker(web) }
                }
                delay(400)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

        return when (outcome) {
            State.READY -> {
                markReady()
                true
            }
            State.SIGNED_OUT -> {
                _state.value = State.SIGNED_OUT
                false
            }
            // Explicit load failure, or nothing loaded in time: Google is not reachable.
            else -> {
                _state.value = State.OFFLINE
                false
            }
        }
    }

    private suspend fun probe(web: WebView): Boolean {
        val id = "p${idSeq.incrementAndGet()}"
        val deferred = CompletableDeferred<Result<String>>()
        pending[id] = deferred
        withContext(Dispatchers.Main) {
            // Answers at once when the page has no bridge, rather than leaving the
            // caller to wait out the full timeout for a reply that cannot come.
            web.evaluateJavascript(
                "(function(){ if(window.__gpc){window.__gpc.checkSession('$id'); return true;} return false; })();",
            ) { hasBridge ->
                if (hasBridge != "true") pending.remove(id)?.complete(Result.failure(SessionException("NO_BRIDGE")))
            }
        }
        // withTimeoutOrNull, not runCatching: the latter would also swallow the
        // caller being cancelled, and keep probing a screen nobody is looking at.
        val res = withTimeoutOrNull(5_000) { deferred.await() }
        pending.remove(id)
        val payload = res?.getOrNull() ?: return false
        return runCatching {
            val obj = json.parseToJsonElement(payload).jsonObject
            rememberAccountPath(obj["path"]?.jsonPrimitive?.content)
            obj["ready"]?.jsonPrimitive?.content == "true"
        }.getOrDefault(false)
    }

    /** Invokes a batchexecute RPC in the page and returns its decoded JSON payload. */
    suspend fun rpc(rpcid: String, args: JsonArray, timeoutMs: Long = 60_000): JsonElement =
        rpcLock.withLock {
            val web = ensureWorker()
            if (!bridgeInstalled && !ensureReady()) {
                throw SessionException(if (_state.value == State.OFFLINE) "OFFLINE" else "NO_SESSION")
            }

            val id = "r${idSeq.incrementAndGet()}"
            val deferred = CompletableDeferred<Result<String>>()
            pending[id] = deferred

            // Every value goes into the script as a JSON string, which is also a valid
            // JavaScript string literal: correct escaping without writing any by hand.
            val script = "(function(){ if(window.__gpc){window.__gpc.call(" +
                "${jsString(id)},${jsString(rpcid)},${jsString(args.toString())}); return true;} " +
                "return false; })();"

            withContext(Dispatchers.Main) {
                web.evaluateJavascript(script) { hasBridge ->
                    // The page lost its bridge (it navigated). Fail now rather than wait out
                    // the timeout, and make the next call re-establish it.
                    if (hasBridge != "true") {
                        bridgeInstalled = false
                        pending.remove(id)?.complete(Result.failure(SessionException("NO_BRIDGE")))
                    }
                }
            }

            val result = try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                pending.remove(id)
                throw SessionException("TIMEOUT")
            }

            val payload = result.getOrElse { throw it }
            if (payload == "null") JsonNull else json.parseToJsonElement(payload)
        }

    /** A string primitive prints as a quoted, fully escaped JSON string. */
    private fun jsString(value: String): String = JsonPrimitive(value).toString()

    /** Wipes every trace of the Google session from the device. */
    suspend fun signOut() = withContext(Dispatchers.Main) {
        bridgeInstalled = false
        _state.value = State.SIGNED_OUT
        // Waits for the removal: flushing before it completes can persist the old jar.
        suspendCancellableCoroutine { cont ->
            CookieManager.getInstance().removeAllCookies { cont.resume(Unit) }
        }
        CookieManager.getInstance().flush()
        WebView(appContext).apply {
            clearCache(true)
            clearHistory()
            clearFormData()
            destroy()
        }
        android.webkit.WebStorage.getInstance().deleteAllData()
        worker?.destroy()
        worker = null
    }
}

/**
 * A failed call into Google Photos. [code] is for logic; [message] is written for
 * the person using the app, since it is what error banners show.
 */
class SessionException(val code: String) : Exception(describe(code)) {
    private companion object {
        fun describe(code: String): String = when (code) {
            "OFFLINE" -> "Can't reach Google Photos. Check your connection and try again."
            "NO_SESSION" -> "You're signed out of Google Photos."
            "TIMEOUT" -> "Google Photos took too long to answer. Try again."
            "RATE_LIMITED" -> "Google is limiting requests right now. Wait a minute and try again."
            "RENDERER_GONE", "NO_BRIDGE" ->
                "The connection to Google Photos was interrupted. Try again."
            else -> "Google Photos request failed ($code)."
        }
    }
}
