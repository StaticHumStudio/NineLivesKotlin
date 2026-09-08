package com.ninelivesaudio.app.ui.onboarding

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings

/** The screen shown immediately after a persisted first-run source choice. */
enum class OnboardingDestination {
    SETTINGS,
}

/**
 * Applies a first-run source choice: sets the chosen [mode] and marks onboarding
 * complete. Always sets onboardingComplete = true, even when [mode] already
 * matches the current mode, so picking the default (LOCAL) is never a no-op.
 */
fun applyOnboardingChoice(current: AppSettings, mode: AppMode): AppSettings =
    current.copy(appMode = mode, onboardingComplete = true)

/** Both source choices go to Settings so the user can complete their setup. */
fun onboardingDestinationFor(mode: AppMode): OnboardingDestination = when (mode) {
    AppMode.LOCAL -> OnboardingDestination.SETTINGS
    AppMode.AUDIOBOOKSHELF -> OnboardingDestination.SETTINGS
}
