package com.ninelivesaudio.app.service

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStatePolicyTest {

    @Test
    fun `pause during active rebuffering stops and syncs playback work`() {
        assertEquals(
            PlaybackIntentTransition(
                state = PlaybackState.PAUSED,
                startPlaybackWork = false,
                stopPlaybackWork = true,
                syncPause = true,
            ),
            playbackIntentTransition(
                playerReady = false,
                playWhenReady = false,
                playbackWorkActive = true,
            ),
        )
    }

    @Test
    fun `pause during initial buffering does not invent playback work`() {
        assertEquals(
            PlaybackIntentTransition(
                state = PlaybackState.PAUSED,
                startPlaybackWork = false,
                stopPlaybackWork = false,
                syncPause = false,
            ),
            playbackIntentTransition(
                playerReady = false,
                playWhenReady = false,
                playbackWorkActive = false,
            ),
        )
    }

    @Test
    fun `ready callback after buffering pause repeats no bookkeeping`() {
        assertEquals(
            PlaybackIntentTransition(
                state = PlaybackState.PAUSED,
                startPlaybackWork = false,
                stopPlaybackWork = false,
                syncPause = false,
            ),
            playbackIntentTransition(
                playerReady = true,
                playWhenReady = false,
                playbackWorkActive = false,
            ),
        )
    }

    @Test
    fun `playing to paused assigns pause bookkeeping to one transition`() {
        assertEquals(
            PlaybackIntentTransition(
                state = PlaybackState.PAUSED,
                startPlaybackWork = false,
                stopPlaybackWork = true,
                syncPause = true,
            ),
            playbackIntentTransition(
                playerReady = true,
                playWhenReady = false,
                playbackWorkActive = true,
            ),
        )
    }

    @Test
    fun `repeated paused callback performs no bookkeeping`() {
        assertEquals(
            PlaybackIntentTransition(
                state = PlaybackState.PAUSED,
                startPlaybackWork = false,
                stopPlaybackWork = false,
                syncPause = false,
            ),
            playbackIntentTransition(
                playerReady = true,
                playWhenReady = false,
                playbackWorkActive = false,
            ),
        )
    }

    @Test
    fun `paused to playing starts work without pause sync`() {
        assertEquals(
            PlaybackIntentTransition(
                state = PlaybackState.PLAYING,
                startPlaybackWork = true,
                stopPlaybackWork = false,
                syncPause = false,
            ),
            playbackIntentTransition(
                playerReady = true,
                playWhenReady = true,
                playbackWorkActive = false,
            ),
        )
    }

    @Test
    fun `ready after active rebuffering does not restart playback work`() {
        assertEquals(
            PlaybackIntentTransition(
                state = PlaybackState.PLAYING,
                startPlaybackWork = false,
                stopPlaybackWork = false,
                syncPause = false,
            ),
            playbackIntentTransition(
                playerReady = true,
                playWhenReady = true,
                playbackWorkActive = true,
            ),
        )
    }

    @Test
    fun `ended resume stays buffering until the player becomes ready`() {
        assertEquals(
            PlaybackIntentTransition(
                state = PlaybackState.BUFFERING,
                startPlaybackWork = false,
                stopPlaybackWork = false,
                syncPause = false,
            ),
            playbackIntentTransition(
                playerReady = false,
                playWhenReady = true,
                playbackWorkActive = false,
            ),
        )
    }

    @Test
    fun `completed child job does not count as active playback work`() {
        val completedJob = Job().apply { complete() }

        assertFalse(playbackWorkActive(positionPollingJob = completedJob, sessionSyncJob = null))
    }

    @Test
    fun `live child job counts as active playback work`() {
        val activeJob = Job()
        try {
            assertTrue(playbackWorkActive(positionPollingJob = null, sessionSyncJob = activeJob))
        } finally {
            activeJob.cancel()
        }
    }
}
