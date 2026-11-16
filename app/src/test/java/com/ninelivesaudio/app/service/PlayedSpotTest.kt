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
        terminalWritesProgress(hasRetainedBook = true, alreadyFinalized = alreadyFinalized, spot = spot)

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
        assertFalse(terminalWritesProgress(hasRetainedBook = false, alreadyFinalized = false, spot = spot))
    }
}
