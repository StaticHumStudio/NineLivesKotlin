package com.ninelivesaudio.app.service

import org.junit.Assert.assertEquals
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
}
