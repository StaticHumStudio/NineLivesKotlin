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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ninelivesaudio.app.ui.theme.unhinged.ArchiveOutline
import com.ninelivesaudio.app.ui.theme.unhinged.GoldFilament
import com.ninelivesaudio.app.ui.theme.unhinged.GoldFilamentBright
import com.ninelivesaudio.app.ui.theme.unhinged.GoldFilamentDim
import com.ninelivesaudio.app.ui.theme.unhinged.GoldFilamentGlow

/**
 * Containment Halo progress ring with premium, intentional aesthetic.
 *
 * Features:
 * - Thinner, elegant strokes with breathing room from edges
 * - Filament gradient (hot wire glowing effect)
 * - Soft glow underlay for depth
 * - Rounded caps for premium feel
 * - Optional partial track (300° arc) to avoid "loading spinner" look
 * - Per-page tuning via RingStyle presets
 *
 * Design Philosophy:
 * Transforms progress rings from "fitness tracker" energy into ceremonial
 * containment halos that enhance rather than dominate cover art.
 *
 * Usage:
 * ```
 * ContainmentProgressRing(
 *     progress = 0.65f,
 *     modifier = Modifier.size(120.dp),
 *     style = RingStyle.LibrarySmall,
 *     progressColor = GoldFilament,
 *     trackColor = ArchiveOutline
 * )
 * ```
 */
@Composable
fun ContainmentProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    style: RingStyle = RingStyle.BookDetail,
    progressColor: Color = GoldFilament,
    trackColor: Color = ArchiveOutline,
) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    val density = LocalDensity.current

    Canvas(modifier = modifier) {
        // Convert dp to px within proper density context
        val strokePx = with(density) { style.strokeDp.dp.toPx() }
        val insetPx = with(density) { style.insetDp.dp.toPx() }
        val diameter = size.minDimension - (insetPx * 2f)

        if (diameter <= strokePx * 2f) return@Canvas // More robust check

        // Arc size accounts for stroke being centered on path
        val arcSize = Size(diameter - strokePx, diameter - strokePx)
        val arcTopLeft = Offset(
            (size.width - diameter) / 2f + strokePx / 2f + insetPx,
            (size.height - diameter) / 2f + strokePx / 2f + insetPx
        )

        val arcStroke = Stroke(width = strokePx, cap = StrokeCap.Round)

        // Track arc (partial or full based on style)
        val trackStartAngle = if (style.usePartialTrack) style.trackStartAngle else -90f
        val trackSweepAngle = if (style.usePartialTrack) style.trackSweepAngle else 360f

        drawArc(
            color = trackColor.copy(alpha = style.trackAlpha),
            startAngle = trackStartAngle,
            sweepAngle = trackSweepAngle,
            useCenter = false,
            style = arcStroke,
            topLeft = arcTopLeft,
            size = arcSize,
        )

        if (clampedProgress > 0f) {
            val progressStartAngle = if (style.usePartialTrack) trackStartAngle else -90f
            val progressSweepAngle = trackSweepAngle * clampedProgress

            // Create filament gradient based on progress color
            val filamentGradient = createFilamentGradient(progressColor)

            // Glow underlay (2 layers for soft depth)
            if (style.glowAlpha > 0f) {
                // Outer glow layer (1.8x stroke width)
                drawArc(
                    color = progressColor.copy(alpha = 0.08f * style.glowAlpha),
                    startAngle = progressStartAngle,
                    sweepAngle = progressSweepAngle,
                    useCenter = false,
                    style = Stroke(width = strokePx * 1.8f, cap = StrokeCap.Round),
                    topLeft = arcTopLeft,
                    size = arcSize,
                )

                // Inner glow layer (1.3x stroke width)
                drawArc(
                    color = progressColor.copy(alpha = 0.12f * style.glowAlpha),
                    startAngle = progressStartAngle,
                    sweepAngle = progressSweepAngle,
                    useCenter = false,
                    style = Stroke(width = strokePx * 1.3f, cap = StrokeCap.Round),
                    topLeft = arcTopLeft,
                    size = arcSize,
                )
            }

            // Main progress arc with filament gradient
            drawArc(
                brush = filamentGradient,
                startAngle = progressStartAngle,
                sweepAngle = progressSweepAngle,
                useCenter = false,
                style = arcStroke,
                topLeft = arcTopLeft,
                size = arcSize,
            )
        }
    }
}

/**
 * Creates a filament gradient for the progress arc.
 *
 * For gold colors (GoldFilament), uses: GoldFilamentDim → GoldFilament → GoldFilamentBright
 * For other colors (e.g., ImpossibleAccent), creates monochromatic gradient with alpha variations
 */
