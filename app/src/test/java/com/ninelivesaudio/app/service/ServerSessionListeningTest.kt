package com.ninelivesaudio.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSessionListeningTest {
    @Test
    fun `heartbeats pause and terminal send only time not already acknowledged`() = runBlocking {
        val listening = ServerSessionListening()
        val sent = mutableListOf<Double>()
        for (total in listOf(12.0, 24.0, 29.5, 29.5)) {
            listening.deliver(total) { sent += it; true }
        }
        assertEquals(listOf(12.0, 12.0, 5.5, 0.0), sent)
        assertEquals(29.5, sent.sum(), 0.0)
    }

    @Test
    fun `rejected delivery remains pending and older snapshots cannot move acknowledgement back`() = runBlocking {
        val listening = ServerSessionListening()
        val sent = mutableListOf<Double>()
        assertTrue(listening.deliver(12.0) { sent += it; true })
        assertFalse(listening.deliver(24.0) { sent += it; false })
        assertTrue(listening.deliver(36.0) { sent += it; true })
        assertTrue(listening.deliver(24.0) { sent += it; true })
        assertTrue(listening.deliver(40.0) { sent += it; true })
        assertEquals(listOf(12.0, 12.0, 24.0, 0.0, 4.0), sent)
    }

    @Test
    fun `pause waits for an in flight heartbeat before computing its delta`() = runBlocking {
        val listening = ServerSessionListening()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<Double>()
        val heartbeat = async {
            listening.deliver(12.0) { sent += it; entered.complete(Unit); release.await(); true }
        }
        entered.await()
        val pause = async { listening.deliver(17.0) { sent += it; true } }
        release.complete(Unit)
        assertTrue(heartbeat.await())
        assertTrue(pause.await())
        assertEquals(listOf(12.0, 5.0), sent)
    }

    @Test
    fun `cancelling a successful in flight heartbeat does not resend its accepted time`() = runBlocking {
        val listening = ServerSessionListening()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<Double>()
        val heartbeat = launch {
            listening.deliver(12.0) { sent += it; entered.complete(Unit); release.await(); true }
        }
        entered.await()
        heartbeat.cancel()
        release.complete(Unit)
        heartbeat.cancelAndJoin()
        listening.deliver(17.0) { sent += it; true }
        assertEquals(listOf(12.0, 5.0), sent)
    }

    @Test
    fun `replacement session starts its own listening acknowledgement`() = runBlocking {
        val oldSession = ServerSessionListening()
        val newSession = ServerSessionListening()
        oldSession.deliver(36.0) { true }
        var firstNewDelta = 0.0
        newSession.deliver(7.0) { firstNewDelta = it; true }
        assertEquals(7.0, firstNewDelta, 0.0)
    }
}
