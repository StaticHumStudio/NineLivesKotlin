package com.ninelivesaudio.app.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings library selector must stay reachable offline. Libraries are
 * cached locally, and switching only persists the selection (no network), so
 * the picker should show whenever there is more than one cached library,
 * regardless of connectivity. Previously it was gated on isConnected, which
 * hid it entirely in airplane mode.
 */
class LibrarySelectorVisibilityTest {

    @Test
    fun `selector shown with multiple cached libraries`() {
        assertTrue(shouldShowLibrarySelector(libraryCount = 2))
    }

    @Test
    fun `selector shown offline when multiple libraries are cached`() {
        // Connectivity is irrelevant: the only input is how many libraries exist.
        assertTrue(shouldShowLibrarySelector(libraryCount = 3))
    }

    @Test
    fun `selector hidden with a single library`() {
        assertFalse(shouldShowLibrarySelector(libraryCount = 1))
    }

    @Test
    fun `selector hidden with no libraries`() {
        assertFalse(shouldShowLibrarySelector(libraryCount = 0))
    }
}
