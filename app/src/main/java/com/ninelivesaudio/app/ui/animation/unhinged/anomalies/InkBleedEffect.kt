package com.ninelivesaudio.app.ui.animation.unhinged.anomalies

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.random.Random

/**
 * Ink Bleed Vignette Effect
 *
 * Dark gradients creeping in from one or two corners.
 * Ultra subtle, like aged paper or ink slowly bleeding.
 *
 * **Animated**: Darkness slowly seeps in and recedes
 * **Static**: Faint vignette from corners
 */
class InkBleedEffect(
    private val seed: Long = System.currentTimeMillis()
) : IAnomalyEffect {

    override val id: String = "ink_bleed"
    override val durationMs: Long = 12000L // 12 seconds (very slow)
    override val supportsReducedMotion: Boolean = true
    override val maxOpacity: Float = 0.08f

    private val random = Random(seed)
    private val corners = listOf(
        Corner.TOP_LEFT,
        Corner.TOP_RIGHT,
        Corner.BOTTOM_LEFT,
        Corner.BOTTOM_RIGHT
    ).shuffled(random).take(random.nextInt(1, 3)) // 1-2 corners

    override fun drawAnimated(
        drawScope: DrawScope,
        progress: Float,
        canvasWidth: Float,
        canvasHeight: Float
    ) {
        // Very slow fade in and out
        val alpha = when {
            progress < 0.3f -> progress / 0.3f // Slow fade in (30%)
            progress > 0.7f -> (1f - progress) / 0.3f // Slow fade out (30%)
            else -> 1f // Hold (40%)
        }

        // Bleed distance grows with progress
        val bleedDistance = 0.4f + (progress * 0.2f) // 40% to 60% of screen

        corners.forEach { corner ->
            drawCornerBleed(
                drawScope,
                corner,
                canvasWidth,
                canvasHeight,
                bleedDistance,
                alpha
            )
        }
    }

    override fun drawStatic(
        drawScope: DrawScope,
        canvasWidth: Float,
        canvasHeight: Float
    ) {
        // Static version at mid-progress
        drawAnimated(drawScope, 0.5f, canvasWidth, canvasHeight)
    }

    private fun drawCornerBleed(
        drawScope: DrawScope,
        corner: Corner,
        canvasWidth: Float,
        canvasHeight: Float,
        bleedDistance: Float,
        alpha: Float
    ) {
        val bleedWidth = canvasWidth * bleedDistance
        val bleedHeight = canvasHeight * bleedDistance

        val (topLeft, size, brush) = when (corner) {
            Corner.TOP_LEFT -> Triple(
                Offset.Zero,
                Size(bleedWidth, bleedHeight),
                Brush.radialGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = maxOpacity * alpha),
                        Color.Black.copy(alpha = maxOpacity * 0.5f * alpha),
                        Color.Transparent
                    ),
                    center = Offset.Zero,
                    radius = bleedWidth
                )
            )
            Corner.TOP_RIGHT -> Triple(
                Offset(canvasWidth - bleedWidth, 0f),
                Size(bleedWidth, bleedHeight),
                Brush.radialGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = maxOpacity * alpha),
                        Color.Black.copy(alpha = maxOpacity * 0.5f * alpha),
                        Color.Transparent
                    ),
                    center = Offset(canvasWidth, 0f),
                    radius = bleedWidth
                )
            )
            Corner.BOTTOM_LEFT -> Triple(
                Offset(0f, canvasHeight - bleedHeight),
                Size(bleedWidth, bleedHeight),
                Brush.radialGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = maxOpacity * alpha),
                        Color.Black.copy(alpha = maxOpacity * 0.5f * alpha),
                        Color.Transparent
                    ),
                    center = Offset(0f, canvasHeight),
                    radius = bleedWidth
                )
            )
            Corner.BOTTOM_RIGHT -> Triple(
                Offset(canvasWidth - bleedWidth, canvasHeight - bleedHeight),
                Size(bleedWidth, bleedHeight),
                Brush.radialGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = maxOpacity * alpha),
                        Color.Black.copy(alpha = maxOpacity * 0.5f * alpha),
                        Color.Transparent
                    ),
                    center = Offset(canvasWidth, canvasHeight),
                    radius = bleedWidth
                )
            )
        }

        drawScope.drawRect(
            brush = brush,
            topLeft = topLeft,
            size = size
        )
    }

    private enum class Corner {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }
}
