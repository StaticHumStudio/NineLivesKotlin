package com.ninelivesaudio.app.service

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class PlaybackTerminationTest {
    @Test
    fun `completed multi track book replays from first track start`() {
        assertEquals(PlaybackResumePoint(0, 0), playbackResumePoint(Player.STATE_ENDED, 7, 40_000))
    }

    @Test
    fun `error retry preserves the interrupted track and position`() {
        assertEquals(PlaybackResumePoint(7, 40_000), playbackResumePoint(Player.STATE_IDLE, 7, 40_000))
    }

    @Test
    fun `error at end of file does not mark a book finished`() {
        assertFalse(finishedAtTermination(PlaybackTermination.ERROR, 100.seconds, 100.seconds))
    }

    @Test
    fun `natural completion and an explicit stop near end retain completion semantics`() {
        assertTrue(finishedAtTermination(PlaybackTermination.COMPLETED, 99.seconds, 100.seconds))
        assertTrue(finishedAtTermination(PlaybackTermination.STOP, 99.5.seconds, 100.seconds))
        assertFalse(finishedAtTermination(PlaybackTermination.STOP, 10.seconds, 100.seconds))
        assertFalse(finishedAtTermination(PlaybackTermination.STOP, 0.seconds, 0.seconds))
    }

    @Test
    fun `resume prepares idle media after an error and ended media after completion`() {
        assertTrue(needsPlaybackPreparation(Player.STATE_IDLE))
        assertTrue(needsPlaybackPreparation(Player.STATE_ENDED))
        assertFalse(needsPlaybackPreparation(Player.STATE_READY))
        assertFalse(needsPlaybackPreparation(Player.STATE_BUFFERING))
    }
}
