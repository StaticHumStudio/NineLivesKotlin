package com.ninelivesaudio.app.ui.theme.unhinged

import android.app.Activity
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.ninelivesaudio.app.domain.model.ThemeMode
import com.ninelivesaudio.app.settings.unhinged.UnhingedSettings
import com.ninelivesaudio.app.ui.components.unhinged.LocalUnhingedSettings
import com.ninelivesaudio.app.ui.theme.LocalNineLivesColors
import com.ninelivesaudio.app.ui.theme.Shapes
import com.ninelivesaudio.app.ui.theme.Typography
import com.ninelivesaudio.app.ui.theme.colorSchemeFor
import com.ninelivesaudio.app.ui.theme.nineLivesColorsFor

private const val TAG = "UnhingedTheme"

/**
 * Archive Beneath Theme Wrapper
 *
 * Resolves the selected [ThemeMode] into a brand color holder and a Material color
 * scheme, provides the brand colors via LocalNineLivesColors so the whole app
 * recolors on theme switch, and keeps all other theme properties (typography,
 * shapes) unchanged.
 *
 * @param themeMode The selected color theme. Defaults to NOIR (the original
 *   Archive Beneath look) so behaviour is unchanged unless a theme is chosen.
 * @param unhingedSettings The unhinged mode feature configuration
 * @param content The app content
 */
@Composable
fun UnhingedTheme(
    themeMode: ThemeMode = ThemeMode.NOIR,
    unhingedSettings: UnhingedSettings = UnhingedSettings.Default,
    content: @Composable () -> Unit
) {
    Log.d(TAG, "UnhingedTheme: Applying theme=$themeMode")
    Log.d(TAG, "UnhingedTheme: Settings - " +
            "unhingedThemeEnabled=${unhingedSettings.unhingedThemeEnabled}, " +
            "anomaliesEnabled=${unhingedSettings.anomaliesEnabled}, " +
            "whispersEnabled=${unhingedSettings.whispersEnabled}, " +
            "copyMode=${unhingedSettings.copyMode}")

    val brandColors = nineLivesColorsFor(themeMode)
    val colorScheme = colorSchemeFor(themeMode)
    val view = LocalView.current

    // Set system bar icon polarity to match the theme's background luminance.
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window

            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = brandColors.isLight
            insetsController.isAppearanceLightNavigationBars = brandColors.isLight

            Log.d(TAG, "UnhingedTheme: System bars set, isLight=${brandColors.isLight}")
        }
    }

    // Provide brand colors and unhinged settings to all composables
    CompositionLocalProvider(
        LocalNineLivesColors provides brandColors,
        LocalUnhingedSettings provides unhingedSettings,
    ) {
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
