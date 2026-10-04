package com.ninelivesaudio.app.ui.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library-load path must not fire a blocking remote sync when there is no
 * network (airplane mode). Switching libraries offline should fall straight
 * through to cached data instead of hanging on a doomed request.
 *
 * This gate governs both remote sync sites in the load path: the per-library
 * items sync (LibraryViewModel.loadAudioBooks) and the library-list sync
 * (LibraryViewModel.loadAudiobookshelfLibraries). The library list is always
 * remote, so it passes isLocalLibrary = false and the gate reduces to isOnline.
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

    @Test
    fun `opening the Library right after the app's own check sends nothing`() {
        assertTrue(
            libraryLoadSkipsNetwork(explicit = false, checkDue = false, savedLibraryIsSynced = true, savedLibraryHasBooks = true),
        )
    }

    @Test
    fun `opening the Library checks when the app's check is two minutes old or more`() {
        assertFalse(
            libraryLoadSkipsNetwork(explicit = false, checkDue = true, savedLibraryIsSynced = true, savedLibraryHasBooks = true),
        )
    }

    @Test
    fun `pull to refresh always goes to the server`() {
        assertFalse(
            libraryLoadSkipsNetwork(explicit = true, checkDue = false, savedLibraryIsSynced = true, savedLibraryHasBooks = true),
        )
    }

    @Test
    fun `a library the app's check does not cover still loads from the server`() {
        // Podcast libraries are not checked in the background.
        assertFalse(
            libraryLoadSkipsNetwork(explicit = false, checkDue = false, savedLibraryIsSynced = false, savedLibraryHasBooks = true),
        )
        // Nothing cached yet (fresh sign-in, new server).
        assertFalse(
            libraryLoadSkipsNetwork(explicit = false, checkDue = false, savedLibraryIsSynced = true, savedLibraryHasBooks = false),
        )
    }
}
