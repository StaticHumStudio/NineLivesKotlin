package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.service.positionCountsAsFinished
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pause on a server book saves through its listening session. The session
 * sync carries the position but no finished flag, so a finished save must not
 * count as delivered until the finished flag itself reaches the server.
 */
class SessionProgressDeliveryTest {

    /** The queued fallback rows for one book and what the server holds. */
    private class FakeServerBook {
        val queuedFinishedFlags = mutableListOf<Boolean>()
        var serverFinished = false
        var finishedPushes = 0

        suspend fun save(
            isFinished: Boolean,
            sessionAnswers: Boolean = true,
            finishedPushAnswers: Boolean = true,
        ): Boolean {
            // The repository queues the row before trying the server.
            queuedFinishedFlags += isFinished
            return deliverSessionProgress(
                isFinished = isFinished,
                // Like the real session sync: position only, never the flag.
                syncSession = { sessionAnswers },
                pushFinished = {
                    finishedPushes++
                    if (finishedPushAnswers) serverFinished = true
                    finishedPushAnswers
                },
                acknowledge = { queuedFinishedFlags.clear() },
            )
        }
    }

    @Test
    fun `pause inside the last second marks the book finished on the server`() = runBlocking {
        val book = FakeServerBook()

        val delivered = book.save(isFinished = true)

        assertTrue(delivered)
        assertTrue(book.serverFinished)
        assertTrue(book.queuedFinishedFlags.isEmpty())
    }

    @Test
    fun `finished push that fails keeps the queued finished row`() = runBlocking {
        val book = FakeServerBook()

        val delivered = book.save(isFinished = true, finishedPushAnswers = false)

        // The listening time still went through the session.
        assertTrue(delivered)
        assertFalse(book.serverFinished)
        assertEquals(listOf(true), book.queuedFinishedFlags)
    }

    @Test
    fun `finished save whose session sync fails keeps its row for the reconnect flush`() = runBlocking {
        val book = FakeServerBook()

        val delivered = book.save(isFinished = true, sessionAnswers = false)

        assertFalse(delivered)
        assertEquals(0, book.finishedPushes)
        assertEquals(listOf(true), book.queuedFinishedFlags)
    }

    @Test
    fun `unfinished pause is delivered by the session sync alone`() = runBlocking {
        val book = FakeServerBook()

        val delivered = book.save(isFinished = false)

        assertTrue(delivered)
        assertEquals(0, book.finishedPushes)
        assertTrue(book.queuedFinishedFlags.isEmpty())
    }

    @Test
    fun `seeking back from a queued finish lets the newer unfinished pause win`() = runBlocking {
        val book = FakeServerBook()
        // The end was reached while the server was away.
        book.save(isFinished = true, sessionAnswers = false)

        // The listener sought back and paused well before the end.
        val delivered = book.save(isFinished = false)

        assertTrue(delivered)
        assertFalse(book.serverFinished)
        assertTrue(book.queuedFinishedFlags.isEmpty())
    }

    @Test
    fun `pause thirty seconds before the end is not a finished save`() {
        assertFalse(positionCountsAsFinished(currentTimeSec = 3570.0, durationSec = 3600.0))
        assertTrue(positionCountsAsFinished(currentTimeSec = 3599.5, durationSec = 3600.0))
    }
}
