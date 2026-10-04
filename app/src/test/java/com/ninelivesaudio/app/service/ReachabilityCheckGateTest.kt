package com.ninelivesaudio.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReachabilityCheckGateTest {

    @Test
    fun `concurrent reachability checks run one at a time`() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        var invocation = 0
        val gate = ReachabilityCheckGate {
            invocation += 1
            if (invocation == 1) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            } else {
                secondEntered.complete(Unit)
            }
            true
        }

        val first = async(start = CoroutineStart.UNDISPATCHED) { gate.run() }
        firstEntered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { gate.run() }

        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        assertTrue(first.await())
        assertTrue(second.await())
        assertTrue(secondEntered.isCompleted)
    }

    @Test
    fun `callers asking about the same network share a probe that just finished`() = runBlocking {
        var now = 1_000L
        var probes = 0
        val gate = ReachabilityCheckGate(nowMs = { now }, reuseWindowMs = PROBE_REUSE_WINDOW_MS) { probes += 1; true }
        val key = ProbeReuseKey(generation = 3, serverUrl = "http://abs")

        assertTrue(gate.run(key))
        now += 1_500L
        assertTrue(gate.run(key))
        now += 1_000L
        assertTrue(gate.run(key))
        assertEquals("cold start's burst is one /ping", 1, probes)
    }

    @Test
    fun `a waiting caller gets the answer of the probe it waited on`() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var probes = 0
        val gate = ReachabilityCheckGate(nowMs = { 0L }, reuseWindowMs = PROBE_REUSE_WINDOW_MS) {
            probes += 1
            firstEntered.complete(Unit)
            releaseFirst.await()
            false
        }
        val key = ProbeReuseKey(generation = 1, serverUrl = "http://abs")
        val first = async(start = CoroutineStart.UNDISPATCHED) { gate.run(key) }
        firstEntered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { gate.run(key) }
        releaseFirst.complete(Unit)
        assertFalse(first.await())
        assertFalse(second.await())
        assertEquals(1, probes)
    }

    @Test
    fun `a new network, another server, or an old answer means a new probe`() = runBlocking {
        var now = 0L
        var probes = 0
        val gate = ReachabilityCheckGate(nowMs = { now }, reuseWindowMs = PROBE_REUSE_WINDOW_MS) { probes += 1; true }
        gate.run(ProbeReuseKey(1, "http://abs"))
        gate.run(ProbeReuseKey(2, "http://abs"))
        assertEquals("network changed", 2, probes)
        gate.run(ProbeReuseKey(2, "http://other"))
        assertEquals("server changed", 3, probes)
        now += PROBE_REUSE_WINDOW_MS
        gate.run(ProbeReuseKey(2, "http://other"))
        assertEquals("window passed", 4, probes)
        gate.run(null)
        assertEquals("no key never shares", 5, probes)
    }

    @Test
    fun `a server answer after a failed probe means the next caller probes again`() = runBlocking {
        var probes = 0
        var answer = false
        val gate = ReachabilityCheckGate(nowMs = { 0L }, reuseWindowMs = PROBE_REUSE_WINDOW_MS) {
            probes += 1
            answer
        }
        val key = ProbeReuseKey(generation = 1, serverUrl = "http://abs")
        assertFalse(gate.run(key))
        // A playback push lands inside the reuse window.
        gate.serverAnswered()
        answer = true
        assertTrue("the failure is not shared after the server answered", gate.run(key))
        assertEquals(2, probes)
        // A success is still shared.
        assertTrue(gate.run(key))
        assertEquals(2, probes)
    }

    @Test
    fun `a server answer during a failing probe keeps that failure from being shared`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var probes = 0
        val gate = ReachabilityCheckGate(nowMs = { 0L }, reuseWindowMs = PROBE_REUSE_WINDOW_MS) {
            probes += 1
            if (probes == 1) {
                entered.complete(Unit)
                release.await()
                false
            } else {
                true
            }
        }
        val key = ProbeReuseKey(generation = 1, serverUrl = "http://abs")
        val first = async(start = CoroutineStart.UNDISPATCHED) { gate.run(key) }
        entered.await()
        gate.serverAnswered()
        release.complete(Unit)
        assertFalse(first.await())
        assertTrue(gate.run(key))
        assertEquals(2, probes)
    }

    @Test
    fun `a clock reading before the finish does not share`() {
        assertFalse(canReuseProbe("k", "k", finishedAtMs = 5_000L, nowMs = 4_000L, windowMs = PROBE_REUSE_WINDOW_MS))
        assertTrue(canReuseProbe("k", "k", finishedAtMs = 5_000L, nowMs = 5_000L, windowMs = PROBE_REUSE_WINDOW_MS))
    }
}
