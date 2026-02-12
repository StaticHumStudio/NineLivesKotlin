package com.ninelivesaudio.app.ui.animation.unhinged.anomalies

import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Anomaly Effect Interface
 *
 * Defines the contract for visual anomalies in Unhinged Mode.
 * Anomalies are rare, subtle visual effects that appear at safe moments
 * to enhance atmosphere without interfering with interaction.
 *
 * Design rules:
 * - Never block interaction (overlays are hit-test-transparent)
 * - Extremely subtle (2-6% opacity max)
 * - Rare enough to feel special ("did that just happen?")
 * - Must support reduce motion (static fallback)
 */
interface IAnomalyEffect {
    /**
     * Unique identifier for this anomaly type
     */
    val id: String

    /**
     * How long the anomaly lasts (in milliseconds)
     */
    val durationMs: Long

    /**
     * Whether this effect has a reduce motion fallback.
     * If false, the effect won't show at all when reduce motion is enabled.
     */
    val supportsReducedMotion: Boolean

    /**
     * Opacity range for this effect (0f to 1f)
     * Most anomalies should stay in the 0.02 to 0.06 range (2-6%)
     */
    val maxOpacity: Float

    /**
     * Draw the animated version of this anomaly
     *
     * @param drawScope The canvas drawing scope
     * @param progress Animation progress (0f to 1f)
     * @param canvasWidth Width of the overlay canvas
     * @param canvasHeight Height of the overlay canvas
     */
    fun drawAnimated(
        drawScope: DrawScope,
        progress: Float,
        canvasWidth: Float,
        canvasHeight: Float
    )

    /**
     * Draw the static version (for reduce motion mode)
     *
     * @param drawScope The canvas drawing scope
     * @param canvasWidth Width of the overlay canvas
     * @param canvasHeight Height of the overlay canvas
     */
    fun drawStatic(
        drawScope: DrawScope,
        canvasWidth: Float,
        canvasHeight: Float
    ) {
        // Default: draw at 50% progress
        drawAnimated(drawScope, 0.5f, canvasWidth, canvasHeight)
    }
}

/**
 * Anomaly trigger context
 *
 * Defines when/where an anomaly can appear
 */
enum class AnomalyTriggerContext {
    /** Home screen, safe to show anomalies */
    HOME,

    /** Library browsing, safe to show anomalies */
    LIBRARY,

    /** App is idle (no user interaction for 60+ seconds) */
    IDLE,

    /** Download completed (moment of transition) */
    DOWNLOAD_COMPLETE,

    /** Chapter finished (moment of transition) */
    CHAPTER_FINISHED
}

/**
 * Anomaly configuration
 *
 * Defines how often and where anomalies can appear
 */
data class AnomalyConfig(
    /**
     * Minimum cooldown between anomalies (in milliseconds)
     * Default: 3 minutes
     */
    val minCooldownMs: Long = 180_000L,

    /**
     * Maximum cooldown between anomalies (in milliseconds)
     * Default: 10 minutes
     */
    val maxCooldownMs: Long = 600_000L,

    /**
     * Contexts where anomalies are allowed
     */
    val allowedContexts: Set<AnomalyTriggerContext> = setOf(
        AnomalyTriggerContext.HOME,
        AnomalyTriggerContext.LIBRARY,
        AnomalyTriggerContext.IDLE
    ),

    /**
     * Contexts where anomalies are NEVER allowed
     */
    val blockedContexts: Set<AnomalyTriggerContext> = emptySet(),

    /**
     * Random seed for deterministic anomaly selection
     * Same seed = same sequence of anomalies per session
     */
    val randomSeed: Long = System.currentTimeMillis()
)
