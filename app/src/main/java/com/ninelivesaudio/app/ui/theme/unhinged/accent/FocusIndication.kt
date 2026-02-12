package com.ninelivesaudio.app.ui.theme.unhinged.accent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ninelivesaudio.app.ui.theme.unhinged.ImpossibleAccent
import com.ninelivesaudio.app.ui.components.unhinged.LocalUnhingedSettings
import com.ninelivesaudio.app.ui.theme.unhinged.isUnhingedThemeActive

/**
 * Archive Focus Indication
 *
 * Apply this modifier to make interactive elements show the impossible accent
 * when focused (e.g., keyboard navigation, accessibility focus).
 *
 * **Normal mode**: Standard Material 3 focus indication
 * **Unhinged mode**: Thin impossible accent border (1-2dp)
 *
 * This is one of the ONLY approved uses of the impossible accent.
 * See AccentRules.md for full usage guidelines.
 *
 * @param shape The shape of the focus border (default: rounded 8dp)
 * @param borderWidth Width of the focus border (default: 2dp)
 */
fun Modifier.archiveFocusIndication(
    shape: Shape = RoundedCornerShape(8.dp),
    borderWidth: Dp = 2.dp
): Modifier = composed {
    val unhingedSettings = LocalUnhingedSettings.current
    val isUnhinged = unhingedSettings.isUnhingedThemeActive

    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    this
        .focusable(interactionSource = interactionSource)
        .then(
            if (isFocused && isUnhinged) {
                Modifier.border(
                    width = borderWidth,
                    color = ImpossibleAccent,
                    shape = shape
                )
            } else if (isFocused) {
                // Normal mode: use primary color for focus
                Modifier.border(
                    width = borderWidth,
                    color = MaterialTheme.colorScheme.primary,
                    shape = shape
                )
            } else {
                Modifier
            }
        )
}

/**
 * Selection Edge Highlight
 *
 * Apply this to list items to show a small edge highlight when selected.
 * This creates a 2-3dp accent strip on the left edge.
 *
 * **Normal mode**: Uses primary color (gold)
 * **Unhinged mode**: Uses impossible accent
 *
 * @param isSelected Whether this item is currently selected
 * @param edgeWidth Width of the selection edge (default: 3dp)
 */
fun Modifier.selectionEdgeHighlight(
    isSelected: Boolean,
    edgeWidth: Dp = 3.dp
): Modifier = composed {
    val unhingedSettings = LocalUnhingedSettings.current
    val isUnhinged = unhingedSettings.isUnhingedThemeActive

    if (isSelected) {
        val accentColor = if (isUnhinged) {
            ImpossibleAccent
        } else {
            MaterialTheme.colorScheme.primary
        }

        this.border(
            BorderStroke(
                width = edgeWidth,
                color = accentColor
            ),
            shape = RoundedCornerShape(
                topStart = 0.dp,
                topEnd = 4.dp,
                bottomStart = 0.dp,
                bottomEnd = 4.dp
            )
        )
    } else {
        this
    }
}

/**
 * Active Indicator (for tabs, bottom nav, etc.)
 *
 * Shows a small indicator when an item is active/selected.
 * Typically used as an underline or side accent.
 *
 * @param isActive Whether this item is currently active
 * @param indicatorColor Override color (null = use theme-appropriate color)
 */
@Composable
fun rememberActiveIndicatorColor(
    isActive: Boolean,
    indicatorColor: Color? = null
): Color {
    val unhingedSettings = LocalUnhingedSettings.current
    val isUnhinged = unhingedSettings.isUnhingedThemeActive

    return when {
        !isActive -> Color.Transparent
        indicatorColor != null -> indicatorColor
        isUnhinged -> ImpossibleAccent
        else -> MaterialTheme.colorScheme.primary
    }
}

/**
 * Progress Accent Overlay
 *
 * Creates a subtle impossible accent overlay for progress indicators.
 * This should be layered OVER the gold progress, not replacing it.
 *
 * Use sparingly - only for specific progress components where the
 * extra visual interest enhances the "alive" feeling.
 *
 * @param progress Current progress value (0f to 1f)
 * @param showAccent Whether to show the accent overlay
 */
@Composable
fun rememberProgressAccentColor(
    progress: Float,
    showAccent: Boolean = true
): Color? {
    val unhingedSettings = LocalUnhingedSettings.current
    val isUnhinged = unhingedSettings.isUnhingedThemeActive

    return if (isUnhinged && showAccent && progress > 0f) {
        ImpossibleAccent.copy(alpha = 0.2f) // Very subtle overlay
    } else {
        null
    }
}
