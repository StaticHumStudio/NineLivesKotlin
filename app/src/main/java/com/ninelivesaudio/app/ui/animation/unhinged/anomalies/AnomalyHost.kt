package com.ninelivesaudio.app.ui.animation.unhinged.anomalies

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.ninelivesaudio.app.ui.components.unhinged.LocalUnhingedSettings
import com.ninelivesaudio.app.ui.theme.unhinged.shouldShowAnomalies
import kotlinx.coroutines.delay

/**
 * Anomaly Host — Overlay Container for Visual Anomalies
 *
 * A transparent overlay that wraps main content and displays rare visual anomalies.
 * This is the single composable wrapper that manages all anomaly effects.
 *
 * **Critical properties**:
 * - Transparent overlay (sits on top of content)
 * - Never blocks interaction (pointer input disabled)
 * - Respects anomaly settings and reduce motion
 * - Only triggers on safe screens
 *
 * Usage:
 * ```
 * AnomalyHost(currentContext = AnomalyTriggerContext.HOME) {
 *     // Your main content here
 * }
 * ```
 */
@Composable
fun AnomalyHost(
    currentContext: AnomalyTriggerContext,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val unhingedSettings = LocalUnhingedSettings.current
    val shouldShowAnomalies = unhingedSettings.shouldShowAnomalies
    val reduceMotion = unhingedSettings.reduceMotionRequested

    // Current anomaly state
    var currentAnomaly by remember { mutableStateOf<IAnomalyEffect?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    // Animation progress
    var animationProgress by remember { mutableStateOf(0f) }

    // Animation effect
    LaunchedEffect(currentAnomaly, isPlaying) {
        if (currentAnomaly != null && isPlaying) {
            val duration = currentAnomaly!!.durationMs

            if (reduceMotion) {
                // Static display for half the duration
                delay(duration / 2)
                isPlaying = false
                currentAnomaly = null
            } else {
                // Animate from 0 to 1 over duration
                val startTime = System.currentTimeMillis()
                while (isPlaying && currentAnomaly != null) {
                    val elapsed = System.currentTimeMillis() - startTime
                    animationProgress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)

                    if (animationProgress >= 1f) {
                        isPlaying = false
                        currentAnomaly = null
                        animationProgress = 0f
                    }

                    delay(16) // ~60fps
                }
            }
        }
    }

    Box(modifier = modifier) {
        // Main content
        content()

        // Anomaly overlay (only if enabled and anomaly is active)
        if (shouldShowAnomalies && currentAnomaly != null) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Make overlay non-interactive
                        // Note: Compose doesn't have direct hit testing control like Android Views
                        // We rely on Canvas being naturally non-interactive
                    }
            ) {
                val effect = currentAnomaly ?: return@Canvas

                if (reduceMotion && effect.supportsReducedMotion) {
                    effect.drawStatic(this, size.width, size.height)
                } else if (!reduceMotion) {
                    effect.drawAnimated(
                        this,
                        animationProgress,
                        size.width,
                        size.height
                    )
                }
            }
        }
    }

    // Anomaly scheduler (separate from display logic)
    AnomalyScheduler(
        currentContext = currentContext,
        enabled = shouldShowAnomalies,
        onTriggerAnomaly = { anomaly ->
            currentAnomaly = anomaly
            isPlaying = true
            animationProgress = 0f
        }
    )
}

/**
 * Anomaly Scheduler
 *
 * Manages the pseudo-random scheduling of anomalies.
 * Ensures cooldowns are respected and only triggers in safe contexts.
 */
@Composable
private fun AnomalyScheduler(
    currentContext: AnomalyTriggerContext,
    enabled: Boolean,
    onTriggerAnomaly: (IAnomalyEffect) -> Unit
) {
    val config = remember { AnomalyConfig() }
    // Initialize to current time so the scheduler waits for the first cooldown
    // before triggering. Previous value of 0L caused an immediate fire on every
    // screen entry because the condition (lastTriggerTime == 0L) was always true.
    var lastTriggerTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var nextTriggerDelay by remember {
        mutableStateOf(
            kotlin.random.Random(config.randomSeed).nextLong(
                config.minCooldownMs,
                config.maxCooldownMs
            )
        )
    }

    LaunchedEffect(enabled, currentContext) {
        if (!enabled) return@LaunchedEffect
        if (currentContext !in config.allowedContexts) return@LaunchedEffect
        if (currentContext in config.blockedContexts) return@LaunchedEffect

        while (enabled) {
            val now = System.currentTimeMillis()

            // Check if enough time has passed since last anomaly
            if ((now - lastTriggerTime) >= nextTriggerDelay) {
                // Trigger a random anomaly
                val anomaly = selectRandomAnomaly(config.randomSeed + now)
                onTriggerAnomaly(anomaly)

                // Update timing
                lastTriggerTime = now
                nextTriggerDelay = kotlin.random.Random(config.randomSeed + now).nextLong(
                    config.minCooldownMs,
                    config.maxCooldownMs
                )
            }

            // Check every 10 seconds
            delay(10_000)
        }
    }
}

/**
 * Select a random anomaly effect from the available pool
 */
private fun selectRandomAnomaly(seed: Long): IAnomalyEffect {
    val random = kotlin.random.Random(seed)
    val effects = listOf(
        { TearVeilEffect(seed) },
        { InkBleedEffect(seed) },
        { CrackWhisperEffect(seed) }
    )

    return effects[random.nextInt(effects.size)]()
}

/**
 * Debug: Force trigger an anomaly
 * Use this in development to test anomaly effects without waiting for cooldowns
 */
@Composable
fun AnomalyHostDebug(
    currentContext: AnomalyTriggerContext,
    forcedAnomaly: IAnomalyEffect? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val unhingedSettings = LocalUnhingedSettings.current
    val shouldShowAnomalies = unhingedSettings.shouldShowAnomalies || forcedAnomaly != null
    val reduceMotion = unhingedSettings.reduceMotionRequested

    var currentAnomaly by remember { mutableStateOf(forcedAnomaly) }
    var isPlaying by remember { mutableStateOf(forcedAnomaly != null) }
    var animationProgress by remember { mutableStateOf(0f) }

    // Update when forced anomaly changes
    LaunchedEffect(forcedAnomaly) {
        if (forcedAnomaly != null) {
            currentAnomaly = forcedAnomaly
            isPlaying = true
            animationProgress = 0f
        }
    }

    // Animation logic
    LaunchedEffect(currentAnomaly, isPlaying) {
        if (currentAnomaly != null && isPlaying) {
            val duration = currentAnomaly!!.durationMs

            if (reduceMotion) {
                delay(duration / 2)
                if (forcedAnomaly == null) {
                    isPlaying = false
                    currentAnomaly = null
                }
            } else {
                val startTime = System.currentTimeMillis()
                while (isPlaying && currentAnomaly != null) {
                    val elapsed = System.currentTimeMillis() - startTime
                    animationProgress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)

                    if (animationProgress >= 1f) {
                        if (forcedAnomaly == null) {
                            isPlaying = false
                            currentAnomaly = null
                        } else {
                            // Loop for debug mode
                            animationProgress = 0f
                        }
                    }

                    delay(16)
                }
            }
        }
    }

    Box(modifier = modifier) {
        content()

        if (shouldShowAnomalies && currentAnomaly != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val effect = currentAnomaly ?: return@Canvas

                if (reduceMotion && effect.supportsReducedMotion) {
                    effect.drawStatic(this, size.width, size.height)
                } else if (!reduceMotion) {
                    effect.drawAnimated(this, animationProgress, size.width, size.height)
                }
            }
        }
    }
}
