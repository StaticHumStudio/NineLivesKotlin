package com.ninelivesaudio.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackIntentOwnerTest {

    @Test
    fun `buffering pause owns stop timestamp and one progress flush`() {
        var state = PlaybackState.PLAYING
        var workActive = true
        val effects = mutableListOf<String>()
        val owner = PlaybackIntentOwner(
            currentState = { state },
            playbackWorkActive = { workActive },
            publishState = {
                state = it
                effects += "state:$it"
            },
            startPlaybackWork = { effects += "start" },
            stopPlaybackWork = {
                workActive = false
                effects += "stop"
            },
            markPaused = { effects += "timestamp" },
            syncPause = { effects += "sync" },
        )

        owner.update(playerReady = false, playWhenReady = false)
        owner.update(playerReady = true, playWhenReady = false)

        assertEquals(
            listOf("state:PAUSED", "stop", "timestamp", "sync"),
            effects,
        )
    }

    @Test
    fun `ready callback owns resume bookkeeping and work start`() {
        var state = PlaybackState.BUFFERING
        val effects = mutableListOf<String>()
        val owner = PlaybackIntentOwner(
            currentState = { state },
            playbackWorkActive = { false },
            publishState = {
                state = it
                effects += "state:$it"
            },
            startPlaybackWork = { effects += "resume-and-start" },
            stopPlaybackWork = { effects += "stop" },
            markPaused = { effects += "timestamp" },
            syncPause = { effects += "sync" },
        )

        owner.update(playerReady = true, playWhenReady = true)

        assertEquals(listOf("state:PLAYING", "resume-and-start"), effects)
    }

    @Test
    fun `pause invalidates an initial buffering load without active playback work`() {
        var state = PlaybackState.BUFFERING
        val effects = mutableListOf<String>()
        val owner = PlaybackIntentOwner(
            currentState = { state },
            playbackWorkActive = { false },
            publishState = {
                state = it
                effects += "state:$it"
            },
            startPlaybackWork = { effects += "start" },
            stopPlaybackWork = { effects += "stop" },
            markPaused = { effects += "timestamp" },
            syncPause = { effects += "sync" },
            pausePlaybackLifetime = { workWasActive -> effects += "invalidate:$workWasActive" },
        )

        owner.update(playerReady = false, playWhenReady = false)

        assertEquals(listOf("state:PAUSED", "invalidate:false"), effects)
    }
}
