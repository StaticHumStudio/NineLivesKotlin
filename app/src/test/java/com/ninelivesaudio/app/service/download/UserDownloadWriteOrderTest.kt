package com.ninelivesaudio.app.service.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pause, cancel and delete on the book the engine is streaming must stop the
 * engine BEFORE writing. DownloadEngine upserts a stale Downloading snapshot
 * about ten times a second, so a write that lands first gets clobbered by the
 * next progress tick (a cancelled row comes back, a paused one keeps going).
 */
class UserDownloadWriteOrderTest {

    @Test
    fun `an active download is stopped before the user's write lands`() = runBlocking {
        val events = mutableListOf<String>()
        writeAfterEngineStops(
            engineActive = true,
            stopEngine = { events += "stop" },
            write = { events += "write" },
        )
        assertEquals(listOf("stop", "write"), events)
    }

    @Test
    fun `an idle row is written without stopping the drain`() = runBlocking {
        val events = mutableListOf<String>()
        writeAfterEngineStops(
            engineActive = false,
            stopEngine = { events += "stop" },
            write = { events += "write" },
        )
        assertEquals(listOf("write"), events)
    }
}
