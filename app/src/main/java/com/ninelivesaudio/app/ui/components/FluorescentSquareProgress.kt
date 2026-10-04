package com.ninelivesaudio.app.ui.components

import android.graphics.BlurMaskFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PathMeasure
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ninelivesaudio.app.ui.components.unhinged.LocalUnhingedSettings
import com.ninelivesaudio.app.ui.theme.NineLivesTheme
import kotlin.math.roundToInt

/** How the glow is drawn right now. */
internal enum class GlowMode {
    /** Nothing to light, so nothing is drawn and no animation runs. */
    Off,

    /** Lit and held at a middle brightness, for people who asked for less motion. */
    Still,

    /** Lit and breathing slowly. */
    Breathing,
}

/**
 * At zero progress there is no arc to light, and a list of unstarted books
 * would otherwise run an animation per row for nothing. With reduced motion
 * the arc is drawn once and left alone.
 */
internal fun glowMode(progress: Float, reduceMotion: Boolean): GlowMode = when {
    !(progress > 0f) -> GlowMode.Off
    reduceMotion -> GlowMode.Still
    else -> GlowMode.Breathing
}

/** Breath position (0 dim, 1 bright) the glow holds at under reduced motion. */
private const val STILL_BREATH = 0.5f

private const val BREATH_ALPHA_DIM = 0.55f
private const val BREATH_ALPHA_BRIGHT = 1.00f
private const val BREATH_BLOOM_DIM_DP = 20f
private const val BREATH_BLOOM_BRIGHT_DP = 38f

/**
 * Fluorescent rounded-square progress indicator with a multi-layer glow effect
 * and a slow breathing animation.
 *
 * Draws a stroked rounded-rectangle path clipped to [progress] (0.0–1.0) with
 * four stacked layers — outer bleed, mid corona, core tube, and hot filament —
 * all modulated by a slow breathing animation. At zero progress nothing is
 * drawn and nothing animates. With reduced motion (the system animation scale
 * at zero, or the in-app toggle) the glow is drawn once and held still.
 *
 * The progress arc starts and ends at the top-center of the rounded square.
 *
 * @param progress 0.0–1.0 listening progress
 * @param cornerRadius rounded-rect corner radius — match the surrounding clip shape
 * @param padding clearance between widget edge and the glow path
 * @param strokeScale multiplier for all stroke widths (use < 1.0 for small thumbnails)
 */
@Composable
fun FluorescentSquareProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = NineLivesTheme.colors.goldFilament,
    cornerRadius: Dp = 20.dp,
    padding: Dp = 8.dp,
    strokeScale: Float = 1.0f,
) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    val reduceMotion = LocalUnhingedSettings.current.reduceMotionRequested
    val mode = glowMode(clampedProgress, reduceMotion)
    if (mode == GlowMode.Off) return

    val glowArgb = color.toArgb()

    // ── Breathing animation ──────────────────────────────────────────────
    // One 0..1 position drives both the brightness and the bloom, since the
    // two always moved on the same clock. Under reduced motion there is no
    // transition at all, only a held value.
    val breath: State<Float> = if (mode == GlowMode.Breathing) {
        rememberInfiniteTransition(label = "fluorescent_breath").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3000, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breath",
        )
    } else {
        remember { mutableFloatStateOf(STILL_BREATH) }
    }

    // The paints, paths and path effects live here and are rebuilt only when
    // the size, colour or progress change. A frame of the breathing animation
    // only changes alphas (and, a pixel at a time, the outer blur).
    val cache = remember { GlowDrawCache() }

    Canvas(modifier = modifier) {
        val ready = cache.prepare(
            width = size.width,
            height = size.height,
            paddingPx = padding.toPx(),
            cornerRadiusPx = cornerRadius.toPx(),
            strokeScale = strokeScale,
            density = density,
            argb = glowArgb,
            progress = clampedProgress,
        )
        if (!ready) return@Canvas

        drawIntoCanvas { canvas -> cache.draw(canvas.nativeCanvas, breath.value) }
    }
}

