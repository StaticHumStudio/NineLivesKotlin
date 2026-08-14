package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.local.entity.PendingProgressEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The offline progress queue must carry `duration` to the server. Before this
 * fix the flush pushed the 3-arg updateProgress (duration defaulting to 0), so a
 * book finished/advanced offline synced back as 0% / not-finished. latestPushArgs
 * picks the newest queued row per item and preserves its duration.
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
    fun `latest inserted row supplies currentTime and duration across clock rollback`() {
        val rows = listOf(
            row(1, "2026-07-01T10:05:00Z", currentTime = 100.0, duration = 3600.0),
            row(2, "2026-07-01T10:00:00Z", currentTime = 250.0, duration = 3600.0),
        )
        val push = latestPushArgs(rows)!!
        assertEquals(250.0, push.currentTime, 0.0)
        assertEquals(3600.0, push.duration, 0.0) // duration carried, not dropped to 0
        assertEquals(false, push.isFinished)
    }

    @Test
    fun `empty rows produce no push`() {
        assertNull(latestPushArgs(emptyList()))
    }

    @Test
    fun `unknown duration remains queued instead of resetting server progress`() {
        assertNull(
            latestPushArgs(
                listOf(row(1, "2026-07-01T10:05:00Z", currentTime = 250.0, duration = 0.0)),
            ),
        )
    }

    @Test
    fun `finished progress can push without duration`() {
        val finished = row(1, "2026-07-01T10:05:00Z", currentTime = 250.0, duration = 0.0)
            .copy(isFinished = 1)

        assertEquals(true, latestPushArgs(listOf(finished))?.isFinished)
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
        assertNull(latestPushArgs(queued))
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
