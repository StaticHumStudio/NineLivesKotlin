package com.ninelivesaudio.app.ui.onboarding

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingChoiceTest {

    @Test
    fun `choosing server sets mode and completes onboarding`() {
        val result = applyOnboardingChoice(AppSettings(), AppMode.AUDIOBOOKSHELF)
        assertEquals(AppMode.AUDIOBOOKSHELF, result.appMode)
        assertTrue(result.onboardingComplete)
    }

    @Test
    fun `choosing local completes onboarding even when mode is already local`() {
        val current = AppSettings(appMode = AppMode.LOCAL, onboardingComplete = false)
        val result = applyOnboardingChoice(current, AppMode.LOCAL)
        assertEquals(AppMode.LOCAL, result.appMode)
        assertTrue(result.onboardingComplete)
    }

    @Test
    fun `unrelated settings are preserved`() {
        val current = AppSettings(serverUrl = "https://example.com", volume = 0.5)
        val result = applyOnboardingChoice(current, AppMode.LOCAL)
        assertEquals("https://example.com", result.serverUrl)
        assertEquals(0.5, result.volume, 0.0)
    }
}
