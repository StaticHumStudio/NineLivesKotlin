package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.local.entity.PendingProgressEntity
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
        )

    @Test
    fun `latest row by timestamp supplies currentTime and duration`() {
        val rows = listOf(
            row(1, "2026-07-01T10:00:00Z", currentTime = 100.0, duration = 3600.0),
            row(2, "2026-07-01T10:05:00Z", currentTime = 250.0, duration = 3600.0),
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
}
