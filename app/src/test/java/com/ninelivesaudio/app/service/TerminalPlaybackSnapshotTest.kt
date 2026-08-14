package com.ninelivesaudio.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class TerminalPlaybackSnapshotTest {

    @Test
    fun `terminal snapshot remains tied to stopped book after immediate load`() {
        var liveBookId = "old-book"
        var livePosition = 42.seconds
        var liveSessionId: String? = "old-session"

        val snapshot = terminalPlaybackSnapshot(
            bookId = liveBookId,
            position = livePosition,
            duration = 100.seconds,
            isFinished = false,
            serverSessionId = liveSessionId,
            timeListened = 18.0,
        )

        liveBookId = "new-book"
        livePosition = 3.seconds
        liveSessionId = "new-session"

        assertEquals("old-book", snapshot.bookId)
        assertEquals(42.seconds, snapshot.position)
        assertEquals("old-session", snapshot.serverSessionId)
        assertFalse(snapshot.isFinished)
        assertEquals("new-book", liveBookId)
        assertEquals(3.seconds, livePosition)
        assertEquals("new-session", liveSessionId)
    }

    @Test
    fun `pause snapshot remains tied to pause state after immediate resume`() {
        var livePosition = 42.seconds
        var liveTimeListened = 18.0

        val snapshot = playbackProgressSnapshot(
            bookId = "book-a",
            isLocal = false,
            position = livePosition,
            duration = 100.seconds,
            serverSessionId = "session-a",
            serverTimeListened = liveTimeListened,
            localSessionId = null,
            localTimeListened = 0.0,
        )

        livePosition = 50.seconds
        liveTimeListened = 0.0

        assertEquals(42.seconds, snapshot.position)
        assertEquals(18.0, snapshot.serverTimeListened, 0.0)
        assertEquals(50.seconds, livePosition)
        assertEquals(0.0, liveTimeListened, 0.0)
    }

    @Test
    fun `rapid resume preserves listening time captured by the prior pause`() {
        val firstPauseTotal = foldListeningTime(
            accumulatedSeconds = 0.0,
            lastTimestampMs = 1_000L,
            nowTimestampMs = 7_000L,
            maxElapsedSeconds = 60.0,
        )
        val secondPauseTotal = foldListeningTime(
            accumulatedSeconds = firstPauseTotal,
            lastTimestampMs = 8_000L,
            nowTimestampMs = 10_000L,
            maxElapsedSeconds = 60.0,
        )

        assertEquals(6.0, firstPauseTotal, 0.0)
        assertEquals(8.0, secondPauseTotal, 0.0)
        assertEquals(
            8.0,
            foldListeningTime(
                accumulatedSeconds = secondPauseTotal,
                lastTimestampMs = 0L,
                nowTimestampMs = 70_000L,
                maxElapsedSeconds = 60.0,
            ),
            0.0,
        )
    }
}
