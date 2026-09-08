package com.ninelivesaudio.app.ui.navigation

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.ui.onboarding.applyOnboardingChoice
import org.junit.Assert.assertEquals
import org.junit.Test

class StartDestinationTest {

    @Test
    fun `not onboarded starts at welcome`() {
        assertEquals(Routes.WELCOME, startDestinationFor(onboardingComplete = false))
    }

    @Test
    fun `onboarded starts at home`() {
        assertEquals(Routes.HOME, startDestinationFor(onboardingComplete = true))
    }

    @Test
    fun `a later process starts home after the persisted server choice`() {
        val persisted = applyOnboardingChoice(AppSettings(), AppMode.AUDIOBOOKSHELF)
        assertEquals(Routes.HOME, startDestinationFor(persisted.onboardingComplete))
    }
}
