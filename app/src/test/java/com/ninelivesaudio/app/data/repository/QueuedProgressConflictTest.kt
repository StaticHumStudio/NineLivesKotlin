package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.remote.ServerProgressRead
import com.ninelivesaudio.app.data.repository.QueuedProgressDecision.ALREADY_ON_SERVER
import com.ninelivesaudio.app.data.repository.QueuedProgressDecision.PUSH
import com.ninelivesaudio.app.data.repository.QueuedProgressDecision.SERVER_STANDS
import com.ninelivesaudio.app.domain.model.UserProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #118. A queued progress row meets the server's progress before it is
 * sent, so an old spot on the phone never overwrites a newer spot another
 * device left, and listening done here is still delivered.
 */
class QueuedProgressConflictTest {

    private val evening = 1_793_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun row(position: Double, atMs: Long?, finished: Boolean = false) =
        QueuedProgressFacts(
            positionSeconds = position,
            isFinished = finished,
            durationSeconds = 36_000.0,
            timestampMs = atMs,
        )

    private fun found(
        position: Double,
        lastUpdate: Long?,
        serverNow: Long?,
        finished: Boolean = false,
    ) = ServerProgressRead.Found(
        progress = UserProgress(
            libraryItemId = "item-1",
            currentTime = position.seconds,
            progress = position / 36_000.0,
            isFinished = finished,
            lastUpdate = lastUpdate,
        ),
        serverNowMs = serverNow,
    )

    /** Shift the phone clock, the server clock, or both, by the given amounts. */
    private fun decide(
        row: QueuedProgressFacts,
        server: ServerProgressRead.Answered,
        phoneNow: Long,
        phoneSkew: Long = 0,
        serverSkew: Long = 0,
    ): QueuedProgressDecision {
        val skewedRow = row.copy(timestampMs = row.timestampMs?.plus(phoneSkew))
        val skewedServer = when (server) {
            is ServerProgressRead.Found -> server.copy(
                progress = server.progress.copy(lastUpdate = server.progress.lastUpdate?.plus(serverSkew)),
                serverNowMs = server.serverNowMs?.plus(serverSkew),
            )
            is ServerProgressRead.Missing -> server
        }
        return decideQueuedProgress(skewedRow, skewedServer, phoneNow + phoneSkew)
    }

    @Test
    fun `decision table`() {
        // The issue: the phone stopped at 1:33:51 in the evening, the Linux app
        // took the book to 3:43:04 three hours later, the phone wakes up next
        // morning with its old row.
        val issueRow = row(5_631.0, evening)
        val issueServer = found(13_384.0, lastUpdate = evening + 3 * hour, serverNow = evening + 14 * hour)
        // Two hours of offline listening after the server last heard from us.
        val offlineRow = row(12_831.0, evening + 2 * hour)
        val offlineServer = found(5_631.0, lastUpdate = evening, serverNow = evening + 3 * hour)
        val skews = listOf(0L, 5 * hour, -5 * hour, 37 * minute)

        for (phoneSkew in skews) for (serverSkew in skews) {
            val label = "phone skew $phoneSkew, server skew $serverSkew"
            assertEquals(label, SERVER_STANDS, decide(issueRow, issueServer, evening + 14 * hour, phoneSkew, serverSkew))
            assertEquals(label, PUSH, decide(offlineRow, offlineServer, evening + 3 * hour, phoneSkew, serverSkew))
        }

        // The server already holds this spot, however the clocks read.
        assertEquals(
            ALREADY_ON_SERVER,
            decide(row(5_631.0, evening), found(5_631.03, evening + 3 * hour, evening + 14 * hour), evening + 14 * hour),
        )
        // Same position but a different finished flag is not the same spot.
        assertEquals(
            PUSH,
            decide(row(5_631.0, evening + hour, finished = true), found(5_631.0, evening, evening + 2 * hour), evening + 2 * hour),
        )

        // No progress on the account yet.
        assertEquals(PUSH, decide(issueRow, ServerProgressRead.Missing(serverNowMs = null), evening + 14 * hour))

        // Inside the margin is too close to call. At the margin it pushes.
        val server = found(100.0, lastUpdate = evening, serverNow = evening + minute)
        assertEquals(SERVER_STANDS, decide(row(120.0, evening + 20_000), server, evening + minute))
        assertEquals(PUSH, decide(row(130.0, evening + 30_000), server, evening + minute))

        // No Date header: the row's own time meets the server's lastUpdate.
        assertEquals(PUSH, decide(row(12_831.0, evening + 2 * hour), found(5_631.0, evening, null), evening + 3 * hour))
        assertEquals(SERVER_STANDS, decide(issueRow, found(13_384.0, evening + 3 * hour, null), evening + 14 * hour))
        assertEquals(SERVER_STANDS, decide(row(120.0, evening + 20_000), found(100.0, evening, null), evening + minute))

        // A Date header rounded down to the second reads a hair before lastUpdate.
        assertEquals(SERVER_STANDS, decide(row(120.0, evening - hour), found(100.0, evening, evening - 1_500), evening))
        // A row stamped ten minutes in the phone's future: the phone clock moved
        // back, so the order cannot be told.
        assertEquals(
            SERVER_STANDS,
            decide(row(12_831.0, evening + 3 * hour + 10 * minute), offlineServer, evening + 3 * hour),
        )
        // A server lastUpdate clearly after the server's own now.
        assertEquals(
            SERVER_STANDS,
            decide(offlineRow, found(5_631.0, evening + 4 * hour, evening + 3 * hour), evening + 3 * hour),
        )

        // Nothing to order by.
        assertEquals(SERVER_STANDS, decide(offlineRow, found(5_631.0, null, evening + 3 * hour), evening + 3 * hour))
        assertEquals(SERVER_STANDS, decide(row(12_831.0, null), offlineServer, evening + 3 * hour))
        assertEquals(SERVER_STANDS, decide(row(12_831.0, null), found(5_631.0, evening, null), evening + 3 * hour))
    }

