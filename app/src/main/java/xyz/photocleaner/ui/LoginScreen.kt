package xyz.photocleaner.ui

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import xyz.photocleaner.Graph
import xyz.photocleaner.session.GPhotosSession

/**
 * Hosts the real Google sign-in page.
 *
 * The credentials are typed into Google's own page inside a Chromium WebView; this
 * app has no login form, never sees a password, and never reads the resulting cookies.
 */
@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    val session = Graph.session
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Sign in to Google Photos", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "This is Google's own sign-in page. Your password is typed directly " +
                        "into Google and is never seen or stored by this app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Google's sign-in journey has dead ends: signed out it may serve the
                // Photos marketing page, and after signing in it can park on an
                // onboarding step (profile picture) whose Skip button does nothing.
                // These two escape hatches make neither a reason to restart the app.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { webView?.loadUrl(GPhotosSession.LOGIN_URL) },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    ) { Text("No sign-in form?") }
                    Spacer(Modifier.width(16.dp))
                    TextButton(
                        onClick = { webView?.loadUrl("${GPhotosSession.ORIGIN}/") },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    ) { Text("Skip to Photos ›") }
                }
            }
        }

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).also { web ->
                        session.configure(web) { url ->
                            loading = false
                            canGoBack = web.canGoBack()
                            // Once we land back on Photos itself, the sign-in flow is
                            // finished — verify by probing for a live session.
                            if (url != null && url.startsWith(GPhotosSession.ORIGIN)) {
                                checking = true
                            }
                        }
                        web.loadUrl(GPhotosSession.LOGIN_URL)
                        webView = web
                    }
                },
            )

            if (checking) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Checking your session…")
                    }
                }
            }
        }
    }

    LaunchedEffect(checking) {
        if (!checking) return@LaunchedEffect
        // ensureReady() drives the hidden worker WebView, which shares this cookie jar.
        val ready = session.ensureReady(timeoutMs = 25_000)
        checking = false
        if (ready) onSignedIn()
    }

    DisposableEffect(Unit) {
        onDispose {
            // Tear down the login view; the session lives on in the shared cookie jar.
            webView?.destroy()
            webView = null
        }
    }
}
