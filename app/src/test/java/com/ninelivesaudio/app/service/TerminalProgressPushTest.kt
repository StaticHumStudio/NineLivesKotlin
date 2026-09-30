package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.repository.pushWithin
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Stopping a book writes its final position locally, then pushes it to the
 * server. The next book's load waits on that job, so a push to a server that
 * is not answering (a VPN keeps the OS online) held a downloaded book for the
 * full 30s connect timeout. The push now runs only while the server is live
 * and gives up quickly. The offline queue row written first delivers it later.
 */
class TerminalProgressPushTest {

    @Test
    fun `the push runs only while the server is live`() {
        listOf(ConnectionStatus.CONNECTED, ConnectionStatus.SYNCING).forEach { status ->
            assertTrue(terminalProgressPushAllowed(isOnline = true, connectionStatus = status))
        }
        listOf(ConnectionStatus.OFFLINE, ConnectionStatus.SERVER_UNREACHABLE).forEach { status ->
            assertFalse(terminalProgressPushAllowed(isOnline = true, connectionStatus = status))
        }
        ConnectionStatus.entries.forEach { status ->
            assertFalse(terminalProgressPushAllowed(isOnline = false, connectionStatus = status))
        }
    }

    @Test
    fun `the push waits about five seconds at most`() {
        assertEquals(5.seconds, TERMINAL_PROGRESS_PUSH_TIMEOUT)
    }

    @Test(timeout = 5_000)
    fun `a push that never answers gives up at the bound`() = runBlocking {
        val never = CompletableDeferred<Boolean>()
        val started = System.nanoTime()
        val pushed = pushWithin(100.milliseconds) { never.await() }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertFalse(pushed)
        assertTrue("waited ${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test
    fun `a push that answers in time reports its result`() = runBlocking {
        assertTrue(pushWithin(1.seconds) { true })
        assertFalse(pushWithin(1.seconds) { false })
    }
}
