package com.ninelivesaudio.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}
