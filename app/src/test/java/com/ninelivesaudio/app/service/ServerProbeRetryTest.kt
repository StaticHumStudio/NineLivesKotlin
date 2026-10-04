package com.ninelivesaudio.app.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerProbeRetryTest {

    private class FakeProbe(vararg answers: Boolean) {
        private val queue = ArrayDeque(answers.toList())
        val budgets = mutableListOf<Long>()
        suspend fun probe(budgetMs: Long): Boolean {
            budgets += budgetMs
            return queue.removeFirst()
        }
    }

    @Test
    fun `a 5xx from a proxy means the server is down`() {
        assertFalse(pingStatusMeansReachable(502))
        assertFalse(pingStatusMeansReachable(503))
        assertFalse(pingStatusMeansReachable(500))
    }

    @Test
    fun `any answer below 500 means the server is up`() {
        assertTrue(pingStatusMeansReachable(200))
        assertTrue(pingStatusMeansReachable(204))
        assertTrue(pingStatusMeansReachable(401))
        assertTrue(pingStatusMeansReachable(404))
    }

    @Test
    fun `one slow probe on a reachable server is retried and stays reachable`() = runBlocking {
        val probe = FakeProbe(false, true)
        val sleeps = mutableListOf<Long>()

        val reachable = probeServerWithRetry(
            wasReachable = true,
            stillOnline = { true },
            probe = probe::probe,
            sleep = { sleeps += it },
        )

        assertTrue(reachable)
        assertEquals(listOf(PROBE_TIMEOUT_MS, PROBE_RETRY_TIMEOUT_MS), probe.budgets)
        assertEquals(listOf(PROBE_RETRY_DELAY_MS), sleeps)
    }

    @Test
    fun `two failed probes call a reachable server unreachable`() = runBlocking {
        val probe = FakeProbe(false, false)

        val reachable = probeServerWithRetry(
            wasReachable = true,
            stillOnline = { true },
            probe = probe::probe,
            sleep = {},
        )

        assertFalse(reachable)
        assertEquals(2, probe.budgets.size)
    }

    @Test
    fun `a server already unreachable is not retried`() = runBlocking {
        val probe = FakeProbe(false)

        val reachable = probeServerWithRetry(
            wasReachable = false,
            stillOnline = { true },
            probe = probe::probe,
            sleep = { error("no retry wait for a server already unreachable") },
        )

        assertFalse(reachable)
        assertEquals(listOf(PROBE_TIMEOUT_MS), probe.budgets)
    }

    @Test
    fun `no retry once the network is gone`() = runBlocking {
        val probe = FakeProbe(false)

        val reachable = probeServerWithRetry(
            wasReachable = true,
            stillOnline = { false },
            probe = probe::probe,
            sleep = { error("no retry wait without a network") },
        )

        assertFalse(reachable)
        assertEquals(1, probe.budgets.size)
    }

    @Test
    fun `a network lost during the retry wait skips the second probe`() = runBlocking {
        val probe = FakeProbe(false)
        var online = true

        val reachable = probeServerWithRetry(
            wasReachable = true,
            stillOnline = { online },
            probe = probe::probe,
            sleep = { online = false },
        )

        assertFalse(reachable)
        assertEquals(1, probe.budgets.size)
    }

    @Test
    fun `a first probe that answers needs no retry`() = runBlocking {
        val probe = FakeProbe(true)

        assertTrue(
            probeServerWithRetry(
                wasReachable = true,
                stillOnline = { true },
                probe = probe::probe,
                sleep = { error("no retry after a good answer") },
            ),
        )
        assertEquals(1, probe.budgets.size)
    }

    @Test
    fun `an unreachable server is pinged again in 15 seconds, otherwise every minute`() {
        assertEquals(PING_RETRY_INTERVAL_MS, nextPingDelayMs(isOnline = true, isServerReachable = false))
        assertEquals(PING_INTERVAL_MS, nextPingDelayMs(isOnline = true, isServerReachable = true))
        assertEquals(PING_INTERVAL_MS, nextPingDelayMs(isOnline = false, isServerReachable = false))
        assertEquals(15_000L, PING_RETRY_INTERVAL_MS)
    }
}
