package com.ninelivesaudio.app.ui.animation.unhinged.sigil

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ninelivesaudio.app.ui.animation.unhinged.motion.MotionTokens
import com.ninelivesaudio.app.ui.components.unhinged.LocalUnhingedSettings
import com.ninelivesaudio.app.ui.theme.unhinged.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sigil Progress — Circular Progress Indicator with Subtle Life
 *
 * Atmospheric animated circular progress:
 * - Very slow rotation drift (0.5-1 deg per second, barely noticeable)
 * - "Filament shimmer" — thin highlight crawling along the progress arc
 * - Gentle breathing opacity during active playback
 * - **Reduce motion**: Static version (no rotation, no shimmer)
 *
 * @param progress Current progress (0f to 1f)
 * @param modifier Modifier for this indicator
 * @param size Size of the circular indicator
 * @param strokeWidth Width of the progress arc
 * @param isActive Whether playback/action is currently active (enables breathing)
 */
@Composable
fun SigilProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    strokeWidth: Dp = 4.dp,
    isActive: Boolean = false
) {
    val reduceMotion = LocalUnhingedSettings.current.reduceMotionRequested

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val progressColor = GoldFilament
    val shimmerColor = GoldFilamentBright

    val infiniteTransition = rememberInfiniteTransition(label = "sigil_infinite")

    val rotationDrift by if (!reduceMotion) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 720_000,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotation_drift"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    val shimmerPosition by if (!reduceMotion && progress > 0f) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = MotionTokens.DurationUltraSlow * 2,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmer_position"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    val breathingAlpha by if (!reduceMotion && isActive) {
        infiniteTransition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = MotionTokens.DurationUltraSlow,
                    easing = MotionTokens.EasingStandard
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathing_alpha"
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    Canvas(
        modifier = modifier
            .progressSemantics(progress)
            .size(size)
    ) {
        val diameter = size.toPx()
        val stroke = strokeWidth.toPx()
        val radius = (diameter - stroke) / 2
        val centerOffset = Offset(diameter / 2, diameter / 2)

        rotate(rotationDrift, centerOffset) {
            drawCircle(
                color = trackColor,
                radius = radius,
                center = centerOffset,
                style = Stroke(width = stroke)
            )

            if (progress > 0f) {
                val sweepAngle = 360f * progress.coerceIn(0f, 1f)

                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(diameter - stroke, diameter - stroke),
                    style = Stroke(
                        width = stroke,
                        cap = StrokeCap.Round
                    ),
                    alpha = breathingAlpha
                )

                if (!reduceMotion) {
                    val shimmerAngle = -90f + (sweepAngle * shimmerPosition)
                    val shimmerRad = shimmerAngle.toDouble() * kotlin.math.PI / 180.0
                    val shimmerX = centerOffset.x + radius * cos(shimmerRad).toFloat()
                    val shimmerY = centerOffset.y + radius * sin(shimmerRad).toFloat()

                    drawCircle(
                        color = shimmerColor,
                        radius = stroke * 0.8f,
                        center = Offset(shimmerX, shimmerY),
                        alpha = 0.6f
                    )

                    drawCircle(
                        color = shimmerColor,
                        radius = stroke * 1.4f,
                        center = Offset(shimmerX, shimmerY),
                        alpha = 0.2f
                    )
                }
            }
        }
    }
}

/**
 * Sigil Progress Bar — Linear Progress with Filament Shimmer
 *
 * Linear version of SigilProgress for horizontal progress bars.
 * Shows gentle shimmer crawling along the progress bar.
 *
 * @param progress Current progress (0f to 1f)
 * @param modifier Modifier for this indicator
 * @param height Height of the progress bar
 * @param isActive Whether playback/action is currently active
 */
@Composable
fun SigilProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    isActive: Boolean = false
) {
    val reduceMotion = LocalUnhingedSettings.current.reduceMotionRequested

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val progressColor = GoldFilament
    val shimmerColor = GoldFilamentBright
    val accentColor = ImpossibleAccent.copy(alpha = 0.3f)

    val infiniteTransition = rememberInfiniteTransition(label = "bar_shimmer")
    val shimmerPosition by if (!reduceMotion && progress > 0f) {
        infiniteTransition.animateFloat(
            initialValue = -0.2f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = MotionTokens.DurationVerySlow * 2,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmer_pos"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    val breathingAlpha by if (!reduceMotion && isActive) {
        infiniteTransition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = MotionTokens.DurationUltraSlow,
                    easing = MotionTokens.EasingStandard
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_breathing"
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    Canvas(
        modifier = modifier
            .progressSemantics(progress)
    ) {
        val barHeight = height.toPx()
        val barWidth = size.width

        drawRect(
            color = trackColor,
            topLeft = Offset.Zero,
            size = Size(barWidth, barHeight)
        )

        if (progress > 0f) {
            val progressWidth = barWidth * progress.coerceIn(0f, 1f)

            drawRect(
                color = progressColor,
                topLeft = Offset.Zero,
                size = Size(progressWidth, barHeight),
                alpha = breathingAlpha
            )

            drawRect(
                color = accentColor,
                topLeft = Offset(0f, barHeight - 1.dp.toPx()),
                size = Size(progressWidth, 1.dp.toPx())
            )

            if (!reduceMotion && shimmerPosition in 0f..1f) {
                val shimmerX = progressWidth * shimmerPosition
                val shimmerWidth = 30.dp.toPx()

                val shimmerBrush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        shimmerColor.copy(alpha = 0.4f),
                        shimmerColor.copy(alpha = 0.6f),
                        shimmerColor.copy(alpha = 0.4f),
                        Color.Transparent
                    ),
                    startX = shimmerX - shimmerWidth / 2,
                    endX = shimmerX + shimmerWidth / 2
                )

                // Clip to progressWidth so shimmer doesn't bleed into the
                // unfilled track area. Previous code used barWidth here which
                // caused the gradient tail to render past the progress fill.
                drawRect(
                    brush = shimmerBrush,
                    topLeft = Offset.Zero,
                    size = Size(progressWidth, barHeight)
                )
            }
        }
    }
}

/**
 * Mini Player Progress Filament
 *
 * Specialized progress bar for mini-player / bottom bar.
 * Very thin filament with breathing animation during playback.
 *
 * @param progress Current progress (0f to 1f)
 * @param modifier Modifier for this indicator
 * @param isPlaying Whether audio is currently playing
 */
@Composable
fun MiniPlayerProgressFilament(
    progress: Float,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false
) {
    SigilProgressBar(
        progress = progress,
        modifier = modifier,
        height = 2.dp,
        isActive = isPlaying
    )
}
