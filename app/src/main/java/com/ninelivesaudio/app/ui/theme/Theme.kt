package com.ninelivesaudio.app.ui.theme

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
import com.ninelivesaudio.app.ui.components.unhinged.LocalUnhingedSettings

private const val TAG = "NineLivesTheme"

private val CosmicDarkColorScheme = darkColorScheme(
    // Primary = Sigil Gold (accent)
    primary = SigilGold,
    onPrimary = VoidDeep,
    primaryContainer = SigilGoldFaint,
    onPrimaryContainer = SigilGoldBright,

    // Secondary = Mist (subdued UI elements)
    secondary = Mist,
    onSecondary = VoidDeep,
    secondaryContainer = VoidElevated,
    onSecondaryContainer = StarlightDim,

    // Tertiary = Cosmic Info blue (links, info)
    tertiary = CosmicInfo,
    onTertiary = VoidDeep,
    tertiaryContainer = VoidElevated,
    onTertiaryContainer = CosmicInfo,

    // Background & Surface
    background = VoidDeep,
    onBackground = Starlight,
    surface = VoidBase,
    onSurface = Starlight,
    surfaceVariant = VoidSurface,
    onSurfaceVariant = StarlightDim,
    surfaceContainerLowest = VoidDeep,
    surfaceContainerLow = VoidBase,
    surfaceContainer = VoidSurface,
    surfaceContainerHigh = VoidElevated,
    surfaceContainerHighest = VoidElevated,

    // Error
    error = CosmicError,
    onError = VoidDeep,
    errorContainer = CosmicError.copy(alpha = 0.2f),
    onErrorContainer = CosmicError,

    // Outline & Dividers
    outline = MistFaint,
    outlineVariant = VoidElevated,

    // Inverse (for snackbars, etc.)
    inverseSurface = StarlightDim,
    inverseOnSurface = VoidDeep,
    inversePrimary = SigilGoldDim
)

@Composable
fun NineLivesAudioTheme(
    unhingedSettings: com.ninelivesaudio.app.settings.unhinged.UnhingedSettings? = null,
    content: @Composable () -> Unit
) {
    val isUnhinged = unhingedSettings?.unhingedThemeEnabled == true
    Log.d(TAG, "NineLivesAudioTheme: isUnhinged=$isUnhinged, settings=$unhingedSettings")

    // Switch between normal and unhinged theme based on settings
    if (isUnhinged) {
        Log.d(TAG, "NineLivesAudioTheme: Activating UNHINGED (Archive Beneath) theme")
        com.ninelivesaudio.app.ui.theme.unhinged.UnhingedTheme(
            unhingedSettings = unhingedSettings,
            content = content
        )
    } else {
        Log.d(TAG, "NineLivesAudioTheme: Using NORMAL (Cosmic) theme")
        // Normal cosmic theme (existing)
        val colorScheme = CosmicDarkColorScheme
        val view = LocalView.current

        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as Activity).window
                // Set status bar and navigation bar to VoidDeep for immersive dark feel
                window.statusBarColor = VoidDeep.toArgb()
                window.navigationBarColor = VoidBase.toArgb()
                // Use light status bar icons (white) on dark background
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = false
                insetsController.isAppearanceLightNavigationBars = false
            }
        }

        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
        ) {
            CompositionLocalProvider(
                LocalUnhingedSettings provides (unhingedSettings ?: com.ninelivesaudio.app.settings.unhinged.UnhingedSettings.Default),
                content = content
            )
        }
    }
}
