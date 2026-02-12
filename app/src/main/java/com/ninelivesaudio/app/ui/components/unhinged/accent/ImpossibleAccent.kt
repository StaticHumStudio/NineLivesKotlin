package com.ninelivesaudio.app.ui.components.unhinged.accent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ninelivesaudio.app.ui.components.unhinged.LocalUnhingedSettings

/**
 * Impossible Accent - Verdigris (#3DBFA0 → #2EE8B0) focus/selection system
 *
 * This color should be RARE and ONLY appear on:
 * - Focus borders (keyboard navigation)
 * - Selection highlights (when items are actively selected)
 * - Active state indicators (currently playing, current page, etc.)
 *
 * **Design Philosophy:**
 * - This is an "impossible" color that shouldn't exist in the Archive
 * - It's intrusive and draws the eye immediately
 * - Use sparingly - only for states that need instant attention
 * - In Normal mode, uses standard Material3 colors
 * - In Unhinged mode, uses verdigris accent
 *
 * **Color Values:**
 * - Muted: #3DBFA0 (subtle baseline)
 * - Bright: #2EE8B0 (active/hover state)
 */

// Impossible Accent colors
private val ImpossibleAccent = Color(0xFF3DBFA0)      // Muted verdigris
private val ImpossibleAccentBright = Color(0xFF2EE8B0) // Bright verdigris

/**
 * Add Impossible Accent focus border
 *
 * Shows a subtle border when the element is focused (keyboard navigation).
 * Only visible in Unhinged mode.
 *
 * @param interactionSource The interaction source for focus state
 * @param borderWidth Width of the focus border (default 2.dp)
 * @param cornerRadius Corner radius of the border (default 8.dp)
 */
@Composable
fun Modifier.impossibleFocusBorder(
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    borderWidth: Dp = 2.dp,
    cornerRadius: Dp = 8.dp
): Modifier {
    val settings = LocalUnhingedSettings.current
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isUnhinged = settings.isUnhinged

    return this
        .focusable(interactionSource = interactionSource)
        .then(
            if (isUnhinged && isFocused) {
                Modifier.border(
                    border = BorderStroke(borderWidth, ImpossibleAccentBright),
                    shape = RoundedCornerShape(cornerRadius)
                )
            } else {
                Modifier
            }
        )
}

/**
 * Add Impossible Accent selection glow
 *
 * Shows a subtle glow around selected items.
 * Only visible in Unhinged mode.
 *
 * @param isSelected Whether the item is selected
 * @param glowRadius Radius of the glow effect (default 8.dp)
 */
@Composable
fun Modifier.impossibleSelectionGlow(
    isSelected: Boolean,
    glowRadius: Dp = 8.dp
): Modifier {
    val settings = LocalUnhingedSettings.current
    val isUnhinged = settings.isUnhinged

    return this.drawBehind {
        if (isUnhinged && isSelected) {
            val glowColor = ImpossibleAccent.copy(alpha = 0.15f)

            // Outer glow
            drawRoundRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glowColor,
                        Color.Transparent
                    ),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.maxDimension * 0.6f
                ),
                cornerRadius = CornerRadius(glowRadius.toPx())
            )

            // Inner border
            drawRoundRect(
                color = ImpossibleAccent.copy(alpha = 0.3f),
                cornerRadius = CornerRadius(glowRadius.toPx()),
                style = Stroke(width = 1.5f)
            )
        }
    }
}

/**
 * Add Impossible Accent active indicator
 *
 * Shows a subtle left-edge accent line for active/current items.
 * Only visible in Unhinged mode.
 *
 * @param isActive Whether the item is active/current
 * @param lineWidth Width of the accent line (default 3.dp)
 */
@Composable
fun Modifier.impossibleActiveIndicator(
    isActive: Boolean,
    lineWidth: Dp = 3.dp
): Modifier {
    val settings = LocalUnhingedSettings.current
    val isUnhinged = settings.isUnhinged

    return this.drawBehind {
        if (isUnhinged && isActive) {
            // Vertical accent line on left edge
            drawLine(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        ImpossibleAccentBright,
                        ImpossibleAccent,
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = size.height
                ),
                start = Offset(0f, 0f),
                end = Offset(0f, size.height),
                strokeWidth = lineWidth.toPx()
            )
        }
    }
}

/**
 * Add Impossible Accent ripple highlight
 *
 * Shows a subtle background tint for pressed/hovering states.
 * Only visible in Unhinged mode.
 *
 * @param isPressed Whether the item is currently pressed
 * @param cornerRadius Corner radius (default 8.dp)
 */
@Composable
fun Modifier.impossibleRippleHighlight(
    isPressed: Boolean,
    cornerRadius: Dp = 8.dp
): Modifier {
    val settings = LocalUnhingedSettings.current
    val isUnhinged = settings.isUnhinged

    return this.drawBehind {
        if (isUnhinged && isPressed) {
            drawRoundRect(
                color = ImpossibleAccent.copy(alpha = 0.08f),
                cornerRadius = CornerRadius(cornerRadius.toPx())
            )
        }
    }
}
