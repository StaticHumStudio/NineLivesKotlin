package com.ninelivesaudio.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * CompositionLocal carrying the active brand color holder.
 *
 * staticCompositionLocalOf is used (not compositionLocalOf) because the value
 * changes rarely (only on theme switch) and we want the whole subtree to recompose
 * when it does, without the read-tracking overhead.
 *
 * Default is the NOIR palette so any composable rendered outside a Theme wrapper
 * (previews, isolated tests) still resolves the original look.
 */
val LocalNineLivesColors = staticCompositionLocalOf { NoirColors }

/**
 * Accessor for brand colors, MaterialTheme-adjacent.
 *
 * Read brand tones as `NineLivesTheme.colors.goldFilament` from any composable.
 * Standard Material roles remain available via `MaterialTheme.colorScheme`.
 */
object NineLivesTheme {
    val colors: NineLivesColors
        @Composable
        @ReadOnlyComposable
        get() = LocalNineLivesColors.current
}
