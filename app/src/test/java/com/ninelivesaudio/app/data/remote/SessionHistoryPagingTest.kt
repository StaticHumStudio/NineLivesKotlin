package com.ninelivesaudio.app.data.remote

import com.ninelivesaudio.app.domain.model.ListeningSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #65: the Dossier stopped at 20 pages of 50 sessions and Book Detail at
 * 3, so a heavy listener's longer periods were silently cut short. History now
 * pages until a page reaches past the start the caller asked for.
 */
class SessionHistoryPagingTest {

    private fun s(id: String, at: Long, startedAt: Long = at) = ListeningSession(
        id = id,
        libraryItemId = "book",
        currentTime = Duration.ZERO,
        timeListening = 600.seconds,
        startedAt = startedAt,
        updatedAt = at,
        displayTitle = null,
    )

    private fun page(vararg rows: ListeningSession, total: Int = 0) =
        PageOutcome.Page(rows.toList(), total = total)

    private fun unreachable(message: String): PageOutcome<ListeningSession> {
        fail(message)
        return PageOutcome.Stopped("unreachable")
    }

    @Test
    fun `paging stops after the first page that reaches past the period start`() = runBlocking {
        val requested = mutableListOf<Int>()
        val result = fetchSessionHistory(pageSize = 3, maxPages = 100, coveredFrom = 1_000L) { p ->
            requested += p
            when (p) {
                0 -> page(s("a", 5_000), s("b", 4_000), s("c", 3_000), total = 9)
                1 -> page(s("d", 2_000), s("e", 1_500), s("f", 900), total = 9)
                else -> unreachable("page 1 already reached past the period start")
            }
        }

        assertEquals(6, (result as RemoteResult.Ok).value.size)
        assertEquals(listOf(0, 1), requested)
    }

    @Test
    fun `a period holding more than 1,000 sessions is covered in full`() = runBlocking {
        val all = (0 until 1_200).map { k -> s("s$k", 10_000_000L - k) }
        val result = fetchSessionHistory(
            pageSize = 50,
            maxPages = SESSION_HISTORY_PAGE_CAP,
            coveredFrom = 1_000L,
        ) { p ->
            PageOutcome.Page(all.drop(p * 50).take(50), total = 1_200)
        }

        assertEquals(1_200, (result as RemoteResult.Ok).value.size)
    }

    @Test
    fun `no period start pages to the end of the history`() = runBlocking {
        val requested = mutableListOf<Int>()
        // Every row is ancient, so only a period start could have stopped early.
        val result = fetchSessionHistory(pageSize = 3, maxPages = 100, coveredFrom = null) { p ->
            requested += p
            when (p) {
                in 0..2 -> page(s("${p}a", 30L - p * 3), s("${p}b", 29L - p * 3), s("${p}c", 28L - p * 3))
                3 -> page(s("last", 1L))
                else -> unreachable("the short page ended the history")
            }
        }

        assertEquals(10, (result as RemoteResult.Ok).value.size)
        assertEquals(listOf(0, 1, 2, 3), requested)
    }

    @Test
    fun `the runaway cap still ends as Partial so the warning shows`() = runBlocking {
        val result = fetchSessionHistory(pageSize = 2, maxPages = 2, coveredFrom = 1_000L) { p ->
            page(s("${p}a", 9_000L - p * 2), s("${p}b", 8_999L - p * 2))
        }

        assertTrue(result is RemoteResult.Partial)
        result as RemoteResult.Partial
        assertEquals(4, result.value.size)
        assertTrue(result.reason, result.reason.contains("page cap"))
    }

    @Test
    fun `a row with no timestamps never ends the paging early`() = runBlocking {
        val requested = mutableListOf<Int>()
        val result = fetchSessionHistory(pageSize = 3, maxPages = 100, coveredFrom = 1_000L) { p ->
            requested += p
            when (p) {
                0 -> page(s("a", 5_000), s("b", 4_000), s("blank", 0L))
                1 -> page(s("c", 3_000), s("d", 900))
                else -> unreachable("the short page ended the history")
            }
        }

        assertEquals(listOf(0, 1), requested)
        assertEquals(5, (result as RemoteResult.Ok).value.size)
    }

    @Test
    fun `a page with no timestamps at all keeps paging`() {
        assertFalse(sessionPageReaches(listOf(s("x", 0L), s("y", 0L)), coveredFrom = 1_000L))
        assertFalse(sessionPageReaches(emptyList(), coveredFrom = 1_000L))
    }

    @Test
    fun `a page reaches the start only when its last row is before it`() {
        assertTrue(sessionPageReaches(listOf(s("a", 2_000), s("b", 999)), coveredFrom = 1_000L))
        // A row exactly at the start still counts in the period, so its
        // neighbours on the next page might too.
        assertFalse(sessionPageReaches(listOf(s("a", 2_000), s("b", 1_000)), coveredFrom = 1_000L))
        // The later of the two timestamps decides, so a server that sorted by
        // start time instead of update time is still never cut short.
        assertFalse(sessionPageReaches(listOf(s("a", at = 1_500, startedAt = 500)), coveredFrom = 1_000L))
    }

    @Test
    fun `rows come back newest first by start time`() = runBlocking {
        val result = fetchSessionHistory(pageSize = 3, maxPages = 10, coveredFrom = null) { p ->
            when (p) {
                0 -> page(s("old", 9_000, startedAt = 1_000), s("new", 8_000, startedAt = 7_000), s("mid", 7_500, startedAt = 4_000))
                else -> page()
            }
        }

        assertEquals(listOf("new", "mid", "old"), (result as RemoteResult.Ok).value.map { it.id })
    }

    @Test
    fun `a failed page after some history is Partial with its reason`() = runBlocking {
        val failures = mutableListOf<Int>()
        val result = fetchSessionHistory(
            pageSize = 2,
            maxPages = 100,
            coveredFrom = 1_000L,
            onPageFailure = { p, _ -> failures += p },
        ) { p ->
            when (p) {
                0 -> page(s("a", 5_000), s("b", 4_000))
                else -> throw IOException("HTTP 500 loading listening sessions page $p")
            }
        }

        assertTrue(result is RemoteResult.Partial)
        result as RemoteResult.Partial
        assertEquals(listOf("a", "b"), result.value.map { it.id })
        assertTrue(result.reason, result.reason.contains("HTTP 500"))
        assertEquals(listOf(1), failures)
    }

    @Test
    fun `cancelling stops the paging`() = runBlocking {
        val requested = mutableListOf<Int>()
        var escaped: Throwable? = null
        val job = launch {
            try {
                fetchSessionHistory(pageSize = 2, maxPages = 100, coveredFrom = 1_000L) { p ->
                    requested += p
                    // A period switch lands while page 1 is being served.
                    if (p == 1) currentCoroutineContext().cancel()
                    page(s("${p}a", 9_000L - p * 2), s("${p}b", 8_999L - p * 2))
                }
                fail("a cancelled history fetch must not return a result")
            } catch (e: CancellationException) {
                escaped = e
                throw e
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(escaped is CancellationException)
        assertEquals(listOf(0, 1), requested)
    }

    @Test
    fun `a book's history pages back to a day before it was added`() {
        assertEquals(10 * DAY - DAY, bookHistoryStart(10 * DAY))
    }

    @Test
    fun `a book with no added date pages its whole history`() {
        assertNull(bookHistoryStart(null))
        assertNull(bookHistoryStart(0L))
        assertNull(bookHistoryStart(DAY / 2))
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
