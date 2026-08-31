package com.ninelivesaudio.app.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Failure reasons end up pasted into a support email by a user who is already
 * annoyed. They have to be short, name the actual fault, and never be an
 * unbounded server string.
 */
class RemoteResultTest {

    @Test
    fun `a message-less exception is described by its type`() {
        assertEquals("SocketTimeoutException", describeFailure(SocketTimeoutException()))
    }

    @Test
    fun `an exception with a message keeps both`() {
        assertEquals("IOException: Canceled", describeFailure(IOException("Canceled")))
    }

    @Test
    fun `a runaway message is truncated`() {
        val reason = describeFailure(IOException("x".repeat(500)))
        assertTrue("was ${reason.length} chars", reason.length <= 120)
        assertTrue(reason.endsWith("..."))
    }

    @Test
    fun `map keeps the failure reason when it transforms a partial`() {
        val result: RemoteResult<List<String>> = RemoteResult.Partial(listOf("a", "b"), "page 3: timeout")
        assertEquals(RemoteResult.Partial(2, "page 3: timeout"), result.map { it.size })
    }

    @Test
    fun `map leaves a failure alone`() {
        val result: RemoteResult<List<String>> = RemoteResult.Failed("HTTP 500")
        assertEquals(RemoteResult.Failed("HTTP 500"), result.map { it.size })
    }

    @Test
    fun `a partial fetch still yields its books to callers that only want the list`() {
        val result: RemoteResult<List<String>> = RemoteResult.Partial(listOf("a"), "page 3: timeout")
        assertEquals(listOf("a"), result.valueOrEmpty())
    }

    @Test
    fun `a failed fetch yields nothing, so callers fall back to cache`() {
        val result: RemoteResult<List<String>> = RemoteResult.Failed("HTTP 500")
        assertEquals(emptyList<String>(), result.valueOrEmpty())
    }

    @Test
    fun `stopping short with books already fetched keeps them`() {
        val result = stoppedShort(listOf("a", "b"), "page 3: timeout")
        assertEquals(RemoteResult.Partial(listOf("a", "b"), "page 3: timeout"), result)
    }

    @Test
    fun `stopping short with nothing fetched is a plain failure`() {
        // Nothing to show and nothing to salvage. Partial would imply the user
        // has some of their shelf when they have none of it.
        assertEquals(RemoteResult.Failed("page 0: HTTP 500"), stoppedShort(emptyList<String>(), "page 0: HTTP 500"))
    }

    // ─── paginationResult: a false Ok must not be able to erase a shelf ────
    //
    // GitHub codex review of PR #30, finding B: getLibraryItems() labeled
    // EVERY loop exit Ok, including a page that came back empty or shorter
    // than the requested limit while the server's reported `total` said
    // there was more (a page cap or a transient inconsistency). Now that a
    // complete Ok prunes the cache (AudioBookRepository.reconcileServerLibrary,
    // LibraryRepository's library-level reconcile), a false Ok here would
    // erase the rest of a real shelf and still report success.

    @Test
    fun `collecting at least the reported total is a genuine Ok`() {
        assertEquals(RemoteResult.Ok(listOf("a", "b")), paginationResult(listOf("a", "b"), total = 2, currentPage = 0))
    }

    @Test
    fun `collecting more than the reported total is still Ok`() {
        // Can happen if the server's total shrinks between pages. More data
        // than promised is not a shortfall.
        assertEquals(RemoteResult.Ok(listOf("a", "b", "c")), paginationResult(listOf("a", "b", "c"), total = 2, currentPage = 0))
    }

    @Test
    fun `a total of zero (no total reported) trusts whatever came back as complete`() {
        // LibraryItemsResponse.total defaults to 0 when the server omits it.
        // allItems.size is always >= 0, so this must not be misread as a
        // shortfall against an actual reported total.
        assertEquals(RemoteResult.Ok(listOf("a")), paginationResult(listOf("a"), total = 0, currentPage = 0))
        assertEquals(RemoteResult.Ok(emptyList<String>()), paginationResult(emptyList<String>(), total = 0, currentPage = 0))
    }

    @Test
    fun `stopping short of a nonzero reported total with some items is Partial, not Ok`() {
        val result = paginationResult(listOf("a", "b"), total = 5, currentPage = 1)
        assertEquals(RemoteResult.Partial(listOf("a", "b"), "page 1: got 2 of 5 reported"), result)
    }

    @Test
    fun `stopping short of a nonzero reported total with nothing collected is a plain failure`() {
        val result = paginationResult(emptyList<String>(), total = 5, currentPage = 0)
        assertEquals(RemoteResult.Failed("page 0: got 0 of 5 reported"), result)
    }

    @Test
    fun `an omitted final total cannot erase an earlier reported shortfall`() = runBlocking {
        val result = runPaginatedFetch(limit = 100) { page ->
            when (page) {
                0 -> PageOutcome.Page((1..100).toList(), total = 250)
                1 -> PageOutcome.Page((101..200).toList(), total = 0)
                2 -> PageOutcome.Page(emptyList(), total = 0)
                else -> error("unexpected page $page")
            }
        }

        assertEquals(
            RemoteResult.Partial((1..200).toList(), "page 2: got 200 of 250 reported"),
            result,
        )
    }

    @Test
    fun `a later higher total governs an omitted short final page`() = runBlocking {
        val result = runPaginatedFetch(limit = 100) { page ->
            when (page) {
                0 -> PageOutcome.Page((1..100).toList(), total = 150)
                1 -> PageOutcome.Page((101..200).toList(), total = 250)
                2 -> PageOutcome.Page((201..210).toList(), total = 0)
                else -> error("unexpected page $page")
            }
        }

        assertEquals(
            RemoteResult.Partial((1..210).toList(), "page 2: got 210 of 250 reported"),
            result,
        )
    }

    @Test
    fun `a single page with its total remains Ok`() = runBlocking {
        val result = runPaginatedFetch(limit = 100) { page ->
            check(page == 0)
            PageOutcome.Page(listOf("a", "b"), total = 2)
        }

        assertEquals(RemoteResult.Ok(listOf("a", "b")), result)
    }

    @Test
    fun `all omitted totals still finish at their natural page end`() = runBlocking {
        val result = runPaginatedFetch(limit = 2) { page ->
            when (page) {
                0 -> PageOutcome.Page(listOf("a", "b"), total = 0)
                1 -> PageOutcome.Page(emptyList(), total = 0)
                else -> error("unexpected page $page")
            }
        }

        assertEquals(RemoteResult.Ok(listOf("a", "b")), result)
    }

    // ─── Cancellation must escape uncaught ────────────────────────────────
    //
    // getLibraries/getLibraryItems used to catch(e: Exception), which also
    // catches CancellationException (it is a RuntimeException subtype).
    // Stopping a sync mid-fetch turned into a persisted FAILED/PARTIAL result
    // and a failure banner instead of a silently cancelled request.

    @Test
    fun `remoteResultCatching lets cancellation escape instead of converting it to Failed`() = runBlocking {
        val cancellation = CancellationException("stop fetch")

        try {
            remoteResultCatching<Unit> { throw cancellation }
            fail("Expected cancellation to propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun `remoteResultCatching converts a real failure to Failed`() = runBlocking {
        val result = remoteResultCatching<Unit> { throw IOException("Canceled") }
        assertEquals(RemoteResult.Failed("IOException: Canceled"), result)
    }

    @Test
    fun `remoteResultCatching passes through a successful call unchanged`() = runBlocking {
        val result = remoteResultCatching { RemoteResult.Ok("books") }
        assertEquals(RemoteResult.Ok("books"), result)
    }
}
