package com.ninelivesaudio.app.ui.theme.unhinged

import android.app.Activity
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.ninelivesaudio.app.settings.unhinged.UnhingedSettings
import com.ninelivesaudio.app.ui.components.unhinged.LocalUnhingedSettings
import com.ninelivesaudio.app.ui.theme.Shapes
import com.ninelivesaudio.app.ui.theme.Typography

private const val TAG = "UnhingedTheme"

/**
 * Archive Beneath Color Scheme (Unhinged Mode)
 *
 * The dark, atmospheric theme for unhinged mode. Maintains identical layout
 * to the normal theme but uses the Archive Beneath palette:
 * - Obsidian/indigo backgrounds
 * - Gold filament accents
 * - Impossible accent for focus (used sparingly)
 */
private val ArchiveBeneathColorScheme = darkColorScheme(
    // Primary = Gold Filament (accent)
    primary = GoldFilament,
    onPrimary = ArchiveVoidDeep,
    primaryContainer = GoldFilamentFaint,
    onPrimaryContainer = GoldFilamentBright,

    // Secondary = Muted text colors
    secondary = ArchiveTextTertiary,
    onSecondary = ArchiveVoidDeep,
    secondaryContainer = ArchiveVoidElevated,
    onSecondaryContainer = ArchiveTextSecondary,

    // Tertiary = Impossible Accent (for focus/selection only)
    tertiary = ImpossibleAccent,
    onTertiary = ArchiveVoidDeep,
    tertiaryContainer = ArchiveVoidElevated,
    onTertiaryContainer = ImpossibleAccent,

    // Background & Surface
    background = ArchiveVoidDeep,
    onBackground = ArchiveTextPrimary,
    surface = ArchiveVoidBase,
    onSurface = ArchiveTextPrimary,
    surfaceVariant = ArchiveVoidSurface,
    onSurfaceVariant = ArchiveTextSecondary,
    surfaceContainerLowest = ArchiveVoidDeep,
    surfaceContainerLow = ArchiveVoidBase,
    surfaceContainer = ArchiveVoidSurface,
    surfaceContainerHigh = ArchiveVoidElevated,
    surfaceContainerHighest = ArchiveVoidElevated,

    // Error
    error = ArchiveError,
    onError = ArchiveVoidDeep,
    errorContainer = ArchiveError.copy(alpha = 0.2f),
    onErrorContainer = ArchiveError,

    // Outline & Dividers
    outline = ArchiveOutline,
    outlineVariant = ArchiveDivider,

    // Inverse (for snackbars, etc.)
    inverseSurface = ArchiveTextSecondary,
    inverseOnSurface = ArchiveVoidDeep,
    inversePrimary = GoldFilamentDim
)

/**
 * Unhinged Mode Theme Wrapper
 *
 * Apply this theme when unhingedSettings.unhingedThemeEnabled is true.
 * Uses the Archive Beneath color scheme while maintaining all other
 * theme properties (typography, shapes, etc.).
 *
 * @param unhingedSettings The unhinged mode configuration
 * @param content The app content
 */
@Composable
fun UnhingedTheme(
    unhingedSettings: UnhingedSettings = UnhingedSettings.Default,
    content: @Composable () -> Unit
) {
    Log.d(TAG, "UnhingedTheme: Applying Archive Beneath theme")
    Log.d(TAG, "UnhingedTheme: Settings - " +
            "unhingedThemeEnabled=${unhingedSettings.unhingedThemeEnabled}, " +
            "anomaliesEnabled=${unhingedSettings.anomaliesEnabled}, " +
            "whispersEnabled=${unhingedSettings.whispersEnabled}, " +
            "copyMode=${unhingedSettings.copyMode}")

    val colorScheme = ArchiveBeneathColorScheme
    val view = LocalView.current

    // Set system bars to match the unhinged theme
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = ArchiveVoidDeep.toArgb()
            window.navigationBarColor = ArchiveVoidBase.toArgb()

            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false

            Log.d(TAG, "UnhingedTheme: System bars set to Archive colors")
        }
    }

    // Provide unhinged settings to all composables
    CompositionLocalProvider(LocalUnhingedSettings provides unhingedSettings) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}

/**
 * Extension property to check if unhinged theme is active.
 * Use this in composables to conditionally apply unhinged-specific styling.
 */
val UnhingedSettings.isUnhingedThemeActive: Boolean
    get() = unhingedThemeEnabled

/**
 * Extension property to check if anomalies should be shown.
 * Respects both the anomalies toggle and reduce motion setting.
 */
val UnhingedSettings.shouldShowAnomalies: Boolean
    get() = anomaliesEnabled && !reduceMotionRequested

/**
 * Extension property to check if whispers should be shown.
 */
val UnhingedSettings.shouldShowWhispers: Boolean
    get() = whispersEnabled
