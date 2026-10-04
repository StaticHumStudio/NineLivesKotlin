package com.ninelivesaudio.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tabs pop to a route that has to be on the back stack, or Navigation
 * ignores the pop and every tap pushes a new screen. On first run the graph's
 * start destination is Welcome, which onboarding removes, so tab taps piled
 * up (#58). The tab root is Home on every launch, and onboarding leaves Home
 * under whatever screen it lands on.
 */
class TabNavigationTest {

    @Test
    fun `the tab root is where a normal launch starts`() {
        assertEquals(startDestinationFor(onboardingComplete = true), TAB_ROOT_ROUTE)
    }

    @Test
    fun `onboarding leaves Home under the screen it lands on`() {
        assertEquals(listOf(Routes.HOME, Routes.SETTINGS), postOnboardingRoutes(Routes.SETTINGS))
    }

    @Test
    fun `onboarding that lands on Home leaves just Home`() {
        assertEquals(listOf(Routes.HOME), postOnboardingRoutes(Routes.HOME))
    }

    @Test
    fun `the first-run stack never keeps Welcome`() {
        listOf(Routes.HOME, Routes.LIBRARY, Routes.SETTINGS).forEach { target ->
            assertEquals(false, Routes.WELCOME in postOnboardingRoutes(target))
            assertEquals(TAB_ROOT_ROUTE, postOnboardingRoutes(target).first())
        }
    }
}
