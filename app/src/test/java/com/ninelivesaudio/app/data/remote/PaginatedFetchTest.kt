package com.ninelivesaudio.app.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
        val result = runPaginatedFetch<String>(limit = 2, maxPages = 100) { page ->
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
        val result = runPaginatedFetch<String>(limit = 100, maxPages = 100) { PageOutcome.Page(emptyList(), total = 0) }

        assertEquals(RemoteResult.Ok(emptyList<String>()), result)
    }

    @Test
    fun `a full page with no reported total keeps fetching until a natural terminator`() = runBlocking {
        val pagesFetched = mutableListOf<Int>()

        val result = runPaginatedFetch<String>(limit = 2, maxPages = 100) { page ->
            pagesFetched += page
            when (page) {
                0 -> PageOutcome.Page(listOf("a", "b"), total = 0)
                1 -> PageOutcome.Page(listOf("c"), total = 0)
                else -> unreachablePage("the short second page must end this fetch")
            }
        }

        assertEquals(RemoteResult.Ok(listOf("a", "b", "c")), result)
        assertEquals(listOf(0, 1), pagesFetched)
    }

    @Test
    fun `a short page while the reported total says more exist is Partial, not Ok`() = runBlocking {
        // The exact scenario from finding B: a page comes back shorter than
        // the requested limit (a page cap, or a transient inconsistency)
        // while body.total still exceeds what was collected.
        val result = runPaginatedFetch<String>(limit = 10, maxPages = 100) { page ->
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
        val result = runPaginatedFetch<String>(limit = 2, maxPages = 100) { page ->
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
        val result = runPaginatedFetch<String>(limit = 10, maxPages = 100) { PageOutcome.Page(listOf("a", "b", "c"), total = 3) }

        assertEquals(RemoteResult.Ok(listOf("a", "b", "c")), result)
    }

    @Test
    fun `an HTTP failure mid-pagination stops short with whatever was already collected`() = runBlocking {
        val result = runPaginatedFetch<String>(limit = 2, maxPages = 100) { page ->
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
        val result = runPaginatedFetch<String>(limit = 100, maxPages = 100) { PageOutcome.Stopped("page 0: HTTP 500") }

        assertEquals(RemoteResult.Failed("page 0: HTTP 500"), result)
    }

    @Test
    fun `an exception mid-fetch stops short, reports honestly, and calls the failure hook`() = runBlocking {
        var reportedPage = -1
        var reportedException: Exception? = null

        val result = runPaginatedFetch<String>(
            limit = 2,
            maxPages = 100,
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
            runPaginatedFetch<String>(limit = 10, maxPages = 100) { throw cancellation }
            fail("expected cancellation to propagate")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
    }

    @Test
    fun `151 rows across 50 row pages complete without a history cap`() = runBlocking {
        val result = runPaginatedFetch<Int>(limit = 50, maxPages = 100) { page ->
            val values = (page * 50 until minOf((page + 1) * 50, 151)).toList()
            PageOutcome.Page(values, total = 151)
        }

        assertEquals(RemoteResult.Ok((0 until 151).toList()), result)
    }

    @Test
    fun `1001 rows across 50 row pages complete without a history cap`() = runBlocking {
        val result = runPaginatedFetch<Int>(limit = 50, maxPages = 100) { page ->
            val values = (page * 50 until minOf((page + 1) * 50, 1_001)).toList()
            PageOutcome.Page(values, total = 1_001)
        }

        assertEquals(RemoteResult.Ok((0 until 1_001).toList()), result)
    }

    @Test
    fun `page two missing body preserves page one as Partial`() = runBlocking {
        val result = runPaginatedFetch<String>(limit = 2, maxPages = 100) { page ->
            when (page) {
                0 -> PageOutcome.Page(listOf("a", "b"), total = 3)
                1 -> PageOutcome.Stopped("page 1: empty body")
                else -> unreachablePage("must stop on missing body")
            }
        }

        assertEquals(RemoteResult.Partial(listOf("a", "b"), "page 1: empty body"), result)
    }

    @Test
    fun `page two auth change preserves page one as Partial`() = runBlocking {
        val result = runPaginatedFetch<String>(limit = 2, maxPages = 100) { page ->
            when (page) {
                0 -> PageOutcome.Page(listOf("a", "b"), total = 3)
                1 -> PageOutcome.Stopped("page 1: auth session changed")
                else -> unreachablePage("must stop on auth change")
            }
        }

        assertEquals(RemoteResult.Partial(listOf("a", "b"), "page 1: auth session changed"), result)
    }

    @Test
    fun `repeated pages become Partial instead of looping forever`() = runBlocking {
        val result = runPaginatedFetch(
            limit = 2,
            maxPages = 100,
            itemKey = { it },
        ) { page ->
            PageOutcome.Page(listOf("a", "b"), total = 4, reportedPage = page, reportedPageCount = 2)
        }

        assertEquals(RemoteResult.Partial(listOf("a", "b"), "page 1: repeated rows made no progress"), result)
    }

    @Test
    fun `impossible page count is an explicit failure`() = runBlocking {
        val result = runPaginatedFetch<String>(limit = 2, maxPages = 100) {
            PageOutcome.Page(listOf("a", "b"), total = 4, reportedPage = 0, reportedPageCount = 0)
        }

        assertEquals(RemoteResult.Failed("page 0: invalid page count 0"), result)
    }

    @Test
    fun `changed page declarations are Partial instead of a normal terminal page`() = runBlocking {
        val result = runPaginatedFetch<String>(limit = 2, maxPages = 100) { page ->
            when (page) {
                0 -> PageOutcome.Page(
                    results = listOf("a", "b"),
                    total = 0,
                    reportedPage = 0,
                    reportedPageCount = 3,
                )
                1 -> PageOutcome.Page(
                    results = listOf("c", "d"),
                    total = 0,
                    reportedPage = 1,
                    reportedPageCount = 2,
                )
                else -> unreachablePage("must stop when the server changes its page declaration")
            }
        }

        assertEquals(
            RemoteResult.Partial(
                listOf("a", "b"),
                "page 1: server changed page count from 3 to 2",
            ),
            result,
        )
    }

    @Test
    fun stopsAtMaxPagesWithPartialResult() = runBlocking {
        var pagesServed = 0
        val result = runPaginatedFetch<Int>(limit = 2, maxPages = 3, itemKey = { it.toString() }) { page ->
            pagesServed++
            PageOutcome.Page(results = listOf(page * 2, page * 2 + 1), total = 0)
        }
        assertEquals(3, pagesServed)
        assertTrue(result is RemoteResult.Partial)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), (result as RemoteResult.Partial).value)
    }

}
