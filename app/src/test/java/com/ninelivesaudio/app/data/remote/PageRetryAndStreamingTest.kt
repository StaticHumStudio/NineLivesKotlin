package com.ninelivesaudio.app.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * A full download of a big library is a long run of pages against a slow
 * home server. One bad page used to end it short with no second try, and
 * every page sat in memory until the last one landed.
 */
class PageRetryAndStreamingTest {

    @Test
    fun `a thrown network error is retried and the page still arrives`() = runBlocking {
        var calls = 0
        val slept = mutableListOf<Long>()
        val outcome = fetchPageWithRetry(delaysMs = listOf(10L, 20L), sleep = { slept += it }) {
            calls++
            if (calls < 3) throw IOException("timeout") else PageOutcome.Page(listOf("a"), 1)
        }
        assertEquals(3, calls)
        assertEquals(listOf(10L, 20L), slept)
        assertTrue(outcome is PageOutcome.Page)
    }

    @Test
    fun `a retryable stop is retried, then returned as is when tries run out`() = runBlocking {
        var calls = 0
        val outcome = fetchPageWithRetry<String>(delaysMs = listOf(1L, 1L), sleep = {}) {
            calls++
            PageOutcome.Stopped("HTTP 503", retryable = true)
        }
        assertEquals(3, calls)
        assertEquals("HTTP 503", (outcome as PageOutcome.Stopped).reason)
    }

    @Test
    fun `a 401 is not retried`() = runBlocking {
        var calls = 0
        fetchPageWithRetry<String>(delaysMs = listOf(1L, 1L), sleep = {}) {
            calls++
            PageOutcome.Stopped("HTTP 401", retryable = isRetryableHttpStatus(401))
        }
        assertEquals(1, calls)
    }

    @Test(expected = IOException::class)
    fun `the last thrown error escapes for the paging loop to report`() {
        runBlocking {
            fetchPageWithRetry<String>(delaysMs = listOf(1L), sleep = {}) { throw IOException("down") }
        }
    }

    @Test
    fun `retryable statuses are server errors, timeouts and rate limits`() {
        assertTrue(isRetryableHttpStatus(500))
        assertTrue(isRetryableHttpStatus(503))
        assertTrue(isRetryableHttpStatus(408))
        assertTrue(isRetryableHttpStatus(429))
        assertTrue(!isRetryableHttpStatus(401))
        assertTrue(!isRetryableHttpStatus(404))
    }

    @Test
    fun `streaming hands each page over and reports the distinct count`() = runBlocking {
        val delivered = mutableListOf<List<String>>()
        val result = runPaginatedFetchStreaming(
            limit = 2,
            maxPages = 10,
            itemKey = { it },
            onPage = { delivered += it },
        ) { page ->
            when (page) {
                0 -> PageOutcome.Page(listOf("a", "b"), 3)
                else -> PageOutcome.Page(listOf("b", "c"), 3)
            }
        }
        assertEquals(listOf(listOf("a", "b"), listOf("c")), delivered)
        assertEquals(RemoteResult.Ok(3), result)
    }

    @Test
    fun `a page that fails after others were handed over is partial by count`() = runBlocking {
        val delivered = mutableListOf<String>()
        val result = runPaginatedFetchStreaming(
            limit = 2,
            maxPages = 10,
            onPage = { delivered += it },
        ) { page ->
            if (page == 0) PageOutcome.Page(listOf("a", "b"), 5) else PageOutcome.Stopped("HTTP 500")
        }
        assertEquals(listOf("a", "b"), delivered)
        assertEquals(RemoteResult.Partial(2, "HTTP 500"), result)
    }

    @Test
    fun `a save that throws stops the fetch short like a failed page`() = runBlocking {
        val result = runPaginatedFetchStreaming<String>(
            limit = 2,
            maxPages = 10,
            onPage = { throw IllegalStateException("disk full") },
        ) { PageOutcome.Page(listOf("a", "b"), 4) }
        assertTrue(result is RemoteResult.Failed)
    }
}
