package com.ninelivesaudio.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cosmic progress ring with premium aesthetic.
 *
 * Features:
 * - Circular progress indicator starting at top (12 o'clock)
 * - Subtle glow effect for depth
 * - Inner shadow simulation for recessed appearance
 * - Optional end cap dot for visual polish
 * - Smooth rounded caps
 *
 * Usage:
 * ```
 * CosmicProgressRing(
 *     progress = 0.65f,
 *     modifier = Modifier.size(48.dp),
 *     progressColor = SigilGold
 * )
 * ```
 */
@Composable
fun CosmicProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 4.dp,
    trackColor: Color = Color(0xFF263248),
    progressColor: Color = Color(0xFFC9A24A),
    glowStrength: Float = 0.55f,
    innerShadowStrength: Float = 0.6f,
    showEndCapDot: Boolean = true,
) {
    // Clamp progress to valid range
    val clampedProgress = progress.coerceIn(0f, 1f)

    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val strokePx = strokeWidth.toPx()
        val arcStroke = Stroke(width = strokePx, cap = StrokeCap.Round)

        // Calculate arc bounds
        val arcSize = Size(diameter - strokePx, diameter - strokePx)
        val arcTopLeft = Offset(
            (size.width - diameter) / 2f + strokePx / 2f,
            (size.height - diameter) / 2f + strokePx / 2f
        )

        // ─── Track (full 360° background arc) ─────────────────────────
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = arcStroke,
            topLeft = arcTopLeft,
            size = arcSize,
        )

        if (clampedProgress > 0f) {
            val sweepAngle = 360f * clampedProgress

            // ─── Inner Shadow Effect (recessed depth) ─────────────────
            if (innerShadowStrength > 0f) {
                val shadowInset = strokePx * 0.15f
                val shadowSize = Size(
                    diameter - strokePx - shadowInset * 2,
                    diameter - strokePx - shadowInset * 2
                )
                val shadowTopLeft = Offset(
                    arcTopLeft.x + shadowInset,
                    arcTopLeft.y + shadowInset
                )

                drawArc(
                    color = Color.Black.copy(alpha = 0.15f * innerShadowStrength),
                    startAngle = -90f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    style = Stroke(width = strokePx * 0.6f, cap = StrokeCap.Round),
                    topLeft = shadowTopLeft,
                    size = shadowSize,
                )
            }

            // ─── Glow Effect Layers ───────────────────────────────────
            if (glowStrength > 0f) {
                val glowLayers = listOf(
                    Triple(strokePx * 2.5f, 0.10f, glowStrength),
                    Triple(strokePx * 2.0f, 0.15f, glowStrength),
                    Triple(strokePx * 1.5f, 0.20f, glowStrength),
                )

                glowLayers.forEach { (glowStroke, baseAlpha, strength) ->
                    val alpha = (baseAlpha * strength).coerceAtMost(0.25f)
                    drawArc(
                        color = progressColor.copy(alpha = alpha),
                        startAngle = -90f,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        style = Stroke(width = glowStroke, cap = StrokeCap.Round),
                        topLeft = arcTopLeft,
                        size = arcSize,
                    )
                }
            }

            // ─── Progress Arc (main ring) ─────────────────────────────
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        progressColor.copy(alpha = 0.5f),
                        progressColor,
                        progressColor.copy(alpha = 0.85f),
                    ),
                ),
                startAngle = -90f,
                sweepAngle = sweepAngle,
                useCenter = false,
                style = arcStroke,
                topLeft = arcTopLeft,
                size = arcSize,
            )

            // ─── End Cap Dot ──────────────────────────────────────────
            if (showEndCapDot && clampedProgress < 1f) {
                val radius = (diameter - strokePx) / 2f
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val angleRad = Math.toRadians((-90f + sweepAngle).toDouble())

                // End cap position
                val dotX = centerX + radius * cos(angleRad).toFloat()
                val dotY = centerY + radius * sin(angleRad).toFloat()
                val dotCenter = Offset(dotX, dotY)
                val dotRadius = strokePx * 0.8f

                // Glow under dot
                drawCircle(
                    color = progressColor.copy(alpha = 0.15f),
                    radius = dotRadius * 2f,
                    center = dotCenter,
                )

                // Dot
                drawCircle(
                    color = progressColor,
                    radius = dotRadius,
                    center = dotCenter,
                )
            }
        }
    }
}
