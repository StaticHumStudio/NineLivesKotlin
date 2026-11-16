package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.local.entity.PendingProgressEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How queued progress rows relate to the durable row: legacy promotion,
 * superseded rows, and the import gate.
 */
class PendingProgressDurationTest {

    private fun row(id: Long, ts: String, currentTime: Double, duration: Double) =
        PendingProgressEntity(
            id = id,
            itemId = "item-1",
            currentTime = currentTime,
            isFinished = 0,
            timestamp = ts,
            duration = duration,
            isAtomic = 1,
        )

    @Test
    fun `a live push needs a length or a finish, a queued spot does not`() {
        // PATCH computes the fraction from the length. The offline session
        // sync takes the length from the server, so the queue never waits on it.
        assertEquals(false, progressCanBeDelivered(isFinished = false, duration = 0.0))
        assertEquals(true, progressCanBeDelivered(isFinished = true, duration = 0.0))
        assertEquals(true, progressCanBeDelivered(isFinished = false, duration = 3600.0))
    }

    @Test
    fun `pending local write blocks server progress import regardless of clock`() {
        assertEquals(false, serverProgressMayReplaceLocal(hasPendingProgress = true))
        assertEquals(true, serverProgressMayReplaceLocal(hasPendingProgress = false))
    }

    @Test
    fun `legacy non atomic queue survives a different durable state after upgrade`() {
        val queued = listOf(
            row(1, "2026-07-01T10:05:00Z", currentTime = 100.0, duration = 3600.0)
                .copy(isAtomic = 0),
        )
        val durable = PlaybackProgressEntity(
            audioBookId = "item-1",
            positionSeconds = 110.0,
            isFinished = 0,
            updatedAt = "2026-07-01T10:00:00Z",
        )

        assertEquals(false, queuedRowsAreSuperseded(queued, durable))
    }

    @Test
    fun `legacy queue is promoted from durable progress and queued duration`() {
        val queued = listOf(
            row(1, "2026-07-01T10:05:00Z", currentTime = 100.0, duration = 3600.0)
                .copy(isAtomic = 0),
        )
        val durable = PlaybackProgressEntity(
            audioBookId = "item-1",
            positionSeconds = 110.0,
            isFinished = 0,
            updatedAt = "2026-07-01T10:06:00Z",
        )

        assertEquals(
            LegacyProgressSnapshot(currentTime = 110.0, isFinished = false, duration = 3600.0),
            legacyProgressSnapshot(queued, durable),
        )
    }

    @Test
    fun `atomic queue is never treated as legacy`() {
        val queued = listOf(
            row(1, "2026-07-01T10:05:00Z", currentTime = 100.0, duration = 3600.0),
        )

        assertNull(legacyProgressSnapshot(queued, durableProgress = null))
    }

    @Test
    fun `different durable state supersedes a queue created by atomic delivery`() {
        val queued = listOf(
            row(1, "2026-07-01T10:05:00Z", currentTime = 100.0, duration = 3600.0)
                .copy(isAtomic = 1),
        )
        val durable = PlaybackProgressEntity(
            audioBookId = "item-1",
            positionSeconds = 110.0,
            isFinished = 0,
            updatedAt = "2026-07-01T10:00:00Z",
        )

        assertEquals(true, queuedRowsAreSuperseded(queued, durable))
    }

    @Test
    fun `matching durable state keeps crash-safe queue eligible after restart`() {
        val queued = listOf(
            row(1, "2026-07-01T10:05:00Z", currentTime = 100.0, duration = 3600.0),
        )
        val durable = PlaybackProgressEntity(
            audioBookId = "item-1",
            positionSeconds = 100.0,
            isFinished = 0,
            updatedAt = "2026-07-01T10:00:00Z",
        )

        assertEquals(false, queuedRowsAreSuperseded(queued, durable))
    }

    @Test
    fun `durable reconciliation uses insertion order for legacy rows across clock rollback`() {
        val queued = listOf(
            row(1, "2026-07-01T10:05:00Z", currentTime = 100.0, duration = 3600.0),
            row(2, "2026-07-01T10:00:00Z", currentTime = 110.0, duration = 3600.0),
        )
        val durable = PlaybackProgressEntity(
            audioBookId = "item-1",
            positionSeconds = 110.0,
            isFinished = 0,
            updatedAt = "2026-07-01T10:00:00Z",
        )

        assertEquals(false, queuedRowsAreSuperseded(queued, durable))
    }
}
