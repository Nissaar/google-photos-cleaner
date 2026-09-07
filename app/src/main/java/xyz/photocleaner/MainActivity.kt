package xyz.photocleaner

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
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
    private fun RootScreen(appVm: AppViewModel = viewModel()) {
        val lockEnabled by appVm.appLock.collectAsState()
        val allowScreenshots by appVm.allowScreenshots.collectAsState()
        var unlocked by remember { mutableStateOf(false) }

        LaunchedEffect(allowScreenshots) { setSecure(!allowScreenshots) }

        LaunchedEffect(lockEnabled) {
            if (!lockEnabled) unlocked = true
        }

        if (lockEnabled && !unlocked) {
            LaunchedEffect(Unit) { promptUnlock(onSuccess = { unlocked = true }) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        AppNavigation(appVm)
    }

    private fun promptUnlock(onSuccess: () -> Unit) {
        val manager = BiometricManager.from(this)
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        if (manager.canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No biometric and no device credential enrolled — nothing to check against.
            onSuccess()
            return
        }

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) = onSuccess()

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    // Any hard error (cancel, lockout) leaves the app locked.
                    finish()
                }
            },
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Photo Cleaner")
                .setSubtitle("Your photo review is protected")
                .setAllowedAuthenticators(allowed)
                .build(),
        )
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
