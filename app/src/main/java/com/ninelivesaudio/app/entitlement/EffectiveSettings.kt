package com.ninelivesaudio.app.entitlement

import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.ThemeMode

/**
 * Clamps stored settings down to what the current entitlement allows.
 *
 * ## Why a layer instead of gating at each call site
 *
 * Gating in the UI alone leaves every stored premium value live in the engine.
 * A user who set 2.0x speed while unlocked, then lost entitlement, would keep
 * hearing 2.0x with a greyed control that says 1.0x. The layer makes the engine
 * and the UI agree without either one lying.
 *
 * ## Two rules that are not negotiable
 *
 * **Stored values are never wiped.** This returns a normalized COPY. What the
 * user chose stays on disk untouched, so rebuying restores their setup exactly
 * rather than resetting them to defaults. A downgrade is not a factory reset.
 *
 * **No entitlement transition may move playback position.** Nothing in here
 * touches position, and nothing that consumes it may either. Losing someone's
 * place is the single most-cited freemium rage-quit in the competitor review
 * corpus.
 *
 * Pure and Android-free on purpose, same as [EntitlementResolver] and
 * [PurchaseEvaluator]. This decides what every paying user does and does not
 * get, so it is testable without a device.
 */
object EffectiveSettings {

    /** Free tier plays at normal speed only. */
    const val FREE_SPEED = 1.0

    /** Free tier gets one sleep-timer preset, and this is it. */
    const val FREE_SLEEP_TIMER_MINUTES = 30

    fun normalize(stored: AppSettings, entitlement: EntitlementState): AppSettings {
        if (entitlement.isUnlocked) return stored

        return stored.copy(
            // Speed. The stored value survives for when they unlock.
            playbackSpeed = FREE_SPEED,

            // Auto-rewind on resume. Defaults to true in AppSettings, so this is
            // a real downgrade rather than a no-op: free resumes exactly where
            // playback stopped.
            autoRewindEnabled = false,

            // Equalizer and volume boost.
            eqEnabled = false,
            volumeBoostGain = 0,

            // Silence skipping.
            skipSilenceEnabled = false,

            // Theme. The stored choice is untouched, so unlocking restores the
            // user's palette rather than leaving them on NOIR wondering where it
            // went. NOIR is also the default, so a free install never sees a
            // theme it cannot pick again.
            themeMode = FreeTier.THEME,

            // Sleep timer extras. All three default premium-on, so all three
            // have to be normalized off rather than merely hidden.
            sleepTimerMotionEnabled = false,
            sleepTimerShakeResetEnabled = false,
            sleepTimerRewindSeconds = 0,
        )
    }
}
