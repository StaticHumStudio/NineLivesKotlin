package com.ninelivesaudio.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * After a stopped book's final progress lands, the same terminal job sends
 * the session's last sync and closes it on the server. The next book's load
 * waits on that job, so with the server gone mid-listen those two calls held
 * it for the 30s connect timeout each. They now follow the progress push:
 * skipped unless the server is live, and given up at the same bound.
 */
class TerminalSessionCallTest {

    @Test
    fun `a server that is not live gets no call`() = runBlocking {
        var called = false
        val result = terminalServerCall(serverLive = false) { called = true; true }
        assertFalse(called)
        assertNull(result)
    }

    @Test(timeout = 5_000)
    fun `a call that never answers gives up at the bound inside the terminal job`() = runBlocking {
        val never = CompletableDeferred<Boolean>()
        val started = System.nanoTime()
        // The terminal job runs under NonCancellable. The bound must still hold.
        val result = withContext(NonCancellable) {
            terminalServerCall(serverLive = true, timeout = 100.milliseconds) { never.await() }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertNull(result)
        assertTrue("waited ${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test
    fun `a live server that answers returns its result`() = runBlocking {
        assertEquals(true, terminalServerCall(serverLive = true) { true })
    }

    @Test
    fun `the default bound is the progress push bound`() = runBlocking {
        // Guards against the two terminal bounds drifting apart.
        assertEquals(5_000L, TERMINAL_PROGRESS_PUSH_TIMEOUT.inWholeMilliseconds)
    }
}
