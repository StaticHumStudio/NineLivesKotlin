package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.local.entity.PendingProgressEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.data.remote.ServerProgressRead
import com.ninelivesaudio.app.data.repository.QueuedProgressDecision.ALREADY_ON_SERVER
import com.ninelivesaudio.app.data.repository.QueuedProgressDecision.PUSH
import com.ninelivesaudio.app.data.repository.QueuedProgressDecision.SERVER_STANDS
import com.ninelivesaudio.app.domain.model.UserProgress
import com.ninelivesaudio.app.domain.util.toEpochMillis
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
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

        // The server stands only when it is later than the row by more than
        // the margin. Exactly the margin is not evidence enough.
        assertEquals(PUSH, decide(row(120.0, evening), found(100.0, evening + 30_000, evening + minute), evening + minute))
        assertEquals(
            SERVER_STANDS,
            decide(row(120.0, evening), found(100.0, evening + 30_001, evening + minute), evening + minute),
        )
        // A finishing save stranded just before another device's update still
        // lands, so the book is not turned back into an unfinished one.
        assertEquals(
            PUSH,
            decide(
                row(36_000.0, evening, finished = true),
                found(35_990.0, lastUpdate = evening + 20_000, serverNow = evening + minute),
                evening + minute,
            ),
        )

        // No Date header: the server's lastUpdate meets the row's own time.
        assertEquals(PUSH, decide(row(12_831.0, evening + 2 * hour), found(5_631.0, evening, null), evening + 3 * hour))
        assertEquals(SERVER_STANDS, decide(issueRow, found(13_384.0, evening + 3 * hour, null), evening + 14 * hour))
        assertEquals(PUSH, decide(row(120.0, evening), found(100.0, evening + 20_000, null), evening + minute))
        assertEquals(PUSH, decide(row(120.0, evening), found(100.0, evening + 30_000, null), evening + minute))
        assertEquals(SERVER_STANDS, decide(row(120.0, evening), found(100.0, evening + 30_001, null), evening + minute))

        // A Date header rounded down to the second reads a hair before
        // lastUpdate. That age is zero, and the hour old row is still older.
        assertEquals(SERVER_STANDS, decide(row(120.0, evening - hour), found(100.0, evening, evening - 1_500), evening))
        // A server lastUpdate clearly after the server's own now is no
        // evidence, so the row goes out.
        assertEquals(
            PUSH,
            decide(row(12_831.0, evening + 3 * hour), found(5_631.0, evening + 4 * hour, evening + 3 * hour), evening + 3 * hour),
        )
        // A row stamped ten minutes in the phone's future: the phone clock
        // moved back, which is no evidence either.
        assertEquals(PUSH, decide(row(5_631.0, evening + 14 * hour + 10 * minute), issueServer, evening + 14 * hour))

        // Nothing to order by.
        assertEquals(PUSH, decide(offlineRow, found(5_631.0, null, evening + 3 * hour), evening + 3 * hour))
        assertEquals(PUSH, decide(row(12_831.0, null), offlineServer, evening + 3 * hour))
        assertEquals(PUSH, decide(row(12_831.0, null), found(5_631.0, evening, null), evening + 3 * hour))
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

    @Test
    fun `phone time is taken before the server answers`() = runBlocking {
        // The server's Date is stamped when it answers. A slow answer must not
        // count as age on our side.
        var phoneClock = evening + 30_000
        var pushes = 0
        val settled = settleQueuedProgress(
            row = row(120.0, evening),
            readServer = {
                phoneClock += 40_000
                found(100.0, lastUpdate = evening + 60_000, serverNow = evening + 70_000)
            },
            phoneNowMs = { phoneClock },
            push = {
                pushes++
                true
            },
            acknowledge = {},
            adoptServerProgress = {},
        )

        assertTrue(settled)
        assertEquals(1, pushes)
    }

    @Test
    fun `a server read that never answers keeps the queue`() = runBlocking {
        var pushes = 0
        val settled = settleQueuedProgress(
            row = row(120.0, evening),
            readServer = { awaitCancellation() },
            phoneNowMs = { evening },
            push = {
                pushes++
                true
            },
            acknowledge = {},
            adoptServerProgress = {},
            readTimeout = 50.milliseconds,
        )

        assertFalse(settled)
        assertEquals(0, pushes)
        assertEquals(10.seconds, QUEUED_PROGRESS_READ_TIMEOUT)
    }

    @Test
    fun `a promoted legacy row keeps the time it was saved`() {
        val legacy = PendingProgressEntity(
            id = 7,
            itemId = "item-1",
            currentTime = 5_631.0,
            isFinished = 0,
            timestamp = "2026-11-14T22:00:00Z",
            duration = 36_000.0,
            isAtomic = 0,
        )
        val savedAt = "2026-11-14T22:00:00Z".toEpochMillis()!!
        val muchLater = savedAt + 14 * hour

        assertEquals(savedAt, legacyPromotionTimestampMs(listOf(legacy), durableProgress = null, nowMs = muchLater))
        // A later durable write is the newer local act.
        val durable = PlaybackProgressEntity("item-1", 5_640.0, 0, "2026-11-14T22:05:00Z")
        assertEquals(
            savedAt + 5 * minute,
            legacyPromotionTimestampMs(listOf(legacy), durable, nowMs = muchLater),
        )
        // Nothing parseable: now, which delivers the row as master does.
        assertEquals(
            muchLater,
            legacyPromotionTimestampMs(listOf(legacy.copy(timestamp = "garbage")), durable.copy(updatedAt = null), muchLater),
        )

        // The issue's shape through a legacy row: another device moved the book
        // after the row was saved, so the server's spot stands.
        val promoted = QueuedProgressFacts(
            positionSeconds = 5_631.0,
            isFinished = false,
            durationSeconds = 36_000.0,
            timestampMs = legacyPromotionTimestampMs(listOf(legacy), null, muchLater),
        )
        assertEquals(
            SERVER_STANDS,
            decideQueuedProgress(promoted, found(13_384.0, savedAt + 3 * hour, savedAt + 14 * hour), muchLater),
        )
    }

    @Test
    fun `server spot lands locally only on a book nothing is playing`() = runBlocking {
        for (inactive in listOf(true, false)) {
            var rowsDeleted = false
            var keptLocally = false
            applyServerSpot(
                itemIsInactive = inactive,
                deleteQueuedRows = { rowsDeleted = true },
                keepServerSpotLocally = { keptLocally = true },
            )

            assertTrue("inactive $inactive", rowsDeleted)
            assertEquals("inactive $inactive", inactive, keptLocally)
        }
    }
}
