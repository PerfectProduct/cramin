package pro.perfectproduct.cramin.app.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Цвета механики изучения: «ещё учу» — оранжевый, «знаю» — зелёный (SPEC §9.6). */
@Immutable
data class StudyColors(
    val learning: Color,
    val learningContainer: Color,
    val known: Color,
    val knownContainer: Color,
    val starred: Color,
)

val LocalStudyColors = staticCompositionLocalOf {
    StudyColors(
        learning = Color(0xFFF59E0B),
        learningContainer = Color(0x33F59E0B),
        known = Color(0xFF22C55E),
        knownContainer = Color(0x3322C55E),
        starred = Color(0xFFFACC15),
    )
}

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF9CBFFF),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF1F3B6E),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFB8C7E8),
    onSecondary = Color(0xFF223149),
    secondaryContainer = Color(0xFF2E3D57),
    onSecondaryContainer = Color(0xFFD8E3FF),
    tertiary = Color(0xFFE0BBFF),
    background = Color(0xFF12161D),
    onBackground = Color(0xFFEDF1F7),
    surface = Color(0xFF12161D),
    onSurface = Color(0xFFEDF1F7),
    surfaceVariant = Color(0xFF1E2126),
    onSurfaceVariant = Color(0xFFAFBACE),
    surfaceContainer = Color(0xFF1B1E23),
    surfaceContainerHigh = Color(0xFF1D2430),
    surfaceContainerHighest = Color(0xFF2B3037),
    surfaceContainerLow = Color(0xFF151C26),
    outline = Color(0xFF5C616B),
    outlineVariant = Color(0xFF2F343B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF382520),
    onErrorContainer = Color(0xFFFFC1B8),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF285BAC),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001A43),
    secondary = Color(0xFF565E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131B2C),
    tertiary = Color(0xFF6F4FA0),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1D2939),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF1D2939),
    surfaceVariant = Color(0xFFE6E8EE),
    onSurfaceVariant = Color(0xFF526176),
    surfaceContainer = Color(0xFFEFEEEA),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE2E2DE),
    surfaceContainerLow = Color(0xFFF0F3F8),
    outline = Color(0xFF757780),
    outlineVariant = Color(0xFFC5C6D0),
)

/**
 * Тёмная тема по умолчанию, светлая — если система в светлом режиме (API 29+).
 * На API 26–28 системного тёмного режима нет, поэтому тема всегда тёмная.
 */
@Composable
fun CraminTheme(
    darkTheme: Boolean = if (Build.VERSION.SDK_INT >= 29) isSystemInDarkTheme() else true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = CraminTypography,
        content = content,
    )
}

object CraminThemeTokens {
    val study: StudyColors
        @Composable get() = LocalStudyColors.current
}

val MaterialTheme.study: StudyColors
    @Composable get() = LocalStudyColors.current
