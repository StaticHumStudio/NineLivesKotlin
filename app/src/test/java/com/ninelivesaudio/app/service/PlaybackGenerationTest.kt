package com.ninelivesaudio.app.service

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PlaybackGenerationTest {

    @Test
    fun `issuing a newer load does not invalidate the active load before claim`() = runBlocking {
        val owner = PlaybackLoadOwner()
        val first = owner.newRequest()
        assertTrue(owner.claim(first))
        val second = owner.newRequest()

        assertTrue(owner.isCurrent(first))
        assertFalse(owner.isCurrent(second))
    }

    @Test
    fun `newest prepared load claims ownership and invalidates the active load`() = runBlocking {
        val owner = PlaybackLoadOwner()
        val first = owner.newRequest()
        assertTrue(owner.claim(first))
        val second = owner.newRequest()

        assertTrue(owner.claim(second))
        assertFalse(owner.isCurrent(first))
        assertTrue(owner.isCurrent(second))
    }

    @Test
    fun `older pending load waits for the newer request to resolve`() = runBlocking {
        val owner = PlaybackLoadOwner()
        val first = owner.newRequest()
        val second = owner.newRequest()
        val firstClaim = async { owner.claim(first) }

        yield()
        assertFalse(firstClaim.isCompleted)
        assertTrue(owner.claim(second))
        assertFalse(firstClaim.await())
        assertFalse(owner.isCurrent(first))
        assertTrue(owner.isCurrent(second))
    }

    @Test
    fun `abandoning an invalid newer request preserves current ownership`() = runBlocking {
        val owner = PlaybackLoadOwner()
        val first = owner.newRequest()
        assertTrue(owner.claim(first))
        val second = owner.newRequest()

        owner.abandon(second)

        assertTrue(owner.isCurrent(first))
        assertFalse(owner.isCurrent(second))
    }

    @Test
    fun `older pending load may claim after the newer invalid request is abandoned`() = runBlocking {
        val owner = PlaybackLoadOwner()
        val first = owner.newRequest()
        val second = owner.newRequest()

        owner.abandon(second)

        assertTrue(owner.claim(first))
        assertTrue(owner.isCurrent(first))
    }

    @Test
    fun `older pending load waits and claims when newer preflight later abandons`() = runBlocking {
        val owner = PlaybackLoadOwner()
        val first = owner.newRequest()
        val second = owner.newRequest()
        val firstClaim = async { owner.claim(first) }

        yield()
        assertFalse(firstClaim.isCompleted)

        owner.abandon(second)

        assertTrue(firstClaim.await())
        assertTrue(owner.isCurrent(first))
    }

    @Test
    fun `polled progress buffer keeps only newest unsent sample`() {
        runBlocking {
            val reports = newPolledProgressReportChannel()
            val first = PolledProgressReport("book-a", 10.0, 90.0)
            val second = PolledProgressReport("book-a", 11.0, 90.0)
            val newest = PolledProgressReport("book-a", 12.0, 90.0)

            reports.send(first)
            reports.send(second)
            reports.send(newest)

            assertEquals(newest, reports.receive())
            reports.close()
        }
    }

    @Test
    fun `position polling reports remote progress even when duration is unknown`() {
        assertTrue(shouldReportPolledPosition(hasBook = true, duration = Duration.ZERO))
        assertFalse(shouldReportPolledPosition(hasBook = false, duration = 90.seconds))
    }

    @Test
    fun `late session result is accepted only for the same load generation and book`() {
        assertTrue(sessionResultIsCurrent(4L, 4L, "book-a", "book-a"))
        assertFalse(sessionResultIsCurrent(4L, 5L, "book-a", "book-a"))
        assertFalse(sessionResultIsCurrent(4L, 4L, "book-a", "book-b"))
    }

    @Test
    fun `load session result also requires the originating load request`() {
        assertTrue(loadSessionResultIsCurrent(true, 4L, 4L, "book-a", "book-a"))
        assertFalse(loadSessionResultIsCurrent(false, 4L, 4L, "book-a", "book-a"))
        assertFalse(loadSessionResultIsCurrent(true, 4L, 5L, "book-a", "book-a"))
    }

    @Test
    fun `heartbeat session stays owned by its captured playback lifetime`() {
        assertTrue(heartbeatSessionIsCurrent(4L, 4L, "book-a", "book-a", "session-a", "session-a"))
        assertFalse(heartbeatSessionIsCurrent(4L, 5L, "book-a", "book-a", "session-a", "session-a"))
        assertFalse(heartbeatSessionIsCurrent(4L, 4L, "book-a", "book-b", "session-a", "session-a"))
        assertFalse(heartbeatSessionIsCurrent(4L, 4L, "book-a", "book-a", "session-a", "session-b"))
    }

    @Test
    fun `heartbeat without a session becomes stale when a session opens`() {
        assertTrue(playbackSyncLifetimeIsCurrent(4L, 4L, "book-a", "book-a", null, null, null, null))
        assertFalse(playbackSyncLifetimeIsCurrent(4L, 4L, "book-a", "book-a", null, "session-a", null, null))
        assertFalse(playbackSyncLifetimeIsCurrent(4L, 5L, "book-a", "book-a", null, null, null, null))
    }

    @Test
    fun `stale session probe keeps the captured book position and session`() {
        val probe = staleSessionProbe(
            requestedGeneration = 4L,
            bookId = "book-a",
            sessionId = "session-a",
            position = 12.seconds,
            duration = 90.seconds,
        )

        assertEquals(4L, probe.requestedGeneration)
        assertEquals("book-a", probe.bookId)
        assertEquals("session-a", probe.sessionId)
        assertEquals(12.seconds, probe.position)
        assertEquals(90.seconds, probe.duration)
    }
}
