package com.ninelivesaudio.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgressWritePlanTest {

    @Test
    fun `remote pause without a server session keeps durable delivery and shelf progress`() {
        assertEquals(
            PlaybackProgressWritePlan(
                useAtomicDelivery = true,
                updateAudioBook = true,
                useTerminalPath = false,
            ),
            playbackProgressWritePlan(isLocal = false, hasServerSession = false),
        )
    }

    @Test
    fun `local and session backed pauses keep the terminal path`() {
        assertEquals(
            PlaybackProgressWritePlan(
                useAtomicDelivery = false,
                updateAudioBook = true,
                useTerminalPath = true,
            ),
            playbackProgressWritePlan(isLocal = false, hasServerSession = true),
        )
        assertEquals(
            PlaybackProgressWritePlan(
                useAtomicDelivery = false,
                updateAudioBook = true,
                useTerminalPath = true,
            ),
            playbackProgressWritePlan(isLocal = true, hasServerSession = false),
        )
    }
}
