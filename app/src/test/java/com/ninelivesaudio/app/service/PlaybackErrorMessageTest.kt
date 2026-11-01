package com.ninelivesaudio.app.service

import androidx.media3.common.PlaybackException
import com.ninelivesaudio.app.domain.model.AudioBook
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorMessageTest {
    @Test
    fun `an error event shows its message`() {
        assertEquals("Boom.", PlaybackEvent.Error("Boom.").userMessage())
    }

    @Test
    fun `a blank error shows a generic message instead of nothing`() {
        assertEquals(GENERIC_PLAYBACK_ERROR, PlaybackEvent.Error("  ").userMessage())
    }

    @Test
    fun `events that are not errors show nothing`() {
        assertNull(PlaybackEvent.BookFinished.userMessage())
        assertNull(PlaybackEvent.TrackChanged(2).userMessage())
        assertNull(PlaybackEvent.BookLoaded(AudioBook(id = "b")).userMessage())
    }

    @Test
    fun `a cancelled load is not an error`() {
        assertNull(loadFailureMessage(CancellationException("book switch")))
    }

    @Test
    fun `a failed load never leaks the exception text`() {
        val message = loadFailureMessage(IllegalStateException("java.net.SocketException: x"))!!
        assertFalse(message.contains("Socket"))
        assertFalse(message.contains("null"))
    }

    @Test
    fun `player errors read as plain words by cause`() {
        assertTrue(playerErrorMessage(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED).contains("server"))
        assertTrue(playerErrorMessage(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT).contains("server"))
        assertTrue(playerErrorMessage(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS).contains("server"))
        assertTrue(playerErrorMessage(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND).contains("missing"))
        assertEquals(GENERIC_PLAYBACK_ERROR, playerErrorMessage(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    @Test
    fun `the same error is not shown twice in a row`() {
        var now = 0L
        val gate = PlaybackErrorGate(windowMillis = 3_000) { now }
        assertTrue(gate.accept("A"))
        now = 1_000
        assertFalse(gate.accept("A"))
        assertTrue(gate.accept("B"))
        now = 10_000
        assertTrue(gate.accept("B"))
    }
}
