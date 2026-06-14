package com.ninelivesaudio.app.ui.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library-load path must not fire a blocking remote sync when there is no
 * network (airplane mode). Switching libraries offline should fall straight
 * through to cached data instead of hanging on a doomed request.
 */
class LibrarySyncGateTest {

    @Test
    fun `remote library syncs when online`() {
        assertTrue(shouldSyncOnLibraryLoad(isLocalLibrary = false, isOnline = true))
    }

    @Test
    fun `remote library does not sync when offline`() {
        // Airplane mode: this is the bug. No network, so no sync attempt.
        assertFalse(shouldSyncOnLibraryLoad(isLocalLibrary = false, isOnline = false))
    }

    @Test
    fun `local library never syncs even when online`() {
        assertFalse(shouldSyncOnLibraryLoad(isLocalLibrary = true, isOnline = true))
    }

    @Test
    fun `local library never syncs when offline`() {
        assertFalse(shouldSyncOnLibraryLoad(isLocalLibrary = true, isOnline = false))
    }
}
