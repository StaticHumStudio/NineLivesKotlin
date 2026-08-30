package com.ninelivesaudio.app.ui.settings

import com.ninelivesaudio.app.domain.model.LastSyncRecord
import com.ninelivesaudio.app.domain.model.SyncResult
import com.ninelivesaudio.app.service.SyncAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SyncManager.syncNow() never throws for a failed remote result. A failed
 * fetch is recorded as data (LastSyncRecord), not surfaced as an exception.
 * The Settings screen's manual "Sync Now" used to treat a clean return as
 * proof of success, so an HTTP 500 on /libraries recorded FAILED in
 * diagnostics while the screen said "Sync completed successfully."
 *
 * A second, related gap: syncNow() also returns without doing any work when
 * its own gate fails or another sync already holds its mutex. Reading
 * whatever OLD record happened to exist at that point (rather than a record
 * this attempt actually produced) made a background sync in progress plus a
 * manual "Sync Now" tap report a stale "Sync completed successfully" too.
 * [SyncAttempt] distinguishes an attempt that actually ran from one that was
 * skipped, so the skipped case gets its own honest message instead of
 * borrowing a leftover record.
 */
class SyncNowOutcomeTest {

    @Test
    fun `a successful record reports success`() {
        val outcome = syncNowOutcome(
            SyncAttempt.RAN,
            LastSyncRecord(result = SyncResult.SUCCESS, libraryCount = 2, bookCount = 40, completedAtMs = 1L),
        )
        assertEquals("Sync completed successfully", outcome.successMessage)
        assertNull(outcome.errorMessage)
    }

    @Test
    fun `a failed remote result is reported as an error, not success`() {
        val outcome = syncNowOutcome(
            SyncAttempt.RAN,
            LastSyncRecord(
                result = SyncResult.FAILED,
                libraryCount = 0,
                bookCount = 0,
                failure = "libraries: HTTP 500",
                completedAtMs = 1L,
            ),
        )
        assertNull(outcome.successMessage)
        assertEquals("Sync failed: libraries: HTTP 500", outcome.errorMessage)
    }

    @Test
    fun `a partial remote result is reported as an error`() {
        val outcome = syncNowOutcome(
            SyncAttempt.RAN,
            LastSyncRecord(
                result = SyncResult.PARTIAL,
                libraryCount = 1,
                bookCount = 100,
                failure = "items[Books]: page 3: timeout",
                completedAtMs = 1L,
            ),
        )
        assertNull(outcome.successMessage)
        assertEquals("Sync finished incomplete: items[Books]: page 3: timeout", outcome.errorMessage)
    }

    @Test
    fun `a run with no recorded sync at all falls back to success, matching the prior best-effort behavior`() {
        val outcome = syncNowOutcome(SyncAttempt.RAN, null)
        assertEquals("Sync completed successfully", outcome.successMessage)
        assertNull(outcome.errorMessage)
    }

    // ─── Skipped attempts must not borrow a stale record ───────────────────

    @Test
    fun `a sync skipped because another one is already running reports that honestly, not a stale success`() {
        val staleSuccess = LastSyncRecord(result = SyncResult.SUCCESS, libraryCount = 2, bookCount = 40, completedAtMs = 1L)

        val outcome = syncNowOutcome(SyncAttempt.SKIPPED_BUSY, staleSuccess)

        assertNull(outcome.successMessage)
        assertEquals("Sync already in progress. Try again in a moment.", outcome.errorMessage)
    }

    @Test
    fun `a sync skipped because it isn't ready to run also reports that honestly, not a stale record`() {
        val staleFailure = LastSyncRecord(
            result = SyncResult.FAILED,
            libraryCount = 0,
            bookCount = 0,
            failure = "an old, unrelated failure",
            completedAtMs = 1L,
        )

        val outcome = syncNowOutcome(SyncAttempt.SKIPPED_NOT_READY, staleFailure)

        assertNull(outcome.successMessage)
        assertEquals("Sync did not run. Check your connection and try again.", outcome.errorMessage)
    }

    @Test
    fun `a skipped attempt with no prior record at all still reports skipped, not success`() {
        val outcome = syncNowOutcome(SyncAttempt.SKIPPED_BUSY, null)

        assertNull(outcome.successMessage)
        assertEquals("Sync already in progress. Try again in a moment.", outcome.errorMessage)
    }

    @Test
    fun `a sync whose result was discarded because the server changed reports that, not another server's record`() {
        val otherServersRecord = LastSyncRecord(
            result = SyncResult.SUCCESS,
            libraryCount = 3,
            bookCount = 40,
            failure = null,
            completedAtMs = 1L,
            serverUrl = "https://server-b.example",
        )

        val outcome = syncNowOutcome(SyncAttempt.DISCARDED_SERVER_CHANGED, otherServersRecord)

        assertNull(outcome.successMessage)
        assertEquals("The server changed while syncing. Sync again to refresh the new server.", outcome.errorMessage)
    }

    @Test
    fun `a discarded attempt with no record on the new server does not claim success`() {
        val outcome = syncNowOutcome(SyncAttempt.DISCARDED_SERVER_CHANGED, null)

        assertNull(outcome.successMessage)
        assertEquals("The server changed while syncing. Sync again to refresh the new server.", outcome.errorMessage)
    }
}
