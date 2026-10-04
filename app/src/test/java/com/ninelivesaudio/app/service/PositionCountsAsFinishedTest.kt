package com.ninelivesaudio.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pause save overtaken by a paused seek saves the seek's position. If that
 * position sits at the very end, the seek's own save marked the book finished,
 * and the pause save must apply the same rule or it marks the book unfinished.
 */
class PositionCountsAsFinishedTest {

    @Test
    fun `within one second of the end counts as finished`() {
        assertTrue(positionCountsAsFinished(currentTimeSec = 3599.6, durationSec = 3600.0))
    }

    @Test
    fun `one second or more from the end does not`() {
        assertFalse(positionCountsAsFinished(currentTimeSec = 3599.0, durationSec = 3600.0))
        assertFalse(positionCountsAsFinished(currentTimeSec = 50.0, durationSec = 3600.0))
    }

    @Test
    fun `an unknown duration never counts as finished`() {
        assertFalse(positionCountsAsFinished(currentTimeSec = 0.0, durationSec = 0.0))
    }

    @Test
    fun `a position past the end still counts as finished`() {
        assertTrue(positionCountsAsFinished(currentTimeSec = 3605.0, durationSec = 3600.0))
    }
}
