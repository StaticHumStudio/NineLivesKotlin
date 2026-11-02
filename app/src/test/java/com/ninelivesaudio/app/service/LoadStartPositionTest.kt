package com.ninelivesaudio.app.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Where a reloaded book starts (#106). A finished book starts over, and a
 * position held in memory from the end of a book never beats a saved spot
 * from after the restart.
 */
class LoadStartPositionTest {

    private val end = 10.hours

    /** Mirrors the load: in memory positions, then the saved one, then the end of book reset. */
    private fun startOfReload(bookTime: Duration, sessionTime: Duration, saved: Duration?): Duration = runBlocking {
        var written: Duration? = null
        val resolved = PlaybackProgressOwner().resolveAndSavePlaybackPosition(
            bookId = "book",
            candidate = latestInMemoryPosition(listOf(bookTime, sessionTime), end),
            readDurable = { saved },
            save = { written = it },
            bookDuration = end,
        )
        assertEquals(resolved, written)
        loadStartPosition(resolved, end)
    }

    @Test
    fun `a restarted book resumes its saved spot over a stale end position in memory`() {
        // Finished, restarted from 0:00, listened to 3:00. The book object
        // still says it is at the end.
        assertEquals(3.minutes, startOfReload(bookTime = end, sessionTime = Duration.ZERO, saved = 3.minutes))
    }

    @Test
    fun `a restarted book resumes its saved spot over a server session still at the end`() {
        assertEquals(3.minutes, startOfReload(bookTime = Duration.ZERO, sessionTime = end, saved = 3.minutes))
    }

    @Test
    fun `a book reloaded right after it finished starts over`() {
        assertEquals(Duration.ZERO, startOfReload(bookTime = end, sessionTime = end, saved = end))
    }

    @Test
    fun `a finished book with no saved row starts over`() {
        assertEquals(Duration.ZERO, startOfReload(bookTime = end, sessionTime = Duration.ZERO, saved = null))
    }

    @Test
    fun `a newer position from the server still beats an older saved one`() {
        assertEquals(2.hours, startOfReload(bookTime = 1.hours, sessionTime = 2.hours, saved = 1.hours))
    }

    @Test
    fun `a newer saved position still beats an older one in memory`() {
        assertEquals(2.hours, startOfReload(bookTime = 1.hours, sessionTime = Duration.ZERO, saved = 2.hours))
    }

    @Test
    fun `an in memory end position does not hide a mid book one`() {
        assertEquals(3.minutes, latestInMemoryPosition(listOf(end, 3.minutes), end))
    }

    @Test
    fun `a book with no known length keeps the larger position`() {
        assertEquals(end, latestInMemoryPosition(listOf(end, 3.minutes), Duration.ZERO))
    }
    // A stop in the last second saves the book as finished, so the load
    // treats the same window as the end (one shared rule).

    @Test
    fun `a stale position half a second from the end does not beat the saved spot`() {
        // Stopped at the end minus 0.5 s (saved as finished), restarted, listened 10 min.
        val nearEnd = end - 500.milliseconds
        assertEquals(10.minutes, startOfReload(bookTime = nearEnd, sessionTime = Duration.ZERO, saved = 10.minutes))
        assertEquals(10.minutes, startOfReload(bookTime = Duration.ZERO, sessionTime = nearEnd, saved = 10.minutes))
    }

    @Test
    fun `a book stopped half a second from the end starts over`() {
        val nearEnd = end - 500.milliseconds
        assertEquals(Duration.ZERO, startOfReload(bookTime = nearEnd, sessionTime = nearEnd, saved = nearEnd))
        assertEquals(Duration.ZERO, loadStartPosition(nearEnd, end))
    }

    @Test
    fun `the load and the stop share one end of book window`() {
        val nearEnd = end - 500.milliseconds
        assertTrue(finishedAtTermination(PlaybackTermination.STOP, nearEnd, end))
        assertTrue(positionAtBookEnd(nearEnd, end))
        assertFalse(positionAtBookEnd(end - 1500.milliseconds, end))
        assertFalse(finishedAtTermination(PlaybackTermination.STOP, end - 1500.milliseconds, end))
    }

    @Test
    fun `a track sum a little shorter than the server length still counts as the end`() {
        // The player's duration is the sum of its tracks, the load's is the server's.
        val trackSumEnd = end - 300.milliseconds
        assertTrue(positionAtBookEnd(trackSumEnd, end))
        assertEquals(10.minutes, startOfReload(bookTime = trackSumEnd, sessionTime = Duration.ZERO, saved = 10.minutes))
        // A track sum a little longer is past the server end, which is the end too.
        assertEquals(Duration.ZERO, loadStartPosition(end + 300.milliseconds, end))
    }
}
