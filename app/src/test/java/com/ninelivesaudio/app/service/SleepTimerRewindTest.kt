package com.ninelivesaudio.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The sleep timer's rewind only applies to a book that is playing. A seek on
 * a paused book (a finished one parked at its end most of all) would move it,
 * and on a parked book it would resume the book and save it as unfinished.
 */
class SleepTimerRewindTest {

    @Test
    fun `a playing book rewinds when the timer runs out`() {
        assertEquals(1.hours - 15.seconds, sleepTimerRewindTarget(PlaybackState.PLAYING, 1.hours, 15))
        assertEquals(1.hours - 15.seconds, sleepTimerRewindTarget(PlaybackState.BUFFERING, 1.hours, 15))
        assertEquals(Duration.ZERO, sleepTimerRewindTarget(PlaybackState.PLAYING, 5.seconds, 15))
    }

    @Test
    fun `a paused or parked book is left where it is`() {
        assertNull(sleepTimerRewindTarget(PlaybackState.PAUSED, 1.hours, 15))
        assertNull(sleepTimerRewindTarget(PlaybackState.STOPPED, 1.hours, 15))
        assertNull(sleepTimerRewindTarget(PlaybackState.LOADING, 1.hours, 15))
    }

    @Test
    fun `no rewind setting means no seek`() {
        assertNull(sleepTimerRewindTarget(PlaybackState.PLAYING, 1.hours, 0))
    }
}
