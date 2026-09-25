package xyz.photocleaner

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import xyz.photocleaner.session.GPhotosSession
import xyz.photocleaner.ui.LoginScreen
import xyz.photocleaner.ui.MonthsScreen
import xyz.photocleaner.ui.ReviewScreen
import xyz.photocleaner.ui.SettingsScreen
import xyz.photocleaner.ui.SwipeScreen
import xyz.photocleaner.ui.theme.PhotoCleanerTheme
import xyz.photocleaner.vm.AppViewModel
import java.time.YearMonth

/**
 * FragmentActivity rather than ComponentActivity because BiometricPrompt requires it.
 */
class MainActivity : FragmentActivity() {

    private val appVm: AppViewModel by viewModels()

    /** True while the app lock covers the screen. Starts locked; RootScreen opens it. */
    private var locked by mutableStateOf(true)

    /** When the app last went to the background, for deciding whether to re-lock. */
    private var backgroundedAt: Long? = null

    /**
     * The device-credential screen is its own activity, so entering a PIN stops this
     * one. That must not count as leaving the app, or unlocking would re-lock it.
     */
    private var authInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Secure by default, before any content is drawn. RootScreen relaxes this only
        // if the user has explicitly opted in, so there is no unprotected first frame.
        setSecure(true)

        setContent {
            PhotoCleanerTheme {
                // targetSdk 35 draws edge-to-edge by default, so content would sit
                // under the status and navigation bars unless it is inset here.
                Surface(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RootScreen()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!authInProgress && !locked) backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        val since = backgroundedAt ?: return
        backgroundedAt = null
        val away = SystemClock.elapsedRealtime() - since
        if (appVm.appLock.value == true && away >= RELOCK_AFTER_MS) locked = true
    }

    /**
     * FLAG_SECURE keeps the library out of screenshots, screen recordings and the
     * recent-apps thumbnail. It is also why bug-report screenshots come out blank,
     * so it is user-controllable — defaulting to on.
     */
    private fun setSecure(secure: Boolean) {
        if (secure) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    @Composable
    private fun RootScreen() {
        val lockEnabled by appVm.appLock.collectAsState()
        val allowScreenshots by appVm.allowScreenshots.collectAsState()

        // Once shown, the library stays composed underneath a re-lock, so unlocking
        // returns you to where you were rather than to the start screen.
        var contentStarted by remember { mutableStateOf(false) }

        LaunchedEffect(allowScreenshots) { setSecure(!allowScreenshots) }

        LaunchedEffect(lockEnabled) {
            if (lockEnabled == false) locked = false
        }
        if (!locked) SideEffect { contentStarted = true }

        Box(Modifier.fillMaxSize()) {
            if (!locked || contentStarted) AppNavigation(appVm)

            if (locked) {
                // Null means the setting has not been read yet: decide nothing until it has.
                if (lockEnabled == true) {
                    LockCover()
                } else {
                    Surface(Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
            }
        }
    }

    /** Opaque cover shown while locked. Surface also swallows touches meant for below. */
    @Composable
    private fun LockCover() {
        var noCredential by remember { mutableStateOf(false) }
        var attempt by remember { mutableStateOf(0) }

        BackHandler { finish() }
        LaunchedEffect(attempt) {
            noCredential = false
            promptUnlock(
                onSuccess = { locked = false },
                onNoCredential = { noCredential = true },
            )
        }

        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!noCredential) {
                    CircularProgressIndicator()
                    return@Column
                }
                Text(
                    "This phone no longer has a screen lock",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Photo Cleaner is set to require an unlock. Set a PIN, pattern or " +
                        "fingerprint on this phone, then try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = {
                    runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                }) { Text("Open security settings") }
                TextButton(onClick = { attempt++ }) { Text("Try again") }
            }
        }
    }

    private fun promptUnlock(onSuccess: () -> Unit, onNoCredential: () -> Unit) {
        val manager = BiometricManager.from(this)
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        when (manager.canAuthenticate(allowed)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            // The lock was turned on, and the screen lock it relied on has since been
            // removed. Opening anyway would make the setting meaningless.
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                onNoCredential()
                return
            }
            // Hardware missing or unavailable. Failing closed here would lock people
            // out of their own data with nothing they could do about it.
            else -> {
                onSuccess()
                return
            }
        }

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    authInProgress = false
                    onSuccess()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    authInProgress = false
                    // Any hard error (cancel, lockout) leaves the app locked.
                    finish()
                }
            },
        )

        authInProgress = true
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Photo Cleaner")
                .setSubtitle("Your photo review is protected")
                .setAllowedAuthenticators(allowed)
                .build(),
        )
    }

    private companion object {
        /** Long enough to answer a notification, short enough to matter. */
        const val RELOCK_AFTER_MS = 30_000L
    }
}

private object Routes {
    const val LOGIN = "login"
    const val MONTHS = "months"
    const val SWIPE = "swipe/{month}"
    const val REVIEW = "review"
    const val SETTINGS = "settings"
    fun swipe(month: YearMonth) = "swipe/$month"
}

@Composable
private fun AppNavigation(appVm: AppViewModel) {
    val nav = rememberNavController()
    val sessionState by appVm.sessionState.collectAsState()
    val startSignedIn by appVm.startSignedIn.collectAsState()

    // Only a local flag read stands between launch and the first screen.
    val remembered = startSignedIn ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val start = if (remembered) Routes.MONTHS else Routes.LOGIN

    // If the remembered session turns out to be dead, fall back to sign-in.
    LaunchedEffect(sessionState) {
        if (sessionState == GPhotosSession.State.SIGNED_OUT) {
            val current = nav.currentBackStackEntry?.destination?.route
            if (current != null && current != Routes.LOGIN) {
                nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
            }
        }
    }

    NavHost(navController = nav, startDestination = start) {

        composable(Routes.LOGIN) {
            LoginScreen(
                onSignedIn = {
                    appVm.onLoginCompleted()
                    nav.navigate(Routes.MONTHS) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.MONTHS) {
            MonthsScreen(
                appVm = appVm,
                onMonthPicked = { nav.navigate(Routes.swipe(it)) },
                onReview = { nav.navigate(Routes.REVIEW) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            Routes.SWIPE,
            arguments = listOf(navArgument("month") { type = NavType.StringType }),
        ) { entry ->
            val raw = entry.arguments?.getString("month")
            val month = runCatching { YearMonth.parse(raw) }.getOrNull()
            if (month == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                SwipeScreen(
                    month = month,
                    onBack = { nav.popBackStack() },
                    onReview = { nav.navigate(Routes.REVIEW) },
                )
            }
        }

        composable(Routes.REVIEW) {
            ReviewScreen(appVm = appVm, onBack = { nav.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                appVm = appVm,
                onBack = { nav.popBackStack() },
                onSignedOut = {
                    nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                },
            )
        }
    }
}
