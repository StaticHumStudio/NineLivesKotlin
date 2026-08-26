package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.remote.RemoteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The library half of a sync has to say which of the four things happened:
 * the library list failed, an item fetch failed, everything worked and found
 * books, or everything worked and found nothing. Collapsing the last two into
 * each other is what made "nothing shows up" impossible to diagnose from a
 * bug report.
 */
class SyncReportBuildTest {

    @Test
    fun `a failed library list is reported as a failure, not as an empty shelf`() {
        val report = buildSyncReport(
            libraries = RemoteResult.Failed("HTTP 500"),
            items = emptyList(),
            ageMinutes = 4,
        )
        assertEquals("libraries: HTTP 500", report.failure)
        assertEquals(0, report.libraryCount)
        assertEquals(0, report.bookCount)
        assertEquals(4L, report.ageMinutes)
    }

    @Test
    fun `a clean sync sums the books across libraries`() {
        val report = buildSyncReport(
            libraries = RemoteResult.Ok(listOf("Books", "Podcasts")),
            items = listOf(RemoteResult.Ok(200), RemoteResult.Ok(231)),
            ageMinutes = null,
        )
        assertNull(report.failure)
        assertEquals(2, report.libraryCount)
        assertEquals(431, report.bookCount)
    }

    @Test
    fun `an empty shelf is a clean sync with zero books`() {
        val report = buildSyncReport(
            libraries = RemoteResult.Ok(listOf("Books")),
            items = listOf(RemoteResult.Ok(0)),
            ageMinutes = null,
        )
        assertNull(report.failure)
        assertEquals(1, report.libraryCount)
        assertEquals(0, report.bookCount)
    }

    @Test
    fun `a failed item fetch names the library and keeps the counts that worked`() {
        val report = buildSyncReport(
            libraries = RemoteResult.Ok(listOf("Books", "Podcasts")),
            items = listOf(RemoteResult.Ok(200), RemoteResult.Failed("timeout")),
            ageMinutes = null,
        )
        assertEquals("items[Podcasts]: timeout", report.failure)
        assertEquals(2, report.libraryCount)
        assertEquals(200, report.bookCount)
    }

    @Test
    fun `a part-way pagination failure keeps the books it got and still names the failure`() {
        // A large library paginates. Page 0 lands, page 3 times out. The old
        // code kept the partial pages and reported nothing, so a shelf that
        // silently stopped at 100 books looked like a shelf with 100 books.
        val report = buildSyncReport(
            libraries = RemoteResult.Ok(listOf("Books")),
            items = listOf(RemoteResult.Partial(100, "page 3: timeout")),
            ageMinutes = null,
        )
        assertEquals("items[Books]: page 3: timeout", report.failure)
        assertEquals(100, report.bookCount)
    }

    @Test
    fun `no libraries at all is a clean sync, which is itself the finding`() {
        // An Audiobookshelf account with no libraries visible to this user.
        // Distinguishable from a 401, which lands in the Failed branch.
        val report = buildSyncReport(
            libraries = RemoteResult.Ok(emptyList()),
            items = emptyList(),
            ageMinutes = 0,
        )
        assertNull(report.failure)
        assertEquals(0, report.libraryCount)
        assertEquals(0L, report.ageMinutes)
    }
}
