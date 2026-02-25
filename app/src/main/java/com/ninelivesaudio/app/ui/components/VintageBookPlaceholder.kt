package com.ninelivesaudio.app.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ninelivesaudio.app.ui.theme.unhinged.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * A procedurally drawn vintage, worn library book cover.
 * Used as a placeholder when no cover art is available.
 *
 * Draws leather-textured background, spine, decorative gold lines,
 * optional title text, and aging effects. Each book gets deterministic
 * visual variation from its [seed].
 */
@Composable
fun VintageBookPlaceholder(
    modifier: Modifier = Modifier,
    title: String? = null,
    seed: Int = 0,
) {
    val density = LocalDensity.current

    // Pre-compute seed-derived variation (deterministic per book)
    val seedHash = abs(seed)
    val grainVariation = (seedHash % 256) / 255f
    val scuffX1 = ((seedHash shr 8) % 256) / 255f
    val scuffY1 = ((seedHash shr 16) % 256) / 255f
    val scuffX2 = ((seedHash shr 4) % 256) / 255f

    // Paint for title text (remembered to avoid re-allocation)
    val textPaint = remember(title) {
        Paint().apply {
            typeface = Typeface.SERIF
            isAntiAlias = true
            color = VintageTextGold.copy(alpha = 0.7f).toArgb()
            textAlign = Paint.Align.CENTER
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val smallThreshold = with(density) { 56.dp.toPx() }
        val isSmall = min(w, h) < smallThreshold

        // ── Layer 1: Base leather gradient ──────────────────────────
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(VintageLeather, VintageLeatherDark),
            ),
            size = size,
        )

        // ── Layer 2: Spine line ─────────────────────────────────────
        val spineX = w * 0.09f
        val spineWidth = if (isSmall) 3f else 4f
        drawRect(
            color = VintageSpine,
            topLeft = Offset(spineX - spineWidth / 2f, 0f),
            size = Size(spineWidth, h),
        )
        // Spine shadow
        drawLine(
            color = VintageWear.copy(alpha = 0.5f),
            start = Offset(spineX + spineWidth / 2f, 0f),
            end = Offset(spineX + spineWidth / 2f, h),
            strokeWidth = 1f,
        )
        // Spine highlight
        drawLine(
            color = VintageSpine.copy(alpha = 0.3f),
            start = Offset(spineX - spineWidth / 2f - 1f, 0f),
            end = Offset(spineX - spineWidth / 2f - 1f, h),
            strokeWidth = 1f,
        )

        // ── Layer 3: Leather grain texture ──────────────────────────
        val grainCount = if (isSmall) 10 else 25
        val baseAngle = (grainVariation - 0.5f) * 0.05f // slight tilt
        for (i in 0 until grainCount) {
            val yFraction = i.toFloat() / grainCount
            val y = h * yFraction
            val alpha = 0.04f + (sin(i * 2.7f + grainVariation * 6f).toFloat() * 0.03f)
            drawLine(
                color = VintageWear.copy(alpha = alpha.coerceIn(0.02f, 0.08f)),
                start = Offset(spineX + 4f, y + baseAngle * w),
                end = Offset(w, y - baseAngle * w),
                strokeWidth = 0.8f,
            )
        }

        if (!isSmall) {
            // ── Layer 4: Border frame inset ─────────────────────────
            val frameLeft = spineX + w * 0.08f
            val frameTop = h * 0.08f
            val frameWidth = w - frameLeft - w * 0.06f
            val frameHeight = h - frameTop * 2f
            val borderStroke = with(density) { 1.dp.toPx() }
            drawRoundRect(
                color = VintageBorder.copy(alpha = 0.55f),
                topLeft = Offset(frameLeft, frameTop),
                size = Size(frameWidth, frameHeight),
                cornerRadius = CornerRadius(4f, 4f),
                style = Stroke(width = borderStroke),
            )

            // ── Layer 5: Top decorative line ────────────────────────
            val decoY1 = frameTop + frameHeight * 0.15f
            val lineLeft = frameLeft + frameWidth * 0.1f
            val lineRight = frameLeft + frameWidth * 0.9f
            drawLine(
                color = VintageGoldFaded.copy(alpha = 0.45f),
                start = Offset(lineLeft, decoY1),
                end = Offset(lineRight, decoY1),
                strokeWidth = borderStroke,
            )
            // Small center diamond
            val diamondCx = frameLeft + frameWidth / 2f
            val diamondR = with(density) { 2.dp.toPx() }
            drawLine(
                color = VintageGoldFaded.copy(alpha = 0.4f),
                start = Offset(diamondCx - diamondR, decoY1),
                end = Offset(diamondCx, decoY1 - diamondR),
                strokeWidth = borderStroke,
            )
            drawLine(
                color = VintageGoldFaded.copy(alpha = 0.4f),
                start = Offset(diamondCx, decoY1 - diamondR),
                end = Offset(diamondCx + diamondR, decoY1),
                strokeWidth = borderStroke,
            )

            // ── Layer 6: Bottom decorative line ─────────────────────
            val decoY2 = frameTop + frameHeight * 0.85f
            drawLine(
                color = VintageGoldFaded.copy(alpha = 0.45f),
                start = Offset(lineLeft, decoY2),
                end = Offset(lineRight, decoY2),
                strokeWidth = borderStroke,
            )

            // ── Layer 7: Title text ─────────────────────────────────
            if (!title.isNullOrBlank()) {
                val safeTitle: String = title
                val titleAreaTop = decoY1 + frameHeight * 0.05f
                val titleAreaHeight = decoY2 - decoY1 - frameHeight * 0.1f
                val textCenterX = frameLeft + frameWidth / 2f
                val maxTextWidth = frameWidth * 0.8f

                // Scale text size proportionally
                val textSize = (h * 0.065f).coerceIn(
                    with(density) { 8.dp.toPx() },
                    with(density) { 18.dp.toPx() },
                )
                textPaint.textSize = textSize

                // Truncate title if too wide
                val displayTitle = run {
                    val measured = textPaint.measureText(safeTitle)
                    if (measured <= maxTextWidth) {
                        safeTitle
                    } else {
                        var truncated = safeTitle
                        while (truncated.length > 3 && textPaint.measureText("$truncated...") > maxTextWidth) {
                            truncated = truncated.dropLast(1)
                        }
                        "$truncated..."
                    }
                }

                // Center vertically in title area
                val textY = titleAreaTop + titleAreaHeight / 2f + textSize / 3f

                drawContext.canvas.nativeCanvas.drawText(
                    displayTitle,
                    textCenterX,
                    textY,
                    textPaint,
                )
            }
        }

        // ── Layer 8: Corner wear overlays ───────────────────────────
        val cornerRadius = min(w, h) * 0.2f
        val cornerAlpha = 0.25f
        val cornerColor = VintageWear.copy(alpha = cornerAlpha)
        // Top-left
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(cornerColor, VintageWear.copy(alpha = 0f)),
                center = Offset(0f, 0f),
                radius = cornerRadius,
            ),
            center = Offset(0f, 0f),
            radius = cornerRadius,
        )
        // Top-right
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(cornerColor, VintageWear.copy(alpha = 0f)),
                center = Offset(w, 0f),
                radius = cornerRadius,
            ),
            center = Offset(w, 0f),
            radius = cornerRadius,
        )
        // Bottom-left
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(cornerColor, VintageWear.copy(alpha = 0f)),
                center = Offset(0f, h),
                radius = cornerRadius,
            ),
            center = Offset(0f, h),
            radius = cornerRadius,
        )
        // Bottom-right
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(cornerColor, VintageWear.copy(alpha = 0f)),
                center = Offset(w, h),
                radius = cornerRadius,
            ),
            center = Offset(w, h),
            radius = cornerRadius,
        )

        // ── Layer 9: Aging vignette ─────────────────────────────────
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    VintageWear.copy(alpha = 0f),
                    VintageWear.copy(alpha = 0.18f),
                ),
                center = Offset(w / 2f, h / 2f),
                radius = maxOf(w, h) * 0.7f,
            ),
            size = size,
        )

        // ── Layer 10: Scuff marks (seed-based) ─────────────────────
        if (!isSmall) {
            val scuffColor = VintageSpine.copy(alpha = 0.1f)
            val scuffSize = min(w, h) * 0.06f
            // Scuff 1
            drawOval(
                color = scuffColor,
                topLeft = Offset(
                    spineX + (w - spineX) * scuffX1 * 0.7f + w * 0.1f,
                    h * scuffY1 * 0.6f + h * 0.2f,
                ),
                size = Size(scuffSize * 1.5f, scuffSize),
            )
            // Scuff 2
            drawOval(
                color = scuffColor.copy(alpha = 0.07f),
                topLeft = Offset(
                    spineX + (w - spineX) * scuffX2 * 0.5f + w * 0.15f,
                    h * 0.7f + h * scuffX1 * 0.15f,
                ),
                size = Size(scuffSize, scuffSize * 1.3f),
            )
        }
    }
}
