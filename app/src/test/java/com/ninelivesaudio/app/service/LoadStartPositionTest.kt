package com.ninelivesaudio.app.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

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
}
