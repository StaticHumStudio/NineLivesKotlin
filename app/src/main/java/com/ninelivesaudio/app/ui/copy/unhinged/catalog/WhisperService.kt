package com.ninelivesaudio.app.ui.copy.unhinged.catalog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * Whisper Service — Manages Contextual Whisper Display
 *
 * Handles display logic, cooldowns, and deterministic selection of whispers.
 * Ensures whispers are rare enough to feel special.
 *
 * **Rules**:
 * - Max 1 whisper per "session section" (screen nav or 15-minute window)
 * - Cooldown timer between whispers
 * - Never during: error states, modals, car mode
 * - Deterministic selection (same seed = same sequence)
 */
class WhisperService {

    private val _currentWhisper = MutableStateFlow<String?>(null)
    val currentWhisper: StateFlow<String?> = _currentWhisper.asStateFlow()

    private var lastWhisperTime = 0L
    private var sessionSectionStart = 0L
    private var whisperShownInSection = false
    private val random = Random(System.currentTimeMillis())

    /**
     * Whisper configuration
     */
    private val cooldownMs = 15 * 60 * 1000L // 15 minutes
    private val displayDurationMs = 4000L // 4 seconds

    /**
     * Try to show a whisper for the given context.
     * Returns true if whisper was shown, false if blocked by cooldown.
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

        val now = System.currentTimeMillis()

        // Check cooldown
        if (now - lastWhisperTime < cooldownMs) {
            return false
        }

        // Check session section
        if (whisperShownInSection && now - sessionSectionStart < cooldownMs) {
            return false
        }

        // Select whisper
        val whisper = selectWhisper(context, now)

        // Show whisper
        _currentWhisper.value = whisper
        lastWhisperTime = now

        // Update session section tracking
        if (!whisperShownInSection || now - sessionSectionStart >= cooldownMs) {
            sessionSectionStart = now
            whisperShownInSection = true
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
        val now = System.currentTimeMillis()
        if (now - sessionSectionStart >= cooldownMs) {
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
        lastWhisperTime = 0L
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
