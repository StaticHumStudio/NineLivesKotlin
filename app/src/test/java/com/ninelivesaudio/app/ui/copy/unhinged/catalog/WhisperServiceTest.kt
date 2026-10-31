package com.ninelivesaudio.app.ui.copy.unhinged.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperServiceTest {

    private var now = 1_000_000L
    private val service = WhisperService { now }

    @Test fun `the first launch whisper shows`() {
        assertTrue(service.tryShowWhisper(WhisperContext.APP_OPENED))
        assertTrue(service.currentWhisper.value != null)
    }

    @Test fun `a second launch whisper waits the full 15 minutes`() {
        val t = now
        assertTrue(service.tryShowWhisper(WhisperContext.APP_OPENED))
        now = t + LAUNCH_WHISPER_COOLDOWN_MS - 1
        assertFalse(service.tryShowWhisper(WhisperContext.APP_OPENED))
        now = t + LAUNCH_WHISPER_COOLDOWN_MS
        assertTrue(service.tryShowWhisper(WhisperContext.APP_OPENED))
    }

    @Test fun `an event whisper can show 30 seconds after the launch whisper`() {
        val t = now
        assertTrue(service.tryShowWhisper(WhisperContext.APP_OPENED))
        now = t + MIN_WHISPER_GAP_MS - 1
        assertFalse(service.tryShowWhisper(WhisperContext.CHAPTER_FINISHED))
        now = t + MIN_WHISPER_GAP_MS
        assertTrue(service.tryShowWhisper(WhisperContext.CHAPTER_FINISHED))
    }

    @Test fun `event whispers are at most one per 20 minutes across every event context`() {
        val t = now
        assertTrue(service.tryShowWhisper(WhisperContext.PLAYBACK_RESUMED))
        now = t + EVENT_WHISPER_COOLDOWN_MS - 1
        assertFalse(service.tryShowWhisper(WhisperContext.DOWNLOAD_COMPLETED))
        now = t + EVENT_WHISPER_COOLDOWN_MS
        assertTrue(service.tryShowWhisper(WhisperContext.IDLE))
    }

    @Test fun `an event whisper does not push back the next launch whisper`() {
        val t = now
        assertTrue(service.tryShowWhisper(WhisperContext.APP_OPENED))
        now = t + 5 * 60_000L
        assertTrue(service.tryShowWhisper(WhisperContext.BOOK_FINISHED))
        now = t + LAUNCH_WHISPER_COOLDOWN_MS
        assertTrue(service.tryShowWhisper(WhisperContext.APP_OPENED))
    }

    @Test fun `whispers off refuses every context and spends no budget`() {
        assertFalse(service.tryShowWhisper(WhisperContext.IDLE, enabled = false))
        assertNull(service.currentWhisper.value)
        assertTrue(service.tryShowWhisper(WhisperContext.IDLE, enabled = true))
    }

    @Test fun `reduced motion refuses every context`() {
        for (c in WhisperContext.values()) {
            assertFalse("$c", service.tryShowWhisper(c, reduceMotion = true))
        }
        assertNull(service.currentWhisper.value)
    }

    @Test fun `each context picks from its own pool`() {
        for (c in WhisperContext.values()) {
            now += LAUNCH_WHISPER_COOLDOWN_MS + EVENT_WHISPER_COOLDOWN_MS
            assertTrue("$c", service.tryShowWhisper(c))
            assertTrue("$c", service.currentWhisper.value in WhisperCatalog.getWhispers(c))
        }
    }

    @Test fun `a refused whisper leaves the one on screen alone`() {
        assertTrue(service.tryShowWhisper(WhisperContext.APP_OPENED))
        val shown = service.currentWhisper.value
        now += 1_000
        assertFalse(service.tryShowWhisper(WhisperContext.IDLE))
        assertEquals(shown, service.currentWhisper.value)
    }

    @Test fun `only the launch context is not an event`() {
        assertFalse(WhisperContext.APP_OPENED.isEvent)
        assertTrue(WhisperContext.values().filter { it != WhisperContext.APP_OPENED }.all { it.isEvent })
    }
}
