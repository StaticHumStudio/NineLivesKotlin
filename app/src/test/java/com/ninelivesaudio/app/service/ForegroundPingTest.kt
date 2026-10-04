package com.ninelivesaudio.app.service

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundPingTest {

    private suspend fun settle() = repeat(10) { yield() }

    @Test
    fun `the periodic ping runs only while the app is in the foreground`() = runBlocking {
        val foreground = MutableStateFlow(false)
        // Rendezvous: a tick is taken only when the loop is waiting out a delay.
        val ticks = Channel<Unit>()
        var checks = 0
        val loop = launch(start = CoroutineStart.UNDISPATCHED) {
            runForegroundPing(
                appInForeground = foreground,
                intervalMs = MutableStateFlow(PING_INTERVAL_MS),
                sleep = { ticks.receive() },
                check = { checks += 1 },
            )
        }

        settle()
        assertFalse("no ping waits in the background", ticks.trySend(Unit).isSuccess)
        assertEquals(0, checks)

        foreground.value = true
        settle()
        assertTrue(ticks.trySend(Unit).isSuccess)
        settle()
        assertEquals(1, checks)
        assertTrue(ticks.trySend(Unit).isSuccess)
        settle()
        assertEquals(2, checks)

        foreground.value = false
        settle()
        assertFalse("leaving the foreground stops the ping", ticks.trySend(Unit).isSuccess)
        assertEquals(2, checks)

        foreground.value = true
        settle()
        assertTrue("coming back starts it again", ticks.trySend(Unit).isSuccess)
        settle()
        assertEquals(3, checks)

        loop.cancel()
    }

    @Test
    fun `each wait uses the delay chosen at that moment`() = runBlocking {
        val foreground = MutableStateFlow(true)
        val ticks = Channel<Unit>()
        val waits = mutableListOf<Long>()
        val reachable = MutableStateFlow(true)
        val loop = launch(start = CoroutineStart.UNDISPATCHED) {
            runForegroundPing(
                appInForeground = foreground,
                intervalMs = reachable.map { nextPingDelayMs(isOnline = true, isServerReachable = it) },
                sleep = { waits += it; ticks.receive() },
                check = { reachable.value = false },
            )
        }
        settle()
        assertEquals(listOf(PING_INTERVAL_MS), waits)
        ticks.send(Unit)
        settle()
        // The wait running after a failed check is the 15 s retry. A 60 s
        // wait the status change cancels at once may show up before it.
        assertEquals(PING_RETRY_INTERVAL_MS, waits.last())
        loop.cancel()
    }

    @Test
    fun `the server going unreachable mid-wait restarts the wait at the retry interval`() = runBlocking {
        val foreground = MutableStateFlow(true)
        val ticks = Channel<Unit>()
        val waits = mutableListOf<Long>()
        var checks = 0
        val reachable = MutableStateFlow(true)
        val loop = launch(start = CoroutineStart.UNDISPATCHED) {
            runForegroundPing(
                appInForeground = foreground,
                intervalMs = reachable.map { nextPingDelayMs(isOnline = true, isServerReachable = it) },
                sleep = { waits += it; ticks.receive() },
                check = { checks += 1 },
            )
        }
        settle()
        assertEquals(listOf(PING_INTERVAL_MS), waits)

        // Another probe (a 502 from the foreground check) marks it unreachable
        // while the 60 second wait is running.
        reachable.value = false
        settle()
        assertEquals("the 60 s wait is dropped, not waited out", listOf(PING_INTERVAL_MS, PING_RETRY_INTERVAL_MS), waits)
        assertEquals(0, checks)

        ticks.send(Unit)
        settle()
        assertEquals("the re-probe comes after the 15 s wait", 1, checks)
        loop.cancel()
    }

    @Test
    fun `a successful request marks the server reachable only with a network`() {
        assertTrue(serverAnswerMarksReachable(isOnline = true, isServerReachable = false))
        assertFalse(serverAnswerMarksReachable(isOnline = false, isServerReachable = false))
        assertFalse(serverAnswerMarksReachable(isOnline = true, isServerReachable = true))
    }
}
