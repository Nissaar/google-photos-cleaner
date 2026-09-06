package xyz.photocleaner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Keep = Color(0xFF2E9E5B)
private val Delete = Color(0xFFE5484D)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6EA8FF),
    onPrimary = Color(0xFF00274D),
    secondary = Keep,
    error = Delete,
    background = Color(0xFF0D0F12),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF16191D),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF232830),
    onSurfaceVariant = Color(0xFFB6BCC6),
    outline = Color(0xFF39404A),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    onPrimary = Color.White,
    secondary = Keep,
    error = Delete,
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF12141A),
    surface = Color.White,
    onSurface = Color(0xFF12141A),
    surfaceVariant = Color(0xFFE9ECF1),
    onSurfaceVariant = Color(0xFF4A515C),
    outline = Color(0xFFC7CDD6),
)

/** Semantic colours for the two verdicts, used by the swipe deck. */
object VerdictColors {
    val keep = Keep
    val delete = Delete
}

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun PhotoCleanerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
