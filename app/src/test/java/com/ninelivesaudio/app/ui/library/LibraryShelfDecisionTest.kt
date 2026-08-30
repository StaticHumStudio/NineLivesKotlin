package com.ninelivesaudio.app.ui.library

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.LastSyncRecord
import com.ninelivesaudio.app.domain.model.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryShelfDecisionTest {

    @Test
    fun `successful empty sync keeps the existing empty state`() {
        assertEquals(
            LibraryShelfDecision.Empty,
            decideLibraryShelf(lastSyncResult = SyncResult.SUCCESS, cachedCount = 0),
        )
    }

    @Test
    fun `failed sync without cached books shows a load error`() {
        assertEquals(
            LibraryShelfDecision.LoadFailed(SyncResult.FAILED),
            decideLibraryShelf(lastSyncResult = SyncResult.FAILED, cachedCount = 0),
        )
    }

    @Test
    fun `incomplete sync without cached books shows a load error`() {
        assertEquals(
            LibraryShelfDecision.LoadFailed(SyncResult.PARTIAL),
            decideLibraryShelf(lastSyncResult = SyncResult.PARTIAL, cachedCount = 0),
        )
    }

    @Test
    fun `failed sync with cached books keeps the shelf and warns`() {
        assertEquals(
            LibraryShelfDecision.ShowShelf(warning = SyncResult.FAILED),
            decideLibraryShelf(lastSyncResult = SyncResult.FAILED, cachedCount = 12),
        )
    }

    @Test
    fun `incomplete sync with cached books keeps the shelf and warns`() {
        assertEquals(
            LibraryShelfDecision.ShowShelf(warning = SyncResult.PARTIAL),
            decideLibraryShelf(lastSyncResult = SyncResult.PARTIAL, cachedCount = 12),
        )
    }

    @Test
    fun `successful sync with cached books keeps the shelf without a warning`() {
        assertEquals(
            LibraryShelfDecision.ShowShelf(warning = null),
            decideLibraryShelf(lastSyncResult = SyncResult.SUCCESS, cachedCount = 12),
        )
    }

    @Test
    fun `no recorded sync keeps the existing empty state`() {
        assertEquals(
            LibraryShelfDecision.Empty,
            decideLibraryShelf(lastSyncResult = null, cachedCount = 0),
        )
    }

    @Test
    fun `server mode exposes the persisted sync result`() {
        val settings = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            lastSync = LastSyncRecord(
                result = SyncResult.FAILED,
                libraryCount = 0,
                bookCount = 0,
                failure = "offline",
                completedAtMs = 123L,
            ),
        )

        assertEquals(SyncResult.FAILED, librarySyncResult(settings))
    }

    @Test
    fun `local mode ignores a persisted server sync result`() {
        val settings = AppSettings(
            appMode = AppMode.LOCAL,
            lastSync = LastSyncRecord(
                result = SyncResult.FAILED,
                libraryCount = 0,
                bookCount = 0,
                failure = "offline",
                completedAtMs = 123L,
            ),
        )

        assertEquals(null, librarySyncResult(settings))
    }

    // ─── Server-scoped sync records ────────────────────────────────────────
    //
    // LastSyncRecord used to carry no server identity, so a failure recorded
    // against server A survived a switch to server B. Launching offline
    // against B rendered A's failure banner over B's (actually untested)
    // empty shelf.

    @Test
    fun `a sync record from a different server is not surfaced as the current result`() {
        val settings = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            serverUrl = "https://b.example.com",
            lastSync = LastSyncRecord(
                result = SyncResult.FAILED,
                libraryCount = 0,
                bookCount = 0,
                failure = "offline",
                serverUrl = "https://a.example.com",
                completedAtMs = 123L,
            ),
        )

        assertEquals(null, librarySyncResult(settings))
    }

    @Test
    fun `a sync record from the current server is surfaced`() {
        val settings = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            serverUrl = "https://a.example.com",
            lastSync = LastSyncRecord(
                result = SyncResult.FAILED,
                libraryCount = 0,
                bookCount = 0,
                failure = "offline",
                serverUrl = "https://a.example.com",
                completedAtMs = 123L,
            ),
        )

        assertEquals(SyncResult.FAILED, librarySyncResult(settings))
    }
}
