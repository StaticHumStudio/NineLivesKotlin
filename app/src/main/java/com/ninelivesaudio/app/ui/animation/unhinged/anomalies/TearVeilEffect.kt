package com.ninelivesaudio.app.ui.animation.unhinged.anomalies

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.random.Random

/**
 * Tear Veil Effect
 *
 * Faint vertical streaks rendered at 2-6% opacity.
 * Just barely visible - like scratches on old film or tears in reality.
 *
 * **Animated**: Streaks slowly fade in and out
 * **Static**: Faint vertical lines at 3% opacity
 */
class TearVeilEffect(
    private val seed: Long = System.currentTimeMillis()
) : IAnomalyEffect {

    override val id: String = "tear_veil"
    override val durationMs: Long = 8000L // 8 seconds
    override val supportsReducedMotion: Boolean = true
    override val maxOpacity: Float = 0.06f

    private val random = Random(seed)
    private val tearCount = random.nextInt(3, 7) // 3-6 tears
    private val tears = List(tearCount) {
        Tear(
            xPosition = random.nextFloat(), // 0 to 1 (percentage across screen)
            width = random.nextFloat() * 2f + 0.5f, // 0.5 to 2.5 pixels
            opacity = random.nextFloat() * 0.04f + 0.02f, // 2-6%
            yStart = random.nextFloat() * 0.3f, // Start somewhere in top 30%
            yEnd = random.nextFloat() * 0.3f + 0.7f // End somewhere in bottom 30%
        )
    }

    override fun drawAnimated(
        drawScope: DrawScope,
        progress: Float,
        canvasWidth: Float,
        canvasHeight: Float
    ) {
        // Fade in first 20%, hold 60%, fade out last 20%
        val alpha = when {
            progress < 0.2f -> progress / 0.2f // Fade in
            progress > 0.8f -> (1f - progress) / 0.2f // Fade out
            else -> 1f // Hold
        }

        tears.forEach { tear ->
            val x = canvasWidth * tear.xPosition
            val y1 = canvasHeight * tear.yStart
            val y2 = canvasHeight * tear.yEnd

            drawScope.drawLine(
                color = Color.White,
                start = Offset(x, y1),
                end = Offset(x, y2),
                strokeWidth = tear.width,
                alpha = tear.opacity * alpha
            )
        }
    }

    override fun drawStatic(
        drawScope: DrawScope,
        canvasWidth: Float,
        canvasHeight: Float
    ) {
        // Static version at 50% alpha
        drawAnimated(drawScope, 0.5f, canvasWidth, canvasHeight)
    }

    private data class Tear(
        val xPosition: Float,
        val width: Float,
        val opacity: Float,
        val yStart: Float,
        val yEnd: Float
    )
}
