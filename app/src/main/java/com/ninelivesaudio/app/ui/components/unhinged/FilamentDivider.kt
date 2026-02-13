package com.ninelivesaudio.app.ui.components.unhinged

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Filament Divider - A divider that adapts based on Unhinged Mode
 *
 * **Normal Mode:**
 * - Standard Material3 HorizontalDivider
 * - Subtle gray line
 *
 * **Unhinged Mode:**
 * - Gold line with subtle glow effect
 * - Gradient fade at edges
 * - Suggests "golden thread" or "archive filament"
 *
 * Design Philosophy:
 * - Glow is VERY subtle (barely brighter than the line itself)
 * - Should feel like preserved metallic thread, not neon
 * - Maintains clean separation while adding atmosphere
 *
 * @param modifier Standard modifier
 * @param thickness Line thickness (defaults to 1.dp)
 * @param color Optional color override (uses theme outline if not specified)
 */
@Composable
fun FilamentDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
    color: Color? = null
) {
    FilamentGlowDivider(
        modifier = modifier,
        thickness = thickness
    )
}

/**
 * Gold filament divider with subtle glow effect
 */
@Composable
private fun FilamentGlowDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp
) {
    // Archive gold color
    val goldColor = Color(0xFFE8C468)
    val goldGlow = goldColor.copy(alpha = 0.15f) // Very subtle glow

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness * 3) // Extra height for glow
    ) {
        val centerY = size.height / 2f

        // Glow layer (underneath)
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    goldGlow,
                    goldGlow,
                    Color.Transparent
                ),
                startX = 0f,
                endX = size.width
            ),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = thickness.toPx() * 2.5f,
            cap = StrokeCap.Round
        )

        // Main line (on top)
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    goldColor.copy(alpha = 0.5f),
                    goldColor,
                    goldColor,
                    goldColor.copy(alpha = 0.5f),
                    Color.Transparent
                ),
                startX = 0f,
                endX = size.width
            ),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = thickness.toPx(),
            cap = StrokeCap.Round
        )
    }
}
