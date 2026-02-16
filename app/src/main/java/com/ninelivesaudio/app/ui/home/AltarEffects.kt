package com.ninelivesaudio.app.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import com.ninelivesaudio.app.ui.components.unhinged.LocalUnhingedSettings
import com.ninelivesaudio.app.ui.theme.unhinged.ArchiveOutline
import com.ninelivesaudio.app.ui.theme.unhinged.ArchiveVoidDeep
import com.ninelivesaudio.app.ui.theme.unhinged.GoldFilament
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * AltarEffects
 *
 * A lightweight, self-contained effect layer for the Home altar.
 *
 * - Soot motes drift upward (subtle particle field)
 * - Candle-ish flicker (soft pulsing vignette)
 * - Micro-glitch nudges (offset jitter for logo/title)
 *
 * Respects Reduce Motion.
 */
@Composable
internal fun AltarEffects(
    modifier: Modifier = Modifier,
    intensity: Float,
    seed: Int,
) {
    val settings = LocalUnhingedSettings.current
    val reduceMotion = settings.reduceMotionRequested

    // Clamp intensity, but keep a minimum so it never looks dead.
    val i = intensity.coerceIn(0.15f, 1f)

    val infinite = rememberInfiniteTransition(label = "altar_fx")

    val driftT = if (reduceMotion) {
        rememberUpdatedState(0f)
    } else {
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = (9000 - (i * 2500)).toInt().coerceAtLeast(4200)),
                repeatMode = RepeatMode.Restart,
            ),
            label = "soot_drift",
        )
    }

    val flicker = if (reduceMotion) {
        rememberUpdatedState(0.88f)
    } else {
        infinite.animateFloat(
            initialValue = 0.82f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = (1400 - (i * 600)).toInt().coerceAtLeast(600)),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "altar_flicker",
        )
    }

    // Generate particles deterministically (no per-frame randomness).
    val particles = remember(seed) { buildSootParticles(seed, count = 22) }

    Box(modifier = modifier) {
        // Flicker vignette (drawn first, then motes over it)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.55f * i)
                .scale(1.02f),
        ) {
            val w = size.width
            val h = size.height

            val vignette = Brush.radialGradient(
                colors = listOf(
                    GoldFilament.copy(alpha = 0.10f * i * flicker.value),
                    ArchiveVoidDeep.copy(alpha = 0.42f * flicker.value),
                    Color.Black.copy(alpha = 0.55f),
                ),
                center = Offset(w * 0.52f, h * 0.35f),
                radius = (w.coerceAtLeast(h) * 0.85f),
            )

            drawRect(brush = vignette, size = Size(w, h))

            // A faint diagonal "scratch" sheen that comes and goes.
            rotate(degrees = 18f) {
                val sheen = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        ArchiveOutline.copy(alpha = 0.18f * i * abs(flicker.value - 0.86f) * 2f),
                        Color.Transparent,
                    ),
                    start = Offset(0f, h * 0.1f),
                    end = Offset(w, h * 0.9f),
                )
                drawRect(
                    brush = sheen,
                    size = Size(w * 1.2f, h * 1.2f),
                    blendMode = BlendMode.Screen,
                )
            }
        }

        // Soot motes (tiny drifting circles)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.40f * i),
        ) {
            val w = size.width
            val h = size.height
            val t = driftT.value
            val twopi = (2f * PI.toFloat())

            particles.forEach { p ->
                val y = ((p.y0 - (t * p.speed)) % 1f + 1f) % 1f
                val wobble = sin((t * twopi) + p.phase) * (0.006f + 0.01f * i)
                val x = (p.x0 + wobble).coerceIn(0.02f, 0.98f)

                val alpha = (0.12f + 0.20f * i) * (0.55f + 0.45f * sin((t * twopi) + p.phase))
                val r = (p.rPx * (0.85f + 0.55f * i)).coerceAtLeast(1.2f)

                drawCircle(
                    color = Color.White.copy(alpha = alpha.coerceIn(0.02f, 0.28f)),
                    radius = r,
                    center = Offset(w * x, h * y),
                    style = Fill,
                    blendMode = BlendMode.Screen,
                )
            }
        }
    }
}

internal data class SootParticle(
    val x0: Float,
    val y0: Float,
    val rPx: Float,
    val speed: Float,
    val phase: Float,
)

internal fun buildSootParticles(seed: Int, count: Int): List<SootParticle> {
    val rng = Random(seed)
    return List(count) {
        val x = rng.nextFloat().coerceIn(0.02f, 0.98f)
        val y = rng.nextFloat().coerceIn(0.02f, 0.98f)
        val r = 1.5f + rng.nextFloat() * 3.2f
        val speed = 0.10f + rng.nextFloat() * 0.28f
        val phase = rng.nextFloat() * (2f * PI.toFloat())
        SootParticle(x0 = x, y0 = y, rPx = r, speed = speed, phase = phase)
    }
}

/**
 * Micro-glitch offset for altar elements.
 */
@Composable
internal fun rememberAltarGlitchOffset(
    intensity: Float,
    seed: Int,
): MutableState<IntOffset> {
    val settings = LocalUnhingedSettings.current
    val reduceMotion = settings.reduceMotionRequested
    val i = intensity.coerceIn(0f, 1f)

    val offset = remember { mutableStateOf(IntOffset.Zero) }

    LaunchedEffect(seed, reduceMotion) {
        if (reduceMotion) {
            offset.value = IntOffset.Zero
            return@LaunchedEffect
        }

        val rng = Random(seed * 73 + 19)
        while (true) {
            val base = (2200 - (i * 1400)).toLong().coerceAtLeast(600)
            val jitter = rng.nextLong(0, 1400)
            delay(base + jitter)

            val dx = rng.nextInt(-2, 3)
            val dy = rng.nextInt(-2, 3)
            offset.value = IntOffset(dx, dy)
            delay((60 + rng.nextInt(0, 70)).toLong())
            offset.value = IntOffset.Zero
        }
    }

    return offset
}
