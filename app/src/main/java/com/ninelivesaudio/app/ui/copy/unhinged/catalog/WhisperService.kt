package com.ninelivesaudio.app.ui.copy.unhinged.catalog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/** Minimum time between two launch whispers. */
internal const val LAUNCH_WHISPER_COOLDOWN_MS = 15 * 60 * 1000L

/** Minimum time between two event whispers, shared by every event context. */
internal const val EVENT_WHISPER_COOLDOWN_MS = 20 * 60 * 1000L

/** Minimum time between any two whispers of either kind, so they never stack. */
internal const val MIN_WHISPER_GAP_MS = 30_000L

/** Every context except the app opening is driven by something happening in the app. */
val WhisperContext.isEvent: Boolean
    get() = this != WhisperContext.APP_OPENED

/**
 * Whisper Service: manages contextual whisper display
 *
 * Handles display logic, cooldowns, and deterministic selection of whispers.
 * Ensures whispers are rare enough to feel special.
 *
 * **Rules**:
 * - Launch whisper (APP_OPENED): at most one per 15 minutes.
 * - Event whispers (the other five contexts): at most one per 20 minutes, shared by all five.
 * - Any two whispers of either kind: at least 30 seconds apart.
 * - Whispers off or Reduced Motion refuses every context and spends no budget.
 * - Never during: error states, modals, car mode
 *
 * @param clock Wall clock in milliseconds. Injected so the budgets can be tested.
 */
class WhisperService(private val clock: () -> Long = System::currentTimeMillis) {

    private val _currentWhisper = MutableStateFlow<String?>(null)
    val currentWhisper: StateFlow<String?> = _currentWhisper.asStateFlow()

    // null means "never", so a test clock near zero behaves like a fresh install.
    private var lastLaunchAt: Long? = null
    private var lastEventAt: Long? = null
    private var lastAnyAt: Long? = null
    private var sessionSectionStart = 0L
    private var whisperShownInSection = false

    /**
     * Try to show a whisper for the given context.
     * Returns true if whisper was shown, false if blocked by a budget.
     *
     * @param context The whisper context
     * @param enabled Whether whispers are enabled in settings
     * @param reduceMotion Whether reduce motion is enabled
     * @return true if whisper was shown
     */
    fun tryShowWhisper(
        context: WhisperContext,
        enabled: Boolean = true,
        reduceMotion: Boolean = false
    ): Boolean {
        if (!enabled) return false
        if (reduceMotion) return false // Whispers are motion, respect reduce motion

        val now = clock()

        // Never stack two whispers
        if (lastAnyAt.let { it != null && now - it < MIN_WHISPER_GAP_MS }) return false

        if (context.isEvent) {
            if (lastEventAt.let { it != null && now - it < EVENT_WHISPER_COOLDOWN_MS }) return false
        } else {
            if (lastLaunchAt.let { it != null && now - it < LAUNCH_WHISPER_COOLDOWN_MS }) return false
            // Session section applies to the launch whisper only
            if (whisperShownInSection && now - sessionSectionStart < LAUNCH_WHISPER_COOLDOWN_MS) return false
        }

        val whisper = selectWhisper(context, now)
        _currentWhisper.value = whisper
        lastAnyAt = now

        if (context.isEvent) {
            lastEventAt = now
        } else {
            lastLaunchAt = now
            if (!whisperShownInSection || now - sessionSectionStart >= LAUNCH_WHISPER_COOLDOWN_MS) {
                sessionSectionStart = now
                whisperShownInSection = true
            }
        }

        return true
    }

    /**
     * Manually dismiss the current whisper
     */
    fun dismissWhisper() {
        _currentWhisper.value = null
    }

    /**
     * Start a new session section (e.g., screen navigation)
     * Resets the "whisper shown in section" flag after cooldown
     */
    fun newSessionSection() {
        val now = clock()
        if (now - sessionSectionStart >= LAUNCH_WHISPER_COOLDOWN_MS) {
            whisperShownInSection = false
            sessionSectionStart = now
        }
    }

    /**
     * Select a whisper deterministically based on context and seed
     */
    private fun selectWhisper(context: WhisperContext, seed: Long): String {
        val whispers = WhisperCatalog.getWhispers(context)
        val index = Random(seed).nextInt(whispers.size)
        return whispers[index]
    }

    /**
     * Reset the service state (for testing or settings changes)
     */
    fun reset() {
        _currentWhisper.value = null
        lastLaunchAt = null
        lastEventAt = null
        lastAnyAt = null
        sessionSectionStart = 0L
        whisperShownInSection = false
    }

    companion object {
        /**
         * Singleton instance
         * In production, inject via DI
         */
        val instance by lazy { WhisperService() }
    }
}

/**
 * Whisper display configuration
 */
data class WhisperConfig(
    /**
     * How long whispers are displayed (milliseconds)
     */
    val displayDurationMs: Long = 4000L,

    /**
     * Minimum time between whispers (milliseconds)
     */
    val cooldownMs: Long = 15 * 60 * 1000L, // 15 minutes

    /**
     * Whether to show whispers during specific states
     */
    val blockedStates: Set<BlockedState> = setOf(
        BlockedState.ERROR_SCREEN,
        BlockedState.MODAL_DIALOG,
        BlockedState.CAR_MODE
    )
)

/**
 * States where whispers should never appear
 */
enum class BlockedState {
    ERROR_SCREEN,
    MODAL_DIALOG,
    CAR_MODE,
    SETTINGS_SCREEN
}
