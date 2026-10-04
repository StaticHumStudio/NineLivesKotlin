package com.ninelivesaudio.app.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audiobookshelf can be asked for books newest-added first but not "updated
 * since", so the incremental sync pages down until it passes the stored
 * watermark. These pin where it stops, and when it refuses to guess.
 */
class IncrementalFetchTest {

    private data class Row(val id: String, val addedAt: Long?)

    private fun page(vararg rows: Row, total: Int) = PageOutcome.Page(rows.toList(), total)

    private suspend fun fetch(
        cutoff: Long,
        pageSize: Int = 3,
        maxPages: Int = 5,
        pages: (Int) -> PageOutcome<Row>,
    ): RemoteResult<IncrementalFetch<Row>> = runIncrementalFetch(
        pageSize = pageSize,
        maxPages = maxPages,
        cutoff = cutoff,
        addedAt = { it.addedAt },
        itemKey = { it.id },
        fetchPage = { pages(it) },
    )

    @Test
    fun `a page that crosses the watermark ends the fetch`() = runBlocking {
        val requested = mutableListOf<Int>()
        val result = fetch(cutoff = 100) { p ->
            requested += p
            when (p) {
                0 -> page(Row("e", 500), Row("d", 400), Row("c", 300), total = 10)
                1 -> page(Row("b", 200), Row("a", 90), Row("z", 80), total = 10)
                else -> error("must not fetch page $p")
            }
        }
        assertEquals(listOf(0, 1), requested)
        val value = (result as RemoteResult.Ok).value
        assertEquals(listOf("e", "d", "c", "b", "a", "z"), value.items.map { it.id })
        assertEquals(10, value.total)
    }

    @Test
    fun `a short page is the end of the list`() = runBlocking {
        val result = fetch(cutoff = 0) { p ->
            if (p == 0) page(Row("b", 200), Row("a", 100), total = 2) else error("no page $p")
        }
        assertEquals(2, (result as RemoteResult.Ok).value.items.size)
    }

    @Test
    fun `a server that ignored the sort gives up instead of missing books`() = runBlocking {
        val result = fetch(cutoff = 100) { page(Row("a", 300), Row("b", 900), Row("c", 50), total = 3) }
        assertTrue(result is RemoteResult.Failed)
    }

    @Test
    fun `the order is checked across pages too`() = runBlocking {
        val result = fetch(cutoff = 0) { p ->
            when (p) {
                0 -> page(Row("c", 300), Row("b", 200), Row("a", 150), total = 6)
                else -> page(Row("x", 160), Row("y", 140), total = 6)
            }
        }
        assertTrue(result is RemoteResult.Failed)
    }

    @Test
    fun `a book repeated across pages counts once and equal times are fine`() = runBlocking {
        val result = fetch(cutoff = 0) { p ->
            when (p) {
                0 -> page(Row("c", 300), Row("b", 200), Row("a", 200), total = 4)
                else -> page(Row("a", 200), Row("z", 100), total = 4)
            }
        }
        assertEquals(listOf("c", "b", "a", "z"), (result as RemoteResult.Ok).value.items.map { it.id })
    }

    @Test
    fun `a book with no added date gives up`() = runBlocking {
        val result = fetch(cutoff = 0) { page(Row("a", null), total = 1) }
        assertTrue(result is RemoteResult.Failed)
    }

    @Test
    fun `too many pages of new books hands over to the full download`() = runBlocking {
        val result = fetch(cutoff = 0, pageSize = 1, maxPages = 3) { p -> page(Row("b$p", 1000L - p), total = 50) }
        assertTrue(result is RemoteResult.Failed)
    }

    @Test
    fun `a count that moves between pages gives up`() = runBlocking {
        val result = fetch(cutoff = 0) { p ->
            when (p) {
                0 -> page(Row("c", 300), Row("b", 200), Row("a", 150), total = 6)
                else -> page(Row("z", 100), total = 7)
            }
        }
        assertTrue(result is RemoteResult.Failed)
    }

    @Test
    fun `a failed page is a failed fetch, never a partial one`() = runBlocking {
        val result = fetch(cutoff = 0) { p ->
            if (p == 0) page(Row("c", 300), Row("b", 200), Row("a", 150), total = 6) else PageOutcome.Stopped("HTTP 500")
        }
        assertTrue(result is RemoteResult.Failed)
    }

    @Test
    fun `an empty library comes back empty and complete`() = runBlocking {
        val result = fetch(cutoff = 0) { page(total = 0) }
        val value = (result as RemoteResult.Ok).value
        assertEquals(0, value.items.size)
        assertEquals(0, value.total)
    }

    @Test
    fun `page step decisions`() {
        assertEquals(IncrementalPageStep.Done, incrementalPageStep(emptyList(), null, 0, 3, 0, 5))
        assertEquals(IncrementalPageStep.Continue, incrementalPageStep(listOf(9L, 8L, 7L), null, 5, 3, 0, 5))
        assertEquals(IncrementalPageStep.Done, incrementalPageStep(listOf(9L, 8L, 4L), null, 5, 3, 0, 5))
        assertEquals(IncrementalPageStep.Done, incrementalPageStep(listOf(9L, 8L), null, 5, 3, 0, 5))
        assertTrue(incrementalPageStep(listOf(9L, 8L, 7L), null, 5, 3, 4, 5) is IncrementalPageStep.GiveUp)
        assertTrue(incrementalPageStep(listOf(9L, 8L, 7L), 8L, 5, 3, 1, 5) is IncrementalPageStep.GiveUp)
    }
}
