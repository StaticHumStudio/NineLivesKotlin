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

    // ─── The selected library's own outcome, not an unrelated one's ────────
    //
    // GitHub codex review of PR #30, finding A: lastSyncResult is ONE
    // aggregate for the whole account -- SyncManager's background sync folds
    // every library's item fetch into it, and the first failure wins. If
    // library B times out while library A (currently selected, genuinely
    // empty) synced fine, the aggregate reads FAILED and decideLibraryShelf
    // used to apply that to A's empty shelf, rendering a load-failed state
    // for a library that was never actually a problem.
    //
    // selectedLibraryFetchResult is LibraryViewModel's own record of the
    // CURRENTLY SELECTED library's most recent direct fetch (set by
    // loadAudioBooks() from the same RemoteResult it already fetches for
    // that library specifically) -- when present, it is authoritative for
    // this shelf and the stale/unrelated aggregate is not consulted.

    @Test
    fun `an unrelated aggregate failure does not fail a selected library that synced fine`() {
        assertEquals(
            LibraryShelfDecision.Empty,
            decideLibraryShelf(
                lastSyncResult = SyncResult.FAILED, // some OTHER library's item fetch timed out
                selectedLibraryFetchResult = SyncResult.SUCCESS, // but the selected one's own fetch succeeded
                cachedCount = 0, // and it is genuinely, confirmedly empty
            ),
        )
    }

    @Test
    fun `a selected library whose own fetch failed still renders the failed state`() {
        // The aggregate is irrelevant here -- the selected library's own
        // fetch is what failed, and that must still surface.
        assertEquals(
            LibraryShelfDecision.LoadFailed(SyncResult.FAILED),
            decideLibraryShelf(
                lastSyncResult = SyncResult.SUCCESS, // the account-wide aggregate looks fine
                selectedLibraryFetchResult = SyncResult.FAILED, // but THIS library's own fetch failed
                cachedCount = 0,
            ),
        )
    }

    @Test
    fun `a later sequence aggregate failure replaces an earlier selected success after clock rollback`() {
        assertEquals(
            LibraryShelfDecision.LoadFailed(SyncResult.FAILED),
            decideLibraryShelf(
                lastSyncResult = SyncResult.FAILED,
                lastSyncSequence = 2L,
                selectedLibraryFetchResult = SyncResult.SUCCESS,
                selectedLibraryFetchSequence = 1L,
                cachedCount = 0,
            ),
        )
    }

    @Test
    fun `a later sequence aggregate success replaces an earlier selected failure after clock rollback`() {
        assertEquals(
            LibraryShelfDecision.Empty,
            decideLibraryShelf(
                lastSyncResult = SyncResult.SUCCESS,
                lastSyncSequence = 2L,
                selectedLibraryFetchResult = SyncResult.FAILED,
                selectedLibraryFetchSequence = 1L,
                cachedCount = 0,
            ),
        )
    }

    @Test
    fun `an unsequenced selected result cannot mask a persisted aggregate`() {
        assertEquals(
            LibraryShelfDecision.LoadFailed(SyncResult.FAILED),
            decideLibraryShelf(
                lastSyncResult = SyncResult.FAILED,
                lastSyncSequence = 10L,
                selectedLibraryFetchResult = SyncResult.SUCCESS,
                selectedLibraryFetchSequence = null,
                cachedCount = 0,
            ),
        )
    }

    @Test
    fun `a selected library's own partial fetch still warns even with cached books`() {
        assertEquals(
            LibraryShelfDecision.ShowShelf(warning = SyncResult.PARTIAL),
            decideLibraryShelf(
                lastSyncResult = SyncResult.SUCCESS,
                selectedLibraryFetchResult = SyncResult.PARTIAL,
                cachedCount = 5,
            ),
        )
    }

    @Test
    fun `with no per-library signal yet, the aggregate is still consulted as before`() {
        // First launch, or a load that never ran its own live fetch (e.g.
        // offline) -- selectedLibraryFetchResult defaults to null, and the
        // aggregate is the only signal available, matching today's behavior.
        assertEquals(
            LibraryShelfDecision.LoadFailed(SyncResult.FAILED),
            decideLibraryShelf(lastSyncResult = SyncResult.FAILED, cachedCount = 0),
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
