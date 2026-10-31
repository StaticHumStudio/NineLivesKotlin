package com.ninelivesaudio.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A stopped book's server calls share one deadline (#54). */
class TerminalServerBudgetTest {

    private val never = CompletableDeferred<Boolean>()
    private var now = 0L

    @Test(timeout = 5_000)
    fun `three calls that never answer share one deadline`() = runBlocking {
        val budget = TerminalServerBudget(total = 300.milliseconds)
        val started = System.nanoTime()
        withContext(NonCancellable) { repeat(3) { assertNull(budget.call(serverLive = true) { never.await() }) } }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("waited ${ms}ms", ms < 550)
    }

    @Test
    fun `once the deadline has passed a later call does not run`() = runBlocking {
        val budget = TerminalServerBudget(total = 1.seconds, nanoTime = { now })
        now = 2_000_000_000L
        var ran = false
        assertNull(budget.call(serverLive = true) { ran = true; true })
        assertFalse(ran)
    }

    @Test
    fun `the deadline starts when the budget is made, not at the first call`() {
        now = 5_000_000_000L
        val budget = TerminalServerBudget(total = 5.seconds, nanoTime = { now })
        now += 3_000_000_000L
        assertEquals(2.seconds, budget.remaining())
        now += 9_000_000_000L
        assertEquals(Duration.ZERO, budget.remaining())
    }

    @Test
    fun `a live server that answers in time gets every call`() = runBlocking {
        val budget = TerminalServerBudget()
        assertEquals(listOf(true, true, true), List(3) { budget.call(serverLive = true) { true } })
    }

    @Test
    fun `a server that is not live gets no call`() = runBlocking {
        var ran = false
        assertNull(TerminalServerBudget().call(serverLive = false) { ran = true; true })
        assertFalse(ran)
    }

    @Test(timeout = 5_000)
    fun `the deadline running out asks for one reachability check`() = runBlocking {
        var checks = 0
        val budget = TerminalServerBudget(total = 100.milliseconds, onExpired = { checks++ })
        withContext(NonCancellable) { repeat(3) { budget.call(serverLive = true) { never.await() } } }
        assertEquals(1, checks)
        assertTrue(budget.checkExpired())
        assertEquals(1, checks)
    }

    @Test
    fun `a fast sequence never asks for a reachability check`() = runBlocking {
        var checks = 0
        val budget = TerminalServerBudget(onExpired = { checks++ })
        repeat(3) { budget.call(serverLive = true) { true } }
        assertEquals(0, checks)
    }

    @Test(timeout = 5_000)
    fun `a call stuck waiting on a lock still ends at the deadline`() = runBlocking {
        val lock = Mutex(locked = true) // a heartbeat holding ServerSessionListening's delivery lock
        val started = System.nanoTime()
        assertNull(TerminalServerBudget(total = 200.milliseconds).call(serverLive = true) { lock.withLock { true } })
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }

    @Test(timeout = 5_000)
    fun `a stopped book's session sync cannot outlast the deadline behind a stuck heartbeat`() = runBlocking {
        // The real lock: a heartbeat is mid request inside deliver, which runs
        // its send under NonCancellable.
        val listening = ServerSessionListening()
        val heartbeatStarted = CompletableDeferred<Unit>()
        val heartbeat = launch {
            listening.deliver(10.0) { heartbeatStarted.complete(Unit); never.await() }
        }
        heartbeatStarted.await()
        val budget = TerminalServerBudget(total = 300.milliseconds)
        val started = System.nanoTime()
        withContext(NonCancellable) {
            budget.call(serverLive = true) { listening.deliver(20.0) { budget.call(serverLive = true) { true } ?: false } }
            budget.call(serverLive = true) { listening.close {} }
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("waited ${ms}ms", ms < 800)
        never.complete(false)
        heartbeat.join()
    }

    @Test
    fun `a push after the deadline is spent gets no time`() {
        val budget = TerminalServerBudget(total = 1.seconds, nanoTime = { now })
        now = 2_000_000_000L
        assertEquals(Duration.ZERO, terminalPushTimeout(budget))
        assertEquals(TERMINAL_PROGRESS_PUSH_TIMEOUT, terminalPushTimeout(null))
    }

    @Test
    fun `the whole sequence gets what one call used to get`() {
        assertEquals(5_000L, TERMINAL_SEQUENCE_DEADLINE.inWholeMilliseconds)
    }

    @Test(timeout = 5_000)
    fun `a heartbeat stuck mid request lets go of the book's locks within its bound`() = runBlocking {
        // The real lock chain: a heartbeat runs inside the book's progress
        // lock, then inside the session delivery lock under NonCancellable.
        // The stopped book's final flush needs the progress lock first.
        val owner = PlaybackProgressOwner()
        val listening = ServerSessionListening()
        val heartbeatInFlight = CompletableDeferred<Unit>()
        val heartbeat = launch {
            owner.sync("a") {
                listening.deliver(10.0) {
                    heartbeatInFlight.complete(Unit)
                    boundedHeartbeatSend(timeout = 200.milliseconds) { never.await() }
                }
            }
        }
        heartbeatInFlight.await()
        val started = System.nanoTime()
        val budget = TerminalServerBudget(total = 300.milliseconds)
        var flushed = false
        withContext(NonCancellable) {
            owner.finalFlushSnapshot(owner.snapshotToken("a"), syncTerminal = {}) { flushed = true }
            budget.call(serverLive = true) { listening.deliver(20.0) { true } }
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(flushed)
        assertTrue("waited ${ms}ms", ms < 800)
        heartbeat.join()
    }

    @Test
    fun `a heartbeat that answers in time reports what the server said`() = runBlocking {
        assertTrue(boundedHeartbeatSend { true })
        assertFalse(boundedHeartbeatSend { false })
        assertFalse(boundedHeartbeatSend { null })
    }

    @Test
    fun `a heartbeat gets the same bound as the whole terminal sequence`() {
        assertEquals(TERMINAL_SEQUENCE_DEADLINE, HEARTBEAT_SYNC_TIMEOUT)
    }
}
