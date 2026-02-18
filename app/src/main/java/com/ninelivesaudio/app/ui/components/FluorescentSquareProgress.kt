package com.ninelivesaudio.app.ui.components

import android.graphics.BlurMaskFilter
import android.graphics.DashPathEffect
import android.graphics.PathMeasure
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ninelivesaudio.app.ui.theme.unhinged.GoldFilament

/**
 * Fluorescent rounded-square progress indicator with a multi-layer glow effect
 * and continuous breathing animation.
 *
 * Draws a stroked rounded-rectangle path clipped to [progress] (0.0–1.0) with
 * four stacked layers — outer bleed, mid corona, core tube, and hot filament —
 * all modulated by a slow breathing animation.
 *
 * The progress arc starts and ends at the top-center of the rounded square.
 */
@Composable
fun FluorescentSquareProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = GoldFilament,
) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    val density = LocalDensity.current
    val glowColor = color

    // ── Breathing animation (continuous, regardless of progress) ──────────
    val infiniteTransition = rememberInfiniteTransition(label = "fluorescent_breath")

    val breathAlpha by infiniteTransition.animateFloat(
        initialValue = 0.70f,
        targetValue = 1.00f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath_alpha",
    )

    val breathBloom by infiniteTransition.animateFloat(
        initialValue = 24f,
        targetValue = 32f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath_bloom",
    )

    Canvas(modifier = modifier) {
        val paddingPx = with(density) { 8.dp.toPx() }
        val cornerRadiusPx = with(density) { 20.dp.toPx() }

        val left = paddingPx
        val top = paddingPx
        val right = size.width - paddingPx
        val bottom = size.height - paddingPx
        val rectWidth = right - left
        val rectHeight = bottom - top

        if (rectWidth <= 0f || rectHeight <= 0f) return@Canvas

        // ── Build the rounded-rect path ──────────────────────────────────
        val roundRectPath = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    radiusX = cornerRadiusPx,
                    radiusY = cornerRadiusPx,
                ),
            )
            close()
        }

        // ── Measure path and compute top-center phase offset ─────────────
        val nativePath = roundRectPath.asAndroidPath()
        val pathMeasure = PathMeasure(nativePath, true)
        val totalLength = pathMeasure.length
        if (totalLength <= 0f) return@Canvas

        // addRoundRect starts at top-left corner. The distance from top-left
        // corner to top-center is: cornerRadius + (rectWidth / 2 - cornerRadius)
        // = rectWidth / 2.  So the phase offset is rectWidth / 2.
        val phaseOffset = rectWidth / 2f

        val drawLength = clampedProgress * totalLength

        // ── Outset path for the outer bleed layer ────────────────────────
        val outsetPx = with(density) { 8.dp.toPx() }
        val bleedPath = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = left - outsetPx,
                    top = top - outsetPx,
                    right = right + outsetPx,
                    bottom = bottom + outsetPx,
                    radiusX = cornerRadiusPx + outsetPx,
                    radiusY = cornerRadiusPx + outsetPx,
                ),
            )
            close()
        }
        val bleedNativePath = bleedPath.asAndroidPath()
        val bleedMeasure = PathMeasure(bleedNativePath, true)
        val bleedTotalLength = bleedMeasure.length
        val bleedDrawLength = clampedProgress * bleedTotalLength
        val bleedPhaseOffset = (rectWidth + outsetPx * 2) / 2f

        // ── Helper to create dash effect (segment + gap covering rest) ───
        fun dashEffect(segmentLen: Float, total: Float, phase: Float): DashPathEffect {
            val seg = segmentLen.coerceAtLeast(0.01f)
            val gap = (total - seg).coerceAtLeast(0.01f)
            return DashPathEffect(floatArrayOf(seg, gap), total - phase)
        }

        val bloomPx = with(density) { breathBloom.dp.toPx() }

        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas

            // ── Layer 1: Outer bleed (drawn twice with offset for scatter) ──
            val bleedPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = with(density) { 6.dp.toPx() }
                setColor(Color(
                    red = glowColor.red,
                    green = glowColor.green,
                    blue = glowColor.blue,
                    alpha = 0.15f * breathAlpha,
                ).toArgb())
                maskFilter = BlurMaskFilter(bloomPx, BlurMaskFilter.Blur.NORMAL)
                pathEffect = dashEffect(bleedDrawLength, bleedTotalLength, bleedPhaseOffset)
            }
            nativeCanvas.drawPath(bleedNativePath, bleedPaint)

            // Second bleed draw with slight offset for light scatter
            nativeCanvas.save()
            val scatterPx = with(density) { 1.5.dp.toPx() }
            nativeCanvas.translate(scatterPx, -scatterPx)
            nativeCanvas.drawPath(bleedNativePath, bleedPaint)
            nativeCanvas.restore()

            // ── Layer 2: Mid corona ─────────────────────────────────────────
            val coronaPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = with(density) { 4.dp.toPx() }
                setColor(Color(
                    red = glowColor.red,
                    green = glowColor.green,
                    blue = glowColor.blue,
                    alpha = 0.35f * breathAlpha,
                ).toArgb())
                maskFilter = BlurMaskFilter(
                    with(density) { 12.dp.toPx() },
                    BlurMaskFilter.Blur.NORMAL,
                )
                pathEffect = dashEffect(drawLength, totalLength, phaseOffset)
            }
            nativeCanvas.drawPath(nativePath, coronaPaint)

            // ── Layer 3: Core tube ──────────────────────────────────────────
            val corePaint = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = with(density) { 3.dp.toPx() }
                setColor(Color(
                    red = glowColor.red,
                    green = glowColor.green,
                    blue = glowColor.blue,
                    alpha = 0.80f * breathAlpha,
                ).toArgb())
                maskFilter = BlurMaskFilter(
                    with(density) { 4.dp.toPx() },
                    BlurMaskFilter.Blur.NORMAL,
                )
                pathEffect = dashEffect(drawLength, totalLength, phaseOffset)
            }
            nativeCanvas.drawPath(nativePath, corePaint)

            // ── Layer 4: Hot filament ───────────────────────────────────────
            val filamentColor = Color(0xFFFFF0C0) // Warm near-white
            val filamentPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = with(density) { 1.5.dp.toPx() }
                setColor(filamentColor.copy(alpha = 0.60f * breathAlpha).toArgb())
                pathEffect = dashEffect(drawLength, totalLength, phaseOffset)
            }
            nativeCanvas.drawPath(nativePath, filamentPaint)
        }
    }
}
