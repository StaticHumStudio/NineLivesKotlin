package com.ninelivesaudio.app.service

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #118: a stop or a playback error saves (and sends) the book's spot
 * only when the player actually got there. A load whose stream never opened
 * (a deleted book, a 5xx, a timeout) must not push the spot it was handed,
 * stamped as new, over newer progress on the server.
 */
class PlayerSpotReachTest {

    private fun writes(reach: PlayerSpotReach, alreadyFinalized: Boolean = false) =
        terminalWritesProgress(hasRetainedBook = true, alreadyFinalized = alreadyFinalized, reach = reach)

    @Test
    fun `a load whose stream fails before it plays saves nothing`() {
        val reach = PlayerSpotReach()
        reach.bookPlaced()
        assertFalse(writes(reach))
    }

    @Test
    fun `a book the player opened at its spot is saved when it stops or fails`() {
        val reach = PlayerSpotReach()
        reach.bookPlaced()
        reach.onPlayerState(state = Player.STATE_READY, hasMedia = true, bookLoading = false)
        assertTrue(writes(reach))
    }

    @Test
    fun `buffering or an error state is not reaching the spot`() {
        val reach = PlayerSpotReach()
        reach.bookPlaced()
        reach.onPlayerState(state = Player.STATE_BUFFERING, hasMedia = true, bookLoading = false)
        reach.onPlayerState(state = Player.STATE_IDLE, hasMedia = true, bookLoading = false)
        assertFalse(writes(reach))
    }

    @Test
    fun `the previous book turning ready while another loads does not count`() {
        val reach = PlayerSpotReach()
        reach.bookPlaced()
        reach.onPlayerState(state = Player.STATE_READY, hasMedia = true, bookLoading = true)
        reach.onPlayerState(state = Player.STATE_READY, hasMedia = false, bookLoading = false)
        assertFalse(writes(reach))
    }

    @Test
    fun `a server spot the player has not opened yet is not sent back`() {
        val reach = PlayerSpotReach()
        reach.bookPlaced()
        reach.onPlayerState(state = Player.STATE_READY, hasMedia = true, bookLoading = false)
        // The server's spot (or its reset to 0:00) moved the player. The
        // server already holds it, and the stream may fail there.
        reach.bookPlaced()
        assertFalse(writes(reach))
        reach.onPlayerState(state = Player.STATE_READY, hasMedia = true, bookLoading = false)
        assertTrue(writes(reach))
    }

    @Test
    fun `a book deleted on the server, restored at startup, saves nothing when its files 404`() {
        // The evidence run: restored at 0:56, the progress import moved it to
        // 0:00, then every file answered 404. Nothing may be saved or queued.
        val reach = PlayerSpotReach()
        reach.bookPlaced()
        reach.onPlayerState(state = Player.STATE_BUFFERING, hasMedia = true, bookLoading = false)
        reach.bookPlaced()
        reach.onPlayerState(state = Player.STATE_IDLE, hasMedia = true, bookLoading = false)
        assertFalse(writes(reach))
    }

    @Test
    fun `an existing book whose stream hits a 503 or a timeout on load keeps the server's spot, then saves once it plays`() {
        val reach = PlayerSpotReach()
        reach.bookPlaced()
        reach.onPlayerState(state = Player.STATE_BUFFERING, hasMedia = true, bookLoading = false)
        reach.onPlayerState(state = Player.STATE_IDLE, hasMedia = true, bookLoading = false)
        assertFalse(writes(reach))
        // Play retries the same media and the server answers this time.
        reach.onPlayerState(state = Player.STATE_BUFFERING, hasMedia = true, bookLoading = false)
        reach.onPlayerState(state = Player.STATE_READY, hasMedia = true, bookLoading = false)
        assertTrue(writes(reach))
    }

    @Test
    fun `a spot the user seeked to is theirs even if the stream then fails`() {
        val reach = PlayerSpotReach()
        reach.bookPlaced()
        reach.userSeeked()
        assertTrue(writes(reach))
    }

    @Test
    fun `a finalized book still gets no second write`() {
        val reach = PlayerSpotReach()
        reach.onPlayerState(state = Player.STATE_READY, hasMedia = true, bookLoading = false)
        assertFalse(writes(reach, alreadyFinalized = true))
        assertFalse(terminalWritesProgress(hasRetainedBook = false, alreadyFinalized = false, reach = reach))
    }
}
