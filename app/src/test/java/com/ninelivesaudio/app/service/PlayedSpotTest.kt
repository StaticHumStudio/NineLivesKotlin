package com.ninelivesaudio.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #118: a stop or a playback error saves (and sends) the book's spot
 * only when it is new: the book played, or the user seeked. A load whose
 * stream never opened (a deleted book, a 5xx, a timeout), or one that opened
 * but never played, has nothing new to date as fresh over newer progress on
 * the server.
 */
class PlayedSpotTest {

    private fun writes(spot: PlayedSpot, alreadyFinalized: Boolean = false) =
        spot.takeForTerminalWrite(hasRetainedBook = true, alreadyFinalized = alreadyFinalized)

    @Test
    fun `a load whose stream fails before it plays saves nothing`() {
        val spot = PlayedSpot()
        spot.bookPlaced()
        assertFalse(writes(spot))
    }

    @Test
    fun `a book loaded and ready but never played saves nothing on stop`() {
        // The startup restore, or a book picked and left paused. Nothing was
        // listened, so nothing new exists to date as now.
        val spot = PlayedSpot()
        spot.bookPlaced()
        assertFalse(writes(spot))
    }

    @Test
    fun `a book that played is saved when it stops or fails`() {
        val spot = PlayedSpot()
        spot.bookPlaced()
        spot.playbackStarted()
        assertTrue(writes(spot))
    }

    @Test
    fun `a book deleted on the server, restored at startup, saves nothing when its files 404`() {
        // The evidence run: restored at 0:56, the progress import moved it to
        // 0:00, then every file answered 404. Nothing may be saved or queued.
        val spot = PlayedSpot()
        spot.bookPlaced()
        spot.bookPlaced()
        assertFalse(writes(spot))
    }

    @Test
    fun `an existing book whose stream hits a 503 or a timeout on load keeps the server's spot, then saves once it plays`() {
        val spot = PlayedSpot()
        spot.bookPlaced()
        assertFalse(writes(spot))
        // Play retries the same media and the server answers this time.
        spot.playbackStarted()
        assertTrue(writes(spot))
    }

    @Test
    fun `a server spot taken after playing is not sent back until the book plays again`() {
        val spot = PlayedSpot()
        spot.bookPlaced()
        spot.playbackStarted()
        // Paused, then the server's spot (or its reset to 0:00) moved the
        // player. The server already holds it.
        spot.bookPlaced()
        assertFalse(writes(spot))
        spot.playbackStarted()
        assertTrue(writes(spot))
    }

    @Test
    fun `a spot the user seeked to is theirs even if the stream then fails`() {
        val spot = PlayedSpot()
        spot.bookPlaced()
        spot.userSeeked()
        assertTrue(writes(spot))
    }

    @Test
    fun `a finalized book still gets no second write`() {
        val spot = PlayedSpot()
        spot.playbackStarted()
        assertFalse(writes(spot, alreadyFinalized = true))
        assertFalse(spot.takeForTerminalWrite(hasRetainedBook = false, alreadyFinalized = false))
    }

    @Test
    fun `a spot a stop or an error saved is not new any more`() {
        val spot = PlayedSpot()
        spot.playbackStarted()
        assertTrue(writes(spot))
        // The next stop or error, with no playback since, sends nothing.
        assertFalse(writes(spot))
    }

    @Test
    fun `a retry after a stream error that fails again sends nothing`() {
        // Played to 2:00:00, the stream failed, and the error saved 2:00:00.
        // Another device then moved the book to 2:30:00. Play retries, the
        // stream fails again before it plays: 2:00:00 must not go out again
        // dated now, over the other device's spot.
        val spot = PlayedSpot()
        spot.bookPlaced()
        spot.playbackStarted()
        assertTrue(writes(spot))
        // The retry starts: the spot is the one just saved.
        spot.bookPlaced()
        assertFalse(writes(spot))
        // A retry that does play is new listening again.
        spot.playbackStarted()
        assertTrue(writes(spot))
    }

    @Test
    fun `only a seek the user made counts, in any state but a load`() {
        for (state in listOf(PlaybackState.BUFFERING, PlaybackState.PLAYING, PlaybackState.PAUSED, PlaybackState.STOPPED)) {
            assertTrue(state.name, seekIsUsers(appPositioning = false, bookLoading = false, playbackState = state))
            // The app moving the player (a load, a retry, a resume, the
            // resume rewind, a chapter refresh) is not the user.
            assertFalse(state.name, seekIsUsers(appPositioning = true, bookLoading = false, playbackState = state))
            assertFalse(state.name, seekIsUsers(appPositioning = false, bookLoading = true, playbackState = state))
        }
        assertFalse(seekIsUsers(appPositioning = false, bookLoading = false, playbackState = PlaybackState.LOADING))
    }
}
