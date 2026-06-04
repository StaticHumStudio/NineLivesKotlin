package com.ninelivesaudio.app.ui.navigation

/**
 * Chooses the NavHost start destination from persisted onboarding state.
 * First-run users (never onboarded) see the Welcome screen; everyone else
 * goes straight Home. Kept pure so it can be unit-tested without Compose.
 */
fun startDestinationFor(onboardingComplete: Boolean): String =
    if (onboardingComplete) Routes.HOME else Routes.WELCOME
