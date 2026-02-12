package com.ninelivesaudio.app.ui.components.unhinged

import androidx.compose.runtime.compositionLocalOf
import com.ninelivesaudio.app.settings.unhinged.UnhingedSettings

/**
 * CompositionLocal for UnhingedSettings
 * Allows any composable to access unhinged mode state without prop drilling
 *
 * Usage:
 * ```
 * val settings = LocalUnhingedSettings.current
 * if (settings.isUnhinged) {
 *     // Apply unhinged styling
 * }
 * ```
 */
val LocalUnhingedSettings = compositionLocalOf { UnhingedSettings.Default }