    @Test
    fun `server position falls back to progress times duration`() {
        val atStart = UserProgress(currentTime = 0.seconds, progress = 0.25)
        assertEquals(900.0, serverProgressPositionSeconds(atStart, durationSeconds = 3_600.0), 0.0)
        assertEquals(0.0, serverProgressPositionSeconds(atStart, durationSeconds = null), 0.0)
        assertEquals(
            42.0,
            serverProgressPositionSeconds(UserProgress(currentTime = 42.seconds, progress = 0.5), 3_600.0),
            0.0,
        )
    }

    /** What the flush does to one book's queue for each server answer. */
    private class FakeFlush(private val read: ServerProgressRead, private val pushAnswers: Boolean = true) {
        var pushes = 0
        var acknowledged = false
        var adopted: UserProgress? = null

        suspend fun settle(row: QueuedProgressFacts, phoneNow: Long): Boolean =
            settleQueuedProgress(
                row = row,
                readServer = { read },
                phoneNowMs = { phoneNow },
                push = {
                    pushes++
                    pushAnswers
                },
                acknowledge = { acknowledged = true },
                adoptServerProgress = { adopted = it },
            )
    }

    @Test
    fun `server that cannot be asked keeps the queue`() = runBlocking {
        val flush = FakeFlush(ServerProgressRead.Unavailable)

        assertFalse(flush.settle(row(5_631.0, evening), evening + hour))
        assertEquals(0, flush.pushes)
        assertFalse(flush.acknowledged)
        assertNull(flush.adopted)
    }

    @Test
    fun `newer listening here is pushed and acknowledged only when it lands`() = runBlocking {
        val server = found(5_631.0, lastUpdate = evening, serverNow = evening + 3 * hour)
        val landed = FakeFlush(server)
        val refused = FakeFlush(server, pushAnswers = false)

        assertTrue(landed.settle(row(12_831.0, evening + 2 * hour), evening + 3 * hour))
        assertFalse(refused.settle(row(12_831.0, evening + 2 * hour), evening + 3 * hour))

        assertEquals(1, landed.pushes)
        assertTrue(landed.acknowledged)
        assertEquals(1, refused.pushes)
        assertFalse(refused.acknowledged)
        assertNull(landed.adopted)
    }

    @Test
    fun `spot already on the server clears the queue without a write`() = runBlocking {
        val flush = FakeFlush(found(5_631.0, evening + 3 * hour, evening + 14 * hour))

        assertTrue(flush.settle(row(5_631.0, evening), evening + 14 * hour))
        assertEquals(0, flush.pushes)
        assertTrue(flush.acknowledged)
        assertNull(flush.adopted)
    }

    @Test
    fun `newer spot from another device is adopted and never overwritten`() = runBlocking {
        val server = found(13_384.0, lastUpdate = evening + 3 * hour, serverNow = evening + 14 * hour)
        val flush = FakeFlush(server)

        assertTrue(flush.settle(row(5_631.0, evening), evening + 14 * hour))
        assertEquals(0, flush.pushes)
        assertEquals(server.progress, flush.adopted)
    }
}