/**
 * Everything [FluorescentSquareProgress] draws with, kept between frames.
 * [prepare] rebuilds only what the inputs changed, and [draw] touches nothing
 * but the per-frame alphas, so a frame allocates next to nothing.
 */
private class GlowDrawCache {
    private val roundRect = Path()
    private val bleedRect = Path()
    private val nativeRoundRect = roundRect.asAndroidPath()
    private val nativeBleedRect = bleedRect.asAndroidPath()
    private val measure = PathMeasure()

    private val bleedPaint = strokePaint()
    private val coronaPaint = strokePaint()
    private val corePaint = strokePaint()
    private val filamentPaint = strokePaint()

    private var lastWidth = Float.NaN
    private var lastHeight = Float.NaN
    private var lastPaddingPx = Float.NaN
    private var lastCornerRadiusPx = Float.NaN
    private var lastStrokeScale = Float.NaN
    private var lastDensity = Float.NaN
    private var lastArgb = 0
    private var lastProgress = Float.NaN
    private var usable = false

    private var scatterPx = 0f
    private var bloomScale = 0f
    private var lastBloomPx = -1f

    /** Rebuilds what changed. False when there is nothing to draw at this size. */
    fun prepare(
        width: Float,
        height: Float,
        paddingPx: Float,
        cornerRadiusPx: Float,
        strokeScale: Float,
        density: Float,
        argb: Int,
        progress: Float,
    ): Boolean {
        if (width == lastWidth && height == lastHeight && paddingPx == lastPaddingPx &&
            cornerRadiusPx == lastCornerRadiusPx && strokeScale == lastStrokeScale &&
            density == lastDensity && argb == lastArgb && progress == lastProgress
        ) {
            return usable
        }
        lastWidth = width
        lastHeight = height
        lastPaddingPx = paddingPx
        lastCornerRadiusPx = cornerRadiusPx
        lastStrokeScale = strokeScale
        lastDensity = density
        lastArgb = argb
        lastProgress = progress
        usable = rebuild(width, height, paddingPx, cornerRadiusPx, strokeScale, density, argb, progress)
        return usable
    }

    private fun rebuild(
        width: Float,
        height: Float,
        paddingPx: Float,
        cornerRadiusPx: Float,
        strokeScale: Float,
        density: Float,
        argb: Int,
        progress: Float,
    ): Boolean {
        val left = paddingPx
        val top = paddingPx
        val right = width - paddingPx
        val bottom = height - paddingPx
        val rectWidth = right - left
        val rectHeight = bottom - top
        if (rectWidth <= 0f || rectHeight <= 0f) return false

        // ── The rounded-rect path ────────────────────────────────────────
        roundRect.reset()
        roundRect.addRoundRect(
            RoundRect(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                radiusX = cornerRadiusPx,
                radiusY = cornerRadiusPx,
            ),
        )
        roundRect.close()

        // ── Measure path and compute top-center phase offset ─────────────
        measure.setPath(nativeRoundRect, true)
        val totalLength = measure.length
        if (totalLength <= 0f) return false

        // addRoundRect starts at top-left corner. The distance from top-left
        // corner to top-center is: cornerRadius + (rectWidth / 2 - cornerRadius)
        // = rectWidth / 2.  So the phase offset is rectWidth / 2.
        val phaseOffset = rectWidth / 2f
        val drawLength = progress * totalLength

        // ── Outset path for the outer bleed layer ────────────────────────
        val outsetPx = 8f * strokeScale * density
        bleedRect.reset()
        bleedRect.addRoundRect(
            RoundRect(
                left = left - outsetPx,
                top = top - outsetPx,
                right = right + outsetPx,
                bottom = bottom + outsetPx,
                radiusX = cornerRadiusPx + outsetPx,
                radiusY = cornerRadiusPx + outsetPx,
            ),
        )
        bleedRect.close()
        measure.setPath(nativeBleedRect, true)
        val bleedTotalLength = measure.length
        val bleedDrawLength = progress * bleedTotalLength
        val bleedPhaseOffset = (rectWidth + outsetPx * 2) / 2f

        // ── Strokes ─────────────────────────────────────────────────────
        val rgb = argb or OPAQUE

        bleedPaint.color = rgb
        bleedPaint.strokeWidth = 6f * strokeScale * density
        bleedPaint.pathEffect = dashEffect(bleedDrawLength, bleedTotalLength, bleedPhaseOffset)
        // The bloom radius follows the breath, so draw() sets the filter.
        bleedPaint.maskFilter = null
        lastBloomPx = -1f
        bloomScale = strokeScale * density
        scatterPx = 1.5f * strokeScale * density

        coronaPaint.color = rgb
        coronaPaint.strokeWidth = 4f * strokeScale * density
        coronaPaint.maskFilter = BlurMaskFilter(12f * strokeScale * density, BlurMaskFilter.Blur.NORMAL)
        coronaPaint.pathEffect = dashEffect(drawLength, totalLength, phaseOffset)

        corePaint.color = rgb
        corePaint.strokeWidth = 3f * strokeScale * density
        corePaint.maskFilter = BlurMaskFilter(4f * strokeScale * density, BlurMaskFilter.Blur.NORMAL)
        corePaint.pathEffect = dashEffect(drawLength, totalLength, phaseOffset)

        filamentPaint.color = FILAMENT_RGB
        filamentPaint.strokeWidth = 1.5f * strokeScale * density
        filamentPaint.pathEffect = dashEffect(drawLength, totalLength, phaseOffset)
        return true
    }

