package com.ninelivesaudio.app.ui.animation.unhinged.motion

import androidx.compose.animation.core.*
import androidx.compose.ui.unit.dp

/**
 * Motion Tokens — Archive Animation Language
 *
 * Central animation constants for the Unhinged Mode motion system.
 * All animations use these tokens for consistency and to respect
 * the user's reduce motion preferences.
 *
 * Design Philosophy:
 * - Snappy and responsive, never floaty or laggy
 * - Subtle and atmospheric, never distracting
 * - Respects accessibility (reduce motion)
 * - No infinite loops by default
 */
object MotionTokens {

    // ═══════════════════════════════════════════════════════════════
    //  Duration Constants
    // ═══════════════════════════════════════════════════════════════

    /**
     * Quick motion - for immediate feedback
     * Examples: press feedback, ripples, small state changes
     */
    const val DurationQuick = 120

    /**
     * Standard motion - for most transitions
     * Examples: fade in/out, slide transitions, scale animations
     */
    const val DurationStandard = 250

    /**
     * Slow motion - for deliberate, atmospheric moments
     * Examples: panel reveals, anomaly fades, whisper appearances
     */
    const val DurationSlow = 600

    /**
     * Very slow motion - for subtle ambient effects
     * Examples: progress shimmer, selection glow pulse, anomaly drifts
     */
    const val DurationVerySlow = 1200

    /**
     * Ultra slow motion - for barely perceptible effects
     * Examples: sigil rotation drift (so slow you barely notice)
     */
    const val DurationUltraSlow = 3000

    // ═══════════════════════════════════════════════════════════════
    //  Easing Functions
    // ═══════════════════════════════════════════════════════════════

    /**
     * Standard easing - for most animations
     * Material Design's emphasized easing
     */
    val EasingStandard = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1f)

    /**
     * Emphasized easing - for important entrances
     * More pronounced deceleration
     */
    val EasingEmphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /**
     * Decelerate easing - for exits and fades
     */
    val EasingDecelerate = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1f)

    /**
     * Linear easing - for continuous effects
     * Use sparingly - most animations should have easing
     */
    val EasingLinear = LinearEasing

    // ═══════════════════════════════════════════════════════════════
    //  Tween Specs (Pre-configured)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Quick tween - 120ms with standard easing
     */
    fun <T> quickTween() = tween<T>(
        durationMillis = DurationQuick,
        easing = EasingStandard
    )

    /**
     * Standard tween - 250ms with standard easing
     */
    fun <T> standardTween() = tween<T>(
        durationMillis = DurationStandard,
        easing = EasingStandard
    )

    /**
     * Slow tween - 600ms with emphasized easing
     */
    fun <T> slowTween() = tween<T>(
        durationMillis = DurationSlow,
        easing = EasingEmphasized
    )

    /**
     * Emphasized entrance - for important reveals
     */
    fun <T> emphasizedEntrance() = tween<T>(
        durationMillis = DurationStandard,
        easing = EasingEmphasized
    )

    /**
     * Decelerate exit - for fade outs and dismissals
     */
    fun <T> decelerateExit() = tween<T>(
        durationMillis = DurationStandard,
        easing = EasingDecelerate
    )

    // ═══════════════════════════════════════════════════════════════
    //  Spring Specs (For Natural Motion)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Standard spring - responsive and snappy
     * Use for interactive elements (buttons, cards)
     */
    fun <T> standardSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )

    /**
     * Gentle spring - soft and smooth
     * Use for larger elements or atmospheric movements
     */
    fun <T> gentleSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow
    )

    // ═══════════════════════════════════════════════════════════════
    //  Animation Values
    // ═══════════════════════════════════════════════════════════════

    /**
     * Press scale - how much to scale down on press
     * 0.98 = 2% smaller (subtle but noticeable)
     */
    const val PressScale = 0.98f

    /**
     * Hover scale - how much to scale up on hover (desktop)
     * 1.02 = 2% larger
     */
    const val HoverScale = 1.02f

    /**
     * Glow pulse min alpha - minimum opacity for glow effects
     */
    const val GlowPulseMin = 0.3f

    /**
     * Glow pulse max alpha - maximum opacity for glow effects
     */
    const val GlowPulseMax = 0.6f

    /**
     * Standard elevation change - for card lifts
     */
    val ElevationLift = 4.dp

    // ═══════════════════════════════════════════════════════════════
    //  Reduce Motion Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get duration with reduce motion consideration.
     * If reduce motion is enabled, returns 0 (instant).
     *
     * @param durationMs Normal duration in milliseconds
     * @param reduceMotionRequested Whether reduce motion is enabled
     * @return Duration to use (0 if reduce motion, normal otherwise)
     */
    fun getDuration(
        durationMs: Int,
        reduceMotionRequested: Boolean
    ): Int = if (reduceMotionRequested) 0 else durationMs

    /**
     * Get animation spec with reduce motion consideration.
     * If reduce motion is enabled, returns snap (instant transition).
     *
     * @param normalSpec Normal animation spec
     * @param reduceMotionRequested Whether reduce motion is enabled
     * @return Animation spec to use
     */
    fun <T> getAnimationSpec(
        normalSpec: AnimationSpec<T>,
        reduceMotionRequested: Boolean
    ): AnimationSpec<T> = if (reduceMotionRequested) {
        snap() // Instant transition
    } else {
        normalSpec
    }
}
