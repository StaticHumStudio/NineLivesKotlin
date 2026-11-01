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
        assertTrue(playerErrorMessage(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND).contains("missing"))
        assertEquals(GENERIC_PLAYBACK_ERROR, playerErrorMessage(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    @Test
    fun `an expired sign in is not called a connection problem`() {
        val bad = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertEquals(SIGNED_OUT_PLAYBACK_ERROR, playerErrorMessage(bad, httpStatus = 401))
        assertEquals(SIGNED_OUT_PLAYBACK_ERROR, playerErrorMessage(bad, httpStatus = 403))
    }

    @Test
    fun `a book gone from the server says so`() {
        assertEquals(
            BOOK_GONE_PLAYBACK_ERROR,
            playerErrorMessage(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, httpStatus = 404),
        )
    }

    @Test
    fun `other bad statuses are not called a connection problem`() {
        assertEquals(
            GENERIC_PLAYBACK_ERROR,
            playerErrorMessage(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, httpStatus = 500),
        )
        assertEquals(
            GENERIC_PLAYBACK_ERROR,
            playerErrorMessage(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
        )
    }

    @Test
    fun `a missing file in a folder book points at the folder not a download`() {
        val message = playerErrorMessage(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, isLocalBook = true)
        assertEquals(FOLDER_ACCESS_LOST_ERROR, message)
        assertFalse(message.contains("download", ignoreCase = true))
    }

    @Test
    fun `a restore nobody pressed play for reports no error`() {
        assertNull(loadErrorEvent("You are offline.", reportErrors = false))
        assertEquals(PlaybackEvent.Error("You are offline."), loadErrorEvent("You are offline.", reportErrors = true))
        assertNull(loadErrorEvent(null, reportErrors = true))
    }

    @Test
    fun `a book whose download is being removed shows no file error`() {
        var now = 0L
        val guard = RemovalSuppression(graceMillis = 5_000) { now }
        assertFalse(guard.suppresses("b"))
        guard.begin("b")
        assertTrue(guard.suppresses("b"))
        assertFalse(guard.suppresses("other"))
        guard.end("b")
        now = 4_000
        assertTrue(guard.suppresses("b"))
        now = 6_000
        assertFalse(guard.suppresses("b"))
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

    @Test
    fun `a restored session stays quiet until the user presses play`() {
        val quiet = RestoreQuiet()
        assertTrue(quiet.reports())
        quiet.enterLoad(reportErrors = false)
        assertFalse(quiet.reports())
        quiet.userPlay()
        assertTrue(quiet.reports())
    }

    @Test
    fun `a load the user started ends the quiet`() {
        val quiet = RestoreQuiet()
        quiet.enterLoad(reportErrors = false)
        quiet.enterLoad(reportErrors = true)
        assertTrue(quiet.reports())
    }

    @Test
    fun `removal only silences playback from the removed local files`() {
        assertTrue(removalSilencesError(guardSuppresses = true, playingLocalFiles = true))
        assertFalse(removalSilencesError(guardSuppresses = true, playingLocalFiles = false))
        assertFalse(removalSilencesError(guardSuppresses = false, playingLocalFiles = true))
    }

    @Test
    fun `play from a headset or notification after a restore ends the quiet`() {
        val quiet = RestoreQuiet()
        quiet.enterLoad(reportErrors = false)
        quiet.onPlayWhenReadyChanged(playWhenReady = false)
        assertFalse(quiet.reports())
        quiet.onPlayWhenReadyChanged(playWhenReady = true)
        assertTrue(quiet.reports())
    }
}
