package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.LastSyncRecord
import com.ninelivesaudio.app.domain.model.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A bug report that says "it says it's syncing but nothing shows" is
 * undiagnosable without knowing what the last sync actually produced. These
 * cases are the ones that have to be distinguishable from each other:
 * never ran, ran and failed, ran clean and found nothing, ran clean and
 * found books.
 */
class SyncReportTest {

    @Test
    fun `a sync that never ran says so`() {
        assertEquals("never", describeLastSync(null))
    }

    @Test
    fun `a clean sync reports what it found`() {
        val report = SyncReport(libraryCount = 2, bookCount = 431, ageMinutes = 3)
        assertEquals("2 libraries, 431 books, 3m ago", describeLastSync(report))
        assertEquals(emptyList<String>(), report.failedLibraryIds)
    }

    @Test
    fun `a library that returned zero books is not reported as a failure`() {
        // The shape that matters: the fetch worked, the shelf came back empty.
        // Reporting this as "failed" would send the next debugging pass at the
        // network instead of at the library selection.
        val report = SyncReport(libraryCount = 2, bookCount = 0, ageMinutes = 1)
        assertEquals("2 libraries, 0 books, 1m ago", describeLastSync(report))
    }

    @Test
    fun `a failed sync names the reason instead of reporting empty counts`() {
        // Before this, a 500 and an empty shelf both rendered as zero books.
        val report = SyncReport(
            libraryCount = 0,
            bookCount = 0,
            failure = "libraries: HTTP 500",
            ageMinutes = 12,
        )
        assertEquals("FAILED (libraries: HTTP 500), 12m ago", describeLastSync(report))
    }

    @Test
    fun `a sync with no recorded age omits the age`() {
        assertEquals("1 libraries, 9 books", describeLastSync(SyncReport(1, 9)))
    }

    @Test
    fun `a snapshot ages against the current clock`() {
        val snapshot = SyncSnapshot(SyncReport(2, 431), completedAtMs = 1_000_000L)
        assertEquals(5L, snapshot.atAge(nowMs = 1_000_000L + 5 * 60_000L)?.ageMinutes)
    }

    @Test
    fun `a clock that jumped backwards reports zero rather than a negative age`() {
        // Device clock changes, timezone shifts, NTP corrections. "-3m ago" in
        // a support email is noise that costs a round trip to explain.
        val snapshot = SyncSnapshot(SyncReport(2, 431), completedAtMs = 2_000_000L)
        assertEquals(0L, snapshot.atAge(nowMs = 1_000_000L)?.ageMinutes)
    }

    @Test
    fun `no snapshot ages to nothing`() {
        assertEquals(null, (null as SyncSnapshot?).atAge(nowMs = 1_000_000L))
    }

    @Test
    fun `persisted sync record keeps the outcome counts failure and completion time`() {
        val current = AppSettings(serverUrl = "https://server.example")
        val updated = current.withLastSyncIfServerUnchanged(
            report = SyncReport(
                libraryCount = 2,
                bookCount = 200,
                failure = "items[Books]: timeout",
                result = SyncResult.PARTIAL,
                failedLibraryIds = listOf("books"),
            ),
            completedAtMs = 123_456_789L,
            serverUrlAtStart = "https://server.example",
        )

        assertEquals("https://server.example", updated.serverUrl)
        assertEquals(
            LastSyncRecord(
                result = SyncResult.PARTIAL,
                libraryCount = 2,
                bookCount = 200,
                failure = "items[Books]: timeout",
                completedAtMs = 123_456_789L,
                outcomeSequence = 1L,
                failedLibraryIds = listOf("books"),
                // Stamped with the server this sync actually ran against, so
                // a later switch to a different server can tell this record
                // isn't about it. See LibraryShelfDecisionTest and
                // LibraryRemoteRefreshTest for the read side of that check.
                serverUrl = "https://server.example",
            ),
            updated.lastSync,
        )
        assertEquals(1L, updated.lastSyncOutcomeSequence)
    }

