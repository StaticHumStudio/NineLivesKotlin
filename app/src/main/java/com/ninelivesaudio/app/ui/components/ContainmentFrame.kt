package com.ninelivesaudio.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ninelivesaudio.app.ui.theme.unhinged.ArchiveOutline
import com.ninelivesaudio.app.ui.theme.unhinged.GoldFilament

/**
 * A dual-stroke containment frame drawn via Canvas.
 *
 * Outer stroke: faint outline (ArchiveOutline at 18% alpha, 2dp)
 * Inner stroke: gold filament (GoldFilament at 55% alpha, 1dp)
 *
 * Drawn as an overlay — place with Modifier.matchParentSize() inside a Box.
 */
@Composable
fun ContainmentFrame(
    modifier: Modifier = Modifier,
    inset: Dp = 5.dp,
    cornerRadius: Dp = 14.dp,
) {
    val density = LocalDensity.current
    val outerStrokePx = with(density) { 2.dp.toPx() }
    val innerStrokePx = with(density) { 1.dp.toPx() }
    val insetPx = with(density) { inset.toPx() }
    val cornerPx = with(density) { cornerRadius.toPx() }

    val outerColor = ArchiveOutline.copy(alpha = 0.18f)
    val innerColor = GoldFilament.copy(alpha = 0.55f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Outer stroke
        drawRoundRect(
            color = outerColor,
            topLeft = Offset(insetPx, insetPx),
            size = Size(w - insetPx * 2, h - insetPx * 2),
            cornerRadius = CornerRadius(cornerPx, cornerPx),
            style = Stroke(width = outerStrokePx),
        )

        // Inner stroke (offset inward by outer stroke width)
        val innerInset = insetPx + outerStrokePx + 1f
        drawRoundRect(
            color = innerColor,
            topLeft = Offset(innerInset, innerInset),
            size = Size(w - innerInset * 2, h - innerInset * 2),
            cornerRadius = CornerRadius(cornerPx - 2f, cornerPx - 2f),
            style = Stroke(width = innerStrokePx),
        )
    }
}
