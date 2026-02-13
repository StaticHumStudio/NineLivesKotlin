package com.ninelivesaudio.app.ui.theme

import android.util.Log
import androidx.compose.runtime.Composable
import com.ninelivesaudio.app.settings.unhinged.UnhingedSettings
import com.ninelivesaudio.app.ui.theme.unhinged.UnhingedTheme

private const val TAG = "NineLivesTheme"

/**
 * Nine Lives Audio Theme — Archive Beneath is the only reality.
 *
 * There is no "normal" mode. The Archive Beneath palette is always active.
 * UnhingedSettings still controls feature preferences (anomalies, whispers, motion)
 * but the theme itself is permanent.
 */
@Composable
fun NineLivesAudioTheme(
    unhingedSettings: UnhingedSettings? = null,
    content: @Composable () -> Unit
) {
    Log.d(TAG, "NineLivesAudioTheme: Archive Beneath — always active")
    UnhingedTheme(
        unhingedSettings = unhingedSettings ?: UnhingedSettings.Default,
        content = content
    )
}
