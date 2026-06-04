package com.ninelivesaudio.app.ui.navigation

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
}
