package com.ninelivesaudio.app.ui.theme.unhinged.surfaces

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ninelivesaudio.app.ui.theme.unhinged.GoldFilament
import com.ninelivesaudio.app.ui.theme.unhinged.GoldFilamentGlow
import com.ninelivesaudio.app.ui.theme.unhinged.StoneAsh

/**
 * Filament Divider — Surface Language Composable
 *
 * A horizontal divider component.
 *
 * **Normal mode**: Standard Material 3 divider line
 * **Unhinged mode**: Hairline gold or ash-colored line with optional faint glow effect
 * (achieved with a blurred duplicate behind it)
 *
 * Design rules:
 * - Dividers are subtle, never attention-grabbing
 * - Glow is extremely subtle (barely perceptible)
 * - Same height and spacing in both modes
 *
 * @param modifier Modifier for this divider
 * @param thickness Line thickness (default 1.dp)
 * @param color Override color (null = use theme default)
 * @param withGlow Whether to show glow effect in unhinged mode (default false)
 */
@Composable
fun FilamentDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
    color: Color? = null,
    withGlow: Boolean = false
) {
    val lineColor = color ?: StoneAsh
    val glowColor = GoldFilamentGlow.copy(alpha = 0.15f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(if (withGlow) thickness + 2.dp else thickness)
    ) {
        val y = size.height / 2f

        if (withGlow) {
            drawLine(
                color = glowColor.copy(alpha = 0.05f),
                start = Offset(0f, y - 1.dp.toPx()),
                end = Offset(size.width, y - 1.dp.toPx()),
                strokeWidth = 3.dp.toPx()
            )
            drawLine(
                color = glowColor.copy(alpha = 0.1f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.5.dp.toPx()
            )
        }

        drawLine(
            color = lineColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = thickness.toPx()
        )
    }
}

/**
 * Gold Filament Divider variant
 * Uses gold accent color - useful for section breaks in important contexts
 */
@Composable
fun GoldFilamentDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
    withGlow: Boolean = true
) {
    FilamentDivider(
        modifier = modifier,
        thickness = thickness,
        color = GoldFilament.copy(alpha = 0.3f),
        withGlow = withGlow
    )
}

/**
 * Ash Divider variant (default)
 * Subtle, low-contrast divider for most use cases
 */
@Composable
fun AshDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp
) {
    FilamentDivider(
        modifier = modifier,
        thickness = thickness,
        color = StoneAsh.copy(alpha = 0.4f),
        withGlow = false
    )
}