private fun createFilamentGradient(baseColor: Color): Brush {
    // More robust gold color detection using direct color comparison
    // This avoids fragile range checks that break with theme changes
    val isGoldColor = baseColor == GoldFilament ||
                      baseColor == GoldFilamentBright ||
                      baseColor == GoldFilamentDim ||
                      // Fallback: check if color is in gold family (warm yellow-orange tones)
                      (baseColor.red > 0.75f &&
                       baseColor.green > 0.65f &&
                       baseColor.blue < 0.55f &&
                       baseColor.red > baseColor.green &&
                       baseColor.green > baseColor.blue)

    return if (isGoldColor) {
        // Gold filament gradient: dim → bright → brighter
        Brush.sweepGradient(
            colors = listOf(
                GoldFilamentDim.copy(alpha = 0.7f),
                GoldFilament.copy(alpha = 1.0f),
                GoldFilamentBright.copy(alpha = 0.95f)
            )
        )
    } else {
        // Monochromatic gradient for non-gold colors (e.g., ImpossibleAccent)
        Brush.sweepGradient(
            colors = listOf(
                baseColor.copy(alpha = 0.65f),
                baseColor.copy(alpha = 1.0f),
                baseColor.copy(alpha = 0.85f)
            )
        )
    }
}

/**
 * Ring style configuration for per-page tuning.
 *
 * @param strokeDp Stroke width in dp (thinner for subtlety)
 * @param insetDp Edge breathing room in dp (prevents "stapled-on" look)
 * @param trackAlpha Background track transparency (0.0-1.0)
 * @param glowAlpha Glow effect intensity (0.0-1.0)
 * @param usePartialTrack If true, renders 300° arc with gap at 6 o'clock
 * @param trackStartAngle Starting angle for partial track (default: -90° = 12 o'clock)
 * @param trackSweepAngle Arc length for partial track (default: 360° = full circle)
 */
data class RingStyle(
    val strokeDp: Float,
    val insetDp: Float,
    val trackAlpha: Float,
    val glowAlpha: Float,
    val usePartialTrack: Boolean,
    val trackStartAngle: Float = -90f,
    val trackSweepAngle: Float = 360f
) {
    companion object {
        /**
         * Small ring preset for library grid tiles.
         *
         * Features:
         * - Subtle 3dp stroke
         * - 7dp inset for breathing room
         * - Partial 300° track (gap at 6 o'clock)
         * - Low glow for restraint
         */
        val LibrarySmall = RingStyle(
            strokeDp = 3.0f,
            insetDp = 7.0f,
            trackAlpha = 0.20f,
            glowAlpha = 0.20f,
            usePartialTrack = true,
            trackStartAngle = -90f,
            trackSweepAngle = 300f
        )

        /**
         * Small ring preset for home screen 3x3 grid.
         *
         * Features:
         * - Slightly thicker 3.2dp stroke (more prominent than library)
         * - 7dp inset for breathing room
         * - Partial 300° track (gap at 6 o'clock)
         * - Slightly higher track/glow alpha for brand showcase
         */
        val HomeSmall = RingStyle(
            strokeDp = 3.2f,
            insetDp = 7.0f,
            trackAlpha = 0.22f,
            glowAlpha = 0.22f,
            usePartialTrack = true,
            trackStartAngle = -90f,
            trackSweepAngle = 300f
        )

        /**
         * Large ring preset for player screen book cover.
         *
         * Features:
         * - Ceremonial 4.8dp stroke (prominent but not dominant)
         * - 10dp inset for breathing room on large covers
         * - Full 360° ring (appropriate for hero display)
         * - Stronger glow for depth on large canvas
         */
        val PlayerLarge = RingStyle(
            strokeDp = 4.8f,
            insetDp = 10.0f,
            trackAlpha = 0.18f,
            glowAlpha = 0.30f,
            usePartialTrack = false,
            trackStartAngle = -90f,
            trackSweepAngle = 360f
        )

        /**
         * Medium ring preset for book detail screen.
         *
         * Features:
         * - Balanced 4dp stroke
         * - 8dp inset for breathing room
         * - Full 360° ring (hero cover display)
         * - Moderate glow for clean look
         */
        val BookDetail = RingStyle(
            strokeDp = 4.0f,
            insetDp = 8.0f,
            trackAlpha = 0.20f,
            glowAlpha = 0.25f,
            usePartialTrack = false,
            trackStartAngle = -90f,
            trackSweepAngle = 360f
        )
    }
}
