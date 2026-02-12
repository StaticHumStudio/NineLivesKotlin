package com.ninelivesaudio.app.ui.animation.unhinged.anomalies

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Crack Whisper Effect
 *
 * Hairline fractures rendered near the edges of the screen.
 * Static lines, possibly with very slow fade in/out.
 * Like cracks forming in reality itself.
 *
 * **Animated**: Cracks slowly appear and disappear
 * **Static**: Faint hairline fractures
 */
class CrackWhisperEffect(
    private val seed: Long = System.currentTimeMillis()
) : IAnomalyEffect {

    override val id: String = "crack_whisper"
    override val durationMs: Long = 10000L // 10 seconds
    override val supportsReducedMotion: Boolean = true
    override val maxOpacity: Float = 0.04f

    private val random = Random(seed)
    private val crackCount = random.nextInt(2, 5) // 2-4 cracks
    private val cracks = List(crackCount) {
        generateCrack(random)
    }

    override fun drawAnimated(
        drawScope: DrawScope,
        progress: Float,
        canvasWidth: Float,
        canvasHeight: Float
    ) {
        // Very slow fade in and out
        val alpha = when {
            progress < 0.25f -> progress / 0.25f // Slow fade in (25%)
            progress > 0.75f -> (1f - progress) / 0.25f // Slow fade out (25%)
            else -> 1f // Hold (50%)
        }

        cracks.forEach { crack ->
            drawCrack(drawScope, crack, canvasWidth, canvasHeight, alpha)
        }
    }

    override fun drawStatic(
        drawScope: DrawScope,
        canvasWidth: Float,
        canvasHeight: Float
    ) {
        // Static version at 40% alpha for subtlety
        drawAnimated(drawScope, 0.5f, canvasWidth, canvasHeight)
    }

    private fun drawCrack(
        drawScope: DrawScope,
        crack: Crack,
        canvasWidth: Float,
        canvasHeight: Float,
        alphaMult: Float
    ) {
        val segments = crack.segments

        for (i in 0 until segments.size - 1) {
            val start = segments[i]
            val end = segments[i + 1]

            drawScope.drawLine(
                color = Color.White,
                start = Offset(
                    start.x * canvasWidth,
                    start.y * canvasHeight
                ),
                end = Offset(
                    end.x * canvasWidth,
                    end.y * canvasHeight
                ),
                strokeWidth = crack.width,
                alpha = crack.opacity * alphaMult
            )
        }
    }

    private fun generateCrack(random: Random): Crack {
        val edge = Edge.values()[random.nextInt(Edge.values().size)]
        val segments = mutableListOf<Point>()

        // Start point on edge
        val startPoint = when (edge) {
            Edge.TOP -> Point(random.nextFloat(), 0f)
            Edge.BOTTOM -> Point(random.nextFloat(), 1f)
            Edge.LEFT -> Point(0f, random.nextFloat())
            Edge.RIGHT -> Point(1f, random.nextFloat())
        }
        segments.add(startPoint)

        // Generate 3-6 segments moving inward
        val segmentCount = random.nextInt(3, 7)
        var currentPoint = startPoint

        for (i in 0 until segmentCount) {
            // Move slightly inward and in a random direction
            val angle = random.nextFloat() * Math.PI.toFloat() * 2
            val distance = random.nextFloat() * 0.05f + 0.02f // 2-7% of screen

            val nextPoint = Point(
                x = (currentPoint.x + cos(angle) * distance).coerceIn(0f, 1f),
                y = (currentPoint.y + sin(angle) * distance).coerceIn(0f, 1f)
            )

            segments.add(nextPoint)
            currentPoint = nextPoint
        }

        return Crack(
            segments = segments,
            width = random.nextFloat() * 0.5f + 0.3f, // 0.3 to 0.8 pixels (very thin)
            opacity = random.nextFloat() * 0.02f + 0.02f // 2-4%
        )
    }

    private data class Crack(
        val segments: List<Point>,
        val width: Float,
        val opacity: Float
    )

    private data class Point(
        val x: Float, // 0 to 1 (percentage)
        val y: Float  // 0 to 1 (percentage)
    )

    private enum class Edge {
        TOP, BOTTOM, LEFT, RIGHT
    }
}
