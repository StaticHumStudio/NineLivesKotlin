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
}
