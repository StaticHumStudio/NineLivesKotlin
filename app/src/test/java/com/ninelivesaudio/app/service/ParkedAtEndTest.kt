package com.ninelivesaudio.app.service

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
 * A finished book reopens parked at its end (Jeff decided 2026-11-02). The
 * screen shows the end, the player waits paused at 0:00 so it never reaches
 * its end on load, Play starts the book over, and a seek back resumes it.
 */
class ParkedAtEndTest {

    private val end = 10.hours

    @Test
    fun `a finished book parks paused even when the load asked to play`() {
        val placement = loadPlacement(end, end, autoPlay = true)
        assertTrue(placement.parkedAtEnd)
        assertFalse(placement.playWhenReady)
        assertEquals(end, placement.shownPosition)
        // The player itself waits at the start, where Play begins the book again.
        assertEquals(Duration.ZERO, placement.playerStart)
    }

    @Test
    fun `a parked book shows the player's own end when its tracks run a little short`() {
        val trackSum = end - 300.milliseconds
        assertEquals(trackSum, loadPlacement(end, end, autoPlay = false, shownEnd = trackSum).shownPosition)
    }

    @Test
    fun `a mid book load starts where it was and keeps the load's play request`() {
        assertEquals(LoadPlacement(3.minutes, 3.minutes, parkedAtEnd = false, playWhenReady = true), loadPlacement(3.minutes, end, autoPlay = true))
        assertEquals(LoadPlacement(3.minutes, 3.minutes, parkedAtEnd = false, playWhenReady = false), loadPlacement(3.minutes, end, autoPlay = false))
    }

    @Test
    fun `a book with no known length never parks`() {
        assertFalse(loadPlacement(end, Duration.ZERO, autoPlay = true).parkedAtEnd)
    }

    @Test
    fun `the load keeps a finished book finished when it saves its place`() {
        // The load's save used to write every book as unfinished.
        assertTrue(loadSavesFinished(end, end))
        assertTrue(loadSavesFinished(end - 500.milliseconds, end))
        assertFalse(loadSavesFinished(3.minutes, end))
        assertFalse(loadSavesFinished(end, Duration.ZERO))
    }

    @Test
    fun `the load keeps a saved finished flag when it saves the same position`() {
        // Tracks short of the server length: finished at the track end, which
        // the server length does not count as the end.
        val trackEnd = end - 2.seconds
        assertTrue(loadSavesFinished(trackEnd, end, savedPosition = trackEnd, savedFinished = true))
        // A different position is a new place and follows the end rule.
        assertFalse(loadSavesFinished(3.minutes, end, savedPosition = trackEnd, savedFinished = true))
        assertFalse(loadSavesFinished(trackEnd, end, savedPosition = trackEnd, savedFinished = false))
        assertFalse(loadSavesFinished(trackEnd, end, savedPosition = null, savedFinished = false))
    }

    @Test
    fun `skip forward or next chapter from the end keeps the book parked`() {
        assertEquals(ParkedSeek.STAY_PARKED, parkedSeek(end, end))
        assertEquals(ParkedSeek.STAY_PARKED, parkedSeek(end + 30.seconds, end))
        assertEquals(ParkedSeek.STAY_PARKED, parkedSeek(end - 500.milliseconds, end))
    }

    @Test
    fun `a seek back from the end resumes the book there`() {
        assertEquals(ParkedSeek.RESUME_FROM_TARGET, parkedSeek(end - 10.seconds, end))
        assertEquals(ParkedSeek.RESUME_FROM_TARGET, parkedSeek(Duration.ZERO, end))
    }

    @Test
    fun `the end is judged against the shorter of the server length and the player's tracks`() {
        // Tracks 2 s shorter than the server says. A book saved at the end of
        // its tracks is finished, or the player would prepare at its own end
        // and finish the book again.
        val trackTotal = end - 2.seconds
        assertEquals(trackTotal, bookEndLength(serverLength = end, trackTotal = trackTotal))
        assertEquals(end, bookEndLength(serverLength = end, trackTotal = end + 2.seconds))
        assertEquals(end, bookEndLength(serverLength = end, trackTotal = Duration.ZERO))
        assertEquals(trackTotal, bookEndLength(serverLength = Duration.ZERO, trackTotal = trackTotal))
        assertTrue(loadPlacement(trackTotal, bookEndLength(end, trackTotal), autoPlay = true, shownEnd = trackTotal).parkedAtEnd)
    }

    @Test
    fun `a seek into a track is the book position after the tracks before it`() {
        // Cumulative track ends in seconds, as the player keeps them.
        val ends = listOf(600.0, 1_500.0, 3_600.0)
        assertEquals(90.seconds, bookPositionOfTrack(ends, 0, 90_000L))
        assertEquals(1_590.seconds, bookPositionOfTrack(ends, 2, 90_000L))
        assertEquals(3_600.seconds, bookPositionOfTrack(ends, 2, 2_100_000L))
        // No track list (one file): the position is the book's.
        assertEquals(90.seconds, bookPositionOfTrack(emptyList(), 0, 90_000L))
    }

    @Test
    fun `a parked book judges seeks against the same end it parked at`() {
        // Tracks 3 s longer than the server says. The last chapter ends at the
        // server length, so Next to it stays parked.
        val parkedEnd = bookEndLength(serverLength = end, trackTotal = end + 3.seconds)
        assertEquals(ParkedSeek.STAY_PARKED, parkedSeek(end, parkedEnd))
    }

    @Test
    fun `a load made to play starts a parked book over`() {
        assertTrue(playAfterLoad(loaded = true, parked = true, currentBookId = "b", loadedBookId = "b"))
        assertFalse(playAfterLoad(loaded = true, parked = false, currentBookId = "b", loadedBookId = "b"))
        assertFalse(playAfterLoad(loaded = false, parked = true, currentBookId = "b", loadedBookId = "b"))
        // Another book took the player meanwhile.
        assertFalse(playAfterLoad(loaded = true, parked = true, currentBookId = "other", loadedBookId = "b"))
    }
}
