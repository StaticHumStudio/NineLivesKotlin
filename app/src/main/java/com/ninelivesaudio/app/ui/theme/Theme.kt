package com.ninelivesaudio.app.ui.theme

import androidx.compose.runtime.Composable
import com.ninelivesaudio.app.domain.model.ThemeMode
import com.ninelivesaudio.app.settings.unhinged.UnhingedSettings
import com.ninelivesaudio.app.ui.theme.unhinged.UnhingedTheme

/**
 * Nine Lives Audio Theme.
 *
 * The Archive Beneath layout is permanent. The color palette is selectable via
 * [themeMode], defaulting to NOIR (the original deep-indigo, gold-filament look)
 * so existing users see no change unless they pick another theme.
 *
 * UnhingedSettings still controls feature preferences (anomalies, whispers, motion)
 * independently of the color theme.
 */
@Composable
fun NineLivesAudioTheme(
    themeMode: ThemeMode = ThemeMode.NOIR,
    unhingedSettings: UnhingedSettings? = null,
    content: @Composable () -> Unit
) {
    UnhingedTheme(
        themeMode = themeMode,
        unhingedSettings = unhingedSettings ?: UnhingedSettings.Default,
        content = content
    )
}
