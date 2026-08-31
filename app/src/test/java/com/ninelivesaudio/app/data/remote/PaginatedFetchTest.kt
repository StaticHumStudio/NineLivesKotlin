package com.ninelivesaudio.app.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * GitHub codex review of PR #30, finding B: getLibraryItems()'s pagination
 * loop labeled EVERY termination Ok, including a page that came back empty
 * or shorter than the requested limit while the server's reported `total`
 * said there was more (a server-side page cap, a transient inconsistency).
 * Now that a complete Ok prunes the cache, that false Ok could erase the
 * rest of a real shelf while reporting success.
 *
 * runPaginatedFetch() is the actual pagination loop ApiService.getLibraryItems()
 * runs (via a fetchPage lambda standing in for the real Retrofit call), so
 * these tests pin the real termination logic directly, not a stand-in.
 */
class PaginatedFetchTest {

    private fun unreachablePage(message: String): PageOutcome<String> {
        fail(message)
        return PageOutcome.Stopped("unreachable")
    }

    @Test
    fun `true completion via reaching the reported total is Ok`() = runBlocking {
        val result = runPaginatedFetch<String>(limit = 2) { page ->
            when (page) {
                0 -> PageOutcome.Page(listOf("a", "b"), total = 3)
                1 -> PageOutcome.Page(listOf("c"), total = 3)
                else -> unreachablePage("must not fetch a page beyond the reported total")
            }
        }

        assertEquals(RemoteResult.Ok(listOf("a", "b", "c")), result)
    }

    @Test
    fun `an empty first page with no reported total is a genuinely empty shelf, still Ok`() = runBlocking {
        val result = runPaginatedFetch<String>(limit = 100) { PageOutcome.Page(emptyList(), total = 0) }

        assertEquals(RemoteResult.Ok(emptyList<String>()), result)
    }

    @Test
    fun `a short page while the reported total says more exist is Partial, not Ok`() = runBlocking {
        // The exact scenario from finding B: a page comes back shorter than
        // the requested limit (a page cap, or a transient inconsistency)
        // while body.total still exceeds what was collected.
        val result = runPaginatedFetch<String>(limit = 10) { page ->
            when (page) {
                0 -> PageOutcome.Page(listOf("a", "b", "c"), total = 20) // short of `limit`, short of `total`
                else -> unreachablePage("a short page under `limit` is the loop's own stop signal")
            }
        }

        assertEquals(RemoteResult.Partial(listOf("a", "b", "c"), "page 0: got 3 of 20 reported"), result)
    }

    @Test
    fun `an empty page while the reported total says more exist is Partial, not Ok`() = runBlocking {
        // Page 0 returns exactly `limit` items so the loop advances to page
        // 1 instead of stopping early on a short page.
        val result = runPaginatedFetch<String>(limit = 2) { page ->
            when (page) {
                0 -> PageOutcome.Page(listOf("a", "b"), total = 20)
                1 -> PageOutcome.Page(emptyList(), total = 20) // server ran dry early
                else -> unreachablePage("must stop once a page comes back empty")
            }
        }

        assertEquals(RemoteResult.Partial(listOf("a", "b"), "page 1: got 2 of 20 reported"), result)
    }

    @Test
    fun `a short page that already reached the reported total is still Ok`() = runBlocking {
        // Reaching total and being short of `limit` can happen on the same
        // page (the last page of an exact-multiple shelf isn't guaranteed).
        // Reaching total wins.
        val result = runPaginatedFetch<String>(limit = 10) { PageOutcome.Page(listOf("a", "b", "c"), total = 3) }

        assertEquals(RemoteResult.Ok(listOf("a", "b", "c")), result)
    }

    @Test
    fun `an HTTP failure mid-pagination stops short with whatever was already collected`() = runBlocking {
        val result = runPaginatedFetch<String>(limit = 2) { page ->
            when (page) {
                0 -> PageOutcome.Page(listOf("a", "b"), total = 20)
                1 -> PageOutcome.Stopped("page 1: HTTP 500")
                else -> unreachablePage("must not fetch past the failure")
            }
        }

        assertEquals(RemoteResult.Partial(listOf("a", "b"), "page 1: HTTP 500"), result)
    }

    @Test
    fun `an HTTP failure on the very first page with nothing collected is a plain failure`() = runBlocking {
        val result = runPaginatedFetch<String>(limit = 100) { PageOutcome.Stopped("page 0: HTTP 500") }

        assertEquals(RemoteResult.Failed("page 0: HTTP 500"), result)
    }

    @Test
    fun `an exception mid-fetch stops short, reports honestly, and calls the failure hook`() = runBlocking {
        var reportedPage = -1
        var reportedException: Exception? = null

        val result = runPaginatedFetch<String>(
            limit = 2,
            onPageFailure = { page, e -> reportedPage = page; reportedException = e },
        ) { page ->
            when (page) {
                0 -> PageOutcome.Page(listOf("a", "b"), total = 20)
                1 -> throw IllegalStateException("boom")
                else -> unreachablePage("must not fetch past the exception")
            }
        }

        assertEquals(RemoteResult.Partial(listOf("a", "b"), "page 1: IllegalStateException: boom"), result)
        assertEquals(1, reportedPage)
        assertEquals("boom", reportedException?.message)
    }

    @Test
    fun `cancellation during pagination is rethrown, not swallowed`() = runBlocking {
        val cancellation = CancellationException("stop")

        try {
            runPaginatedFetch<String>(limit = 10) { throw cancellation }
            fail("expected cancellation to propagate")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
    }
}