    @Test
    fun `a sync completion is discarded when the configured server changed in flight`() {
        val currentServerRecord = LastSyncRecord(
            result = SyncResult.SUCCESS,
            libraryCount = 1,
            bookCount = 10,
            completedAtMs = 10L,
            serverUrl = "https://b.example",
        )
        val current = AppSettings(
            serverUrl = "https://b.example",
            lastSync = currentServerRecord,
        )

        val updated = current.withLastSyncIfServerUnchanged(
            report = SyncReport(
                libraryCount = 0,
                bookCount = 0,
                failure = "server unreachable",
                result = SyncResult.FAILED,
            ),
            completedAtMs = 20L,
            serverUrlAtStart = "https://a.example",
        )

        assertEquals(current, updated)
    }

    @Test
    fun `a later recorded outcome replaces an earlier outcome when the clock rolls back`() {
        val current = AppSettings(
            serverUrl = "https://server.example",
            lastSync = LastSyncRecord(
                result = SyncResult.FAILED,
                libraryCount = 0,
                bookCount = 0,
                failure = "background sync failed",
                completedAtMs = 200L,
                outcomeSequence = 1L,
                serverUrl = "https://server.example",
            ),
            lastSyncOutcomeSequence = 1L,
        )

        val updated = current.withLastSyncIfServerUnchanged(
            report = SyncReport(libraryCount = 1, bookCount = 10, result = SyncResult.SUCCESS),
            completedAtMs = 100L,
            serverUrlAtStart = "https://server.example",
        )

        assertEquals(SyncResult.SUCCESS, updated.lastSync?.result)
        assertEquals(100L, updated.lastSync?.completedAtMs)
        assertEquals(2L, updated.lastSync?.outcomeSequence)
        assertEquals(emptyList<String>(), updated.lastSync?.failedLibraryIds)
        assertEquals(2L, updated.lastSyncOutcomeSequence)
    }

    @Test
    fun `a later recorded outcome keeps normal forward time behavior`() {
        val current = AppSettings(
            serverUrl = "https://server.example",
            lastSync = LastSyncRecord(
                result = SyncResult.FAILED,
                libraryCount = 0,
                bookCount = 0,
                failure = "background sync failed",
                completedAtMs = 100L,
                outcomeSequence = 1L,
                serverUrl = "https://server.example",
            ),
            lastSyncOutcomeSequence = 1L,
        )

        val updated = current.withLastSyncIfServerUnchanged(
            report = SyncReport(libraryCount = 1, bookCount = 10, result = SyncResult.SUCCESS),
            completedAtMs = 200L,
            serverUrlAtStart = "https://server.example",
        )

        assertEquals(SyncResult.SUCCESS, updated.lastSync?.result)
        assertEquals(200L, updated.lastSync?.completedAtMs)
        assertEquals(2L, updated.lastSync?.outcomeSequence)
        assertEquals(2L, updated.lastSyncOutcomeSequence)
    }

    @Test
    fun `diagnostic snapshot is hidden when it belongs to another server`() {
        val settings = AppSettings(
            serverUrl = "https://b.example",
            lastSync = LastSyncRecord(
                result = SyncResult.FAILED,
                libraryCount = 0,
                bookCount = 0,
                failure = "offline",
                completedAtMs = 10L,
                serverUrl = "https://a.example",
            ),
        )

        assertNull(settings.syncSnapshotForCurrentServer())
    }

    @Test
    fun `diagnostic snapshot is exposed for the configured server`() {
        val settings = AppSettings(
            serverUrl = "https://a.example",
            lastSync = LastSyncRecord(
                result = SyncResult.PARTIAL,
                libraryCount = 1,
                bookCount = 25,
                failure = "page 2: timeout",
                completedAtMs = 10L,
                serverUrl = "https://a.example",
            ),
        )

        assertEquals(SyncResult.PARTIAL, settings.syncSnapshotForCurrentServer()?.report?.result)
    }
}
