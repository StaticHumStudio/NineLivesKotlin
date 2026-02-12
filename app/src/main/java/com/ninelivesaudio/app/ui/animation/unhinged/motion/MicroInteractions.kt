package com.ninelivesaudio.app.ui.animation.unhinged.motion

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import com.ninelivesaudio.app.ui.components.unhinged.LocalUnhingedSettings

/**
 * Press Feedback — Scale Animation
 *
 * Adds a subtle scale animation (0.98 → 1.0) when the element is pressed.
 * Creates satisfying tactile feedback for interactive elements.
 *
 * **Reduce motion**: Skips animation entirely if reduce motion is enabled.
 *
 * @param enabled Whether press feedback is active (default: true)
 */
fun Modifier.pressFeedback(
    enabled: Boolean = true
): Modifier = composed {
    val unhingedSettings = LocalUnhingedSettings.current
    val reduceMotion = unhingedSettings.reduceMotionRequested

    if (!enabled || reduceMotion) {
        return@composed this
    }

    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) MotionTokens.PressScale else 1f,
        animationSpec = MotionTokens.standardSpring(),
        label = "press_feedback_scale"
    )

    this
        .scale(scale)
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    isPressed = true
                    tryAwaitRelease()
                    isPressed = false
                }
            )
        }
}

/**
 * Selection Glow Pulse
 *
 * Adds a subtle opacity pulse to a glow effect when selected.
 * The glow oscillates between 30% and 60% opacity.
 *
 * **Reduce motion**: Shows static glow at 50% opacity if reduce motion is enabled.
 *
 * @param isSelected Whether this element is selected
 * @param enabled Whether the glow effect is active (default: true)
 */
fun Modifier.selectionGlowPulse(
    isSelected: Boolean,
    enabled: Boolean = true
): Modifier = composed {
    val unhingedSettings = LocalUnhingedSettings.current
    val reduceMotion = unhingedSettings.reduceMotionRequested

    if (!enabled || !isSelected) {
        return@composed this
    }

    if (reduceMotion) {
        // Static glow - no animation
        return@composed this.alpha(0.5f)
    }

    // Animated glow pulse
    val infiniteTransition = rememberInfiniteTransition(label = "glow_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = MotionTokens.GlowPulseMin,
        targetValue = MotionTokens.GlowPulseMax,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = MotionTokens.DurationVerySlow,
                easing = MotionTokens.EasingStandard
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    this.alpha(alpha)
}

/**
 * Fade and Slide Enter/Exit Animations
 *
 * Standard enter/exit transitions for panels and popups.
 * Elements fade in while sliding up, fade out while sliding down.
 *
 * **Reduce motion**: Uses fade only (no slide) if reduce motion is enabled.
 */
object ArchiveTransitions {

    /**
     * Fade and slide up entrance
     */
    fun fadeSlideInEnter(
        reduceMotionRequested: Boolean = false
    ): EnterTransition {
        return if (reduceMotionRequested) {
            fadeIn(animationSpec = tween(MotionTokens.DurationQuick))
        } else {
            fadeIn(
                animationSpec = MotionTokens.emphasizedEntrance()
            ) + slideInVertically(
                animationSpec = MotionTokens.emphasizedEntrance(),
                initialOffsetY = { it / 4 } // Slide from 25% down
            )
        }
    }

    /**
     * Fade and slide down exit
     */
    fun fadeSlideOutExit(
        reduceMotionRequested: Boolean = false
    ): ExitTransition {
        return if (reduceMotionRequested) {
            fadeOut(animationSpec = tween(MotionTokens.DurationQuick))
        } else {
            fadeOut(
                animationSpec = MotionTokens.decelerateExit()
            ) + slideOutVertically(
                animationSpec = MotionTokens.decelerateExit(),
                targetOffsetY = { it / 4 } // Slide to 25% down
            )
        }
    }

    /**
     * Expand and fade in (for expanding panels)
     */
    fun expandFadeInEnter(
        reduceMotionRequested: Boolean = false
    ): EnterTransition {
        return if (reduceMotionRequested) {
            fadeIn(animationSpec = tween(MotionTokens.DurationQuick))
        } else {
            fadeIn(
                animationSpec = MotionTokens.standardTween()
            ) + expandVertically(
                animationSpec = MotionTokens.standardTween(),
                expandFrom = androidx.compose.ui.Alignment.Top
            )
        }
    }

    /**
     * Shrink and fade out (for collapsing panels)
     */
    fun shrinkFadeOutExit(
        reduceMotionRequested: Boolean = false
    ): ExitTransition {
        return if (reduceMotionRequested) {
            fadeOut(animationSpec = tween(MotionTokens.DurationQuick))
        } else {
            fadeOut(
                animationSpec = MotionTokens.standardTween()
            ) + shrinkVertically(
                animationSpec = MotionTokens.standardTween(),
                shrinkTowards = androidx.compose.ui.Alignment.Top
            )
        }
    }
}

/**
 * Shimmer Effect (for loading states)
 *
 * Creates a subtle shimmer animation that sweeps across the element.
 * Useful for loading placeholders or to add subtle life to static elements.
 *
 * **Reduce motion**: Shows static gradient if reduce motion is enabled.
 *
 * @param enabled Whether shimmer is active (default: true)
 */
fun Modifier.shimmerEffect(
    enabled: Boolean = true
): Modifier = composed {
    val unhingedSettings = LocalUnhingedSettings.current
    val reduceMotion = unhingedSettings.reduceMotionRequested

    if (!enabled) {
        return@composed this
    }

    if (reduceMotion) {
        // Static - no animation
        return@composed this
    }

    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = MotionTokens.DurationVerySlow * 2,
                easing = MotionTokens.EasingLinear
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    this.graphicsLayer {
        translationX = size.width * translateAnim
    }
}

/**
 * Breathing Animation (subtle scale pulse)
 *
 * Very subtle scale animation that makes elements feel "alive".
 * Scale oscillates between 0.99 and 1.01 (barely noticeable).
 *
 * Use extremely sparingly - only for special moments where you want
 * to draw attention to something important.
 *
 * **Reduce motion**: No animation if reduce motion is enabled.
 *
 * @param enabled Whether breathing effect is active
 */
fun Modifier.breathingEffect(
    enabled: Boolean = false
): Modifier = composed {
    val unhingedSettings = LocalUnhingedSettings.current
    val reduceMotion = unhingedSettings.reduceMotionRequested

    if (!enabled || reduceMotion) {
        return@composed this
    }

    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.99f,
        targetValue = 1.01f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = MotionTokens.DurationUltraSlow,
                easing = MotionTokens.EasingStandard
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing_scale"
    )

    this.scale(scale)
}

/**
 * Animated visibility with Archive transitions
 *
 * Convenience wrapper for AnimatedVisibility using Archive-style transitions.
 */
@Composable
fun ArchiveAnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    val unhingedSettings = LocalUnhingedSettings.current
    val reduceMotion = unhingedSettings.reduceMotionRequested

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = ArchiveTransitions.fadeSlideInEnter(reduceMotion),
        exit = ArchiveTransitions.fadeSlideOutExit(reduceMotion),
        content = content
    )
}