    /** One frame at [breath] (0 dim, 1 bright). */
    fun draw(canvas: android.graphics.Canvas, breath: Float) {
        val breathAlpha = lerp(BREATH_ALPHA_DIM, BREATH_ALPHA_BRIGHT, breath)
        val bloomPx = lerp(BREATH_BLOOM_DIM_DP, BREATH_BLOOM_BRIGHT_DP, breath) * bloomScale
        // A blur filter's radius is fixed when it is made, so this is the one
        // thing that cannot be reused every frame. Whole pixels are finer than
        // the eye can follow on a 3 second breath, and cost far fewer filters.
        val bloomWhole = bloomPx.roundToInt().coerceAtLeast(1).toFloat()
        if (bloomWhole != lastBloomPx) {
            bleedPaint.maskFilter = BlurMaskFilter(bloomWhole, BlurMaskFilter.Blur.NORMAL)
            lastBloomPx = bloomWhole
        }

        bleedPaint.alpha = alphaOf(0.22f * breathAlpha)
        coronaPaint.alpha = alphaOf(0.45f * breathAlpha)
        corePaint.alpha = alphaOf(0.90f * breathAlpha)
        filamentPaint.alpha = alphaOf(0.75f * breathAlpha)

        // ── Layer 1: Outer bleed (drawn twice with offset for scatter) ──
        canvas.drawPath(nativeBleedRect, bleedPaint)

        // Second bleed draw with slight offset for light scatter
        canvas.save()
        canvas.translate(scatterPx, -scatterPx)
        canvas.drawPath(nativeBleedRect, bleedPaint)
        canvas.restore()

        // ── Layers 2 to 4: Mid corona, core tube, hot filament ──────────
        canvas.drawPath(nativeRoundRect, coronaPaint)
        canvas.drawPath(nativeRoundRect, corePaint)
        canvas.drawPath(nativeRoundRect, filamentPaint)
    }

    private fun dashEffect(segmentLen: Float, total: Float, phase: Float): DashPathEffect {
        val seg = segmentLen.coerceAtLeast(0.01f)
        val gap = (total - seg).coerceAtLeast(0.01f)
        return DashPathEffect(floatArrayOf(seg, gap), total - phase)
    }

    private fun alphaOf(fraction: Float): Int = (fraction * 255f).roundToInt().coerceIn(0, 255)

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    private companion object {
        const val OPAQUE = 0xFF000000.toInt()

        /** Warm near-white for the hot filament. */
        const val FILAMENT_RGB = 0xFFFFF0C0.toInt()

        fun strokePaint() = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
        }
    }
}
