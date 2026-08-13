package com.ninelivesaudio.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStatePolicyTest {

    @Test
    fun `ready player follows controller play when ready changes`() {
        assertEquals(PlaybackState.PLAYING, playbackStateForReadyPlayer(playWhenReady = true))
        assertEquals(PlaybackState.PAUSED, playbackStateForReadyPlayer(playWhenReady = false))
    }
}
