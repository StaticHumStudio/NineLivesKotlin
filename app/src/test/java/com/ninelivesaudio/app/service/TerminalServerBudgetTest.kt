package com.ninelivesaudio.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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

    @Test(timeout = 10_000)
    fun `a switch during a slow heartbeat frees the book's locks at once`() = runBlocking {
        // The real lock chain: a heartbeat runs inside the book's progress
        // lock, then inside the session delivery lock under NonCancellable,
        // and the server takes 6 s to answer. The stopped book's final flush
        // needs the progress lock first, and the next load waits on the
        // whole terminal sequence.
        val owner = PlaybackProgressOwner()
        val listening = ServerSessionListening()
        val heartbeatInFlight = CompletableDeferred<Unit>()
        val sent = mutableListOf<Double>()
        var heartbeatAcknowledged: Boolean? = null
        val heartbeat = launch {
            owner.sync("a") {
                heartbeatAcknowledged = listening.deliver(10.0) { delta ->
                    listening.heartbeatSend {
                        sent += delta
                        heartbeatInFlight.complete(Unit)
                        delay(6_000)
                        true
                    }
                }
            }
        }
        heartbeatInFlight.await()
        val started = System.nanoTime()
        // The end of book job: signal first, then the locks, all on the real
        // shared deadline (J2).
        listening.markStopping()
        val terminal = launch {
            withContext(NonCancellable) {
                val budget = TerminalServerBudget()
                owner.finalFlushSnapshot(owner.snapshotToken("a"), syncTerminal = {}) {}
                budget.call(serverLive = true) {
                    listening.deliver(20.0) { delta -> budget.call(serverLive = true) { sent += delta; true } ?: false }
                }
                budget.call(serverLive = true) { listening.close {} }
            }
        }
        terminal.join() // the next book's load starts here
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("next load waited ${ms}ms", ms < 1_000)
        assertTrue("next load waited ${ms}ms, past the J2 budget", ms < TERMINAL_SEQUENCE_DEADLINE.inWholeMilliseconds)
        heartbeat.join()
        assertEquals(false, heartbeatAcknowledged)
        // The cut heartbeat's time rides on the final sync: one linear
        // double count at a switch, never more.
        assertEquals(listOf(10.0, 20.0), sent)
    }

    @Test
    fun `a heartbeat for a book that already stopped is not sent`() = runBlocking {
        val listening = ServerSessionListening()
        listening.markStopping()
        listening.markStopping()
        var called = false
        assertFalse(listening.heartbeatSend { called = true; true })
        assertFalse(called)
    }

    @Test(timeout = 30_000)
    fun `a slow server that still records gets each heartbeat's time once`() = runBlocking {
        // The server takes 6 s to answer but records every request it gets.
        // No book is stopping, so each heartbeat waits for its answer the way
        // master does, and the next one never resends acknowledged time.
        val listening = ServerSessionListening()
        val recorded = mutableListOf<Double>()
        for (total in listOf(12.0, 24.0, 36.0)) {
            listening.deliver(total) { delta ->
                listening.heartbeatSend {
                    recorded += delta
                    delay(6_000)
                    true
                }
            }
        }
        assertEquals(listOf(12.0, 12.0, 12.0), recorded)
        assertEquals(36.0, recorded.sum(), 0.0)
    }

    @Test
    fun `a heartbeat that answers reports what the server said`() = runBlocking {
        val listening = ServerSessionListening()
        assertTrue(listening.heartbeatSend { true })
        assertFalse(listening.heartbeatSend { false })
        assertFalse(listening.heartbeatSend { null })
    }
}
