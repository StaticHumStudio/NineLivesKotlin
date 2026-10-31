package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AudioBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A book becomes the current book only once its load has succeeded (#42). */
class PlaybackLoadStagingTest {

    private val a = AudioBook(id = "a", title = "Alpha")
    private val b = AudioBook(id = "b", title = "Beta")

    @Test
    fun `a staged book shows as loading until its own load clears it`() {
        val s = StagedLoad()
        s.stage(1, b)
        assertEquals(b, s.book.value)
        assertTrue(s.clear(1))
        assertNull(s.book.value)
    }

    @Test
    fun `an older load cannot clear the newer load's staging`() {
        val s = StagedLoad()
        s.stage(1, a)
        s.stage(2, b)
        assertFalse(s.clear(1))
        assertEquals(b, s.book.value)
    }

    @Test
    fun `an older load cannot rewrite the newer load's book`() {
        val s = StagedLoad()
        s.stage(1, a)
        s.stage(2, b)
        assertFalse(s.update(1, a.copy(title = "x")))
        assertEquals(b, s.book.value)
        assertTrue(s.update(2, b.copy(title = "Beta full")))
        assertEquals("Beta full", s.book.value?.title)
    }

    @Test
    fun `a load that never staged cannot clear or rewrite anything`() {
        val s = StagedLoad()
        assertFalse(s.clear(1))
        assertFalse(s.update(1, a))
        assertNull(s.book.value)
    }

    @Test
    fun `a load superseded before or after its tracks never publishes`() {
        assertEquals(LoadedTracks.SUPERSEDED, loadedTracksOutcome(tracksLoaded = false, stillCurrent = true, mediaItemCount = 0))
        assertEquals(LoadedTracks.SUPERSEDED, loadedTracksOutcome(tracksLoaded = true, stillCurrent = false, mediaItemCount = 3))
    }

    @Test
    fun `a load that built no media items is a failed load, not a finished book`() {
        assertEquals(LoadedTracks.EMPTY, loadedTracksOutcome(tracksLoaded = true, stillCurrent = true, mediaItemCount = 0))
    }

    @Test
    fun `a load with media items is ready`() {
        assertEquals(LoadedTracks.READY, loadedTracksOutcome(tracksLoaded = true, stillCurrent = true, mediaItemCount = 2))
    }

    @Test
    fun `the player's book is the loading one while a load runs`() {
        assertEquals("b", playerBookId(b, a))
        assertEquals("a", playerBookId(null, a))
        assertNull(playerBookId(null, null))
    }

    @Test
    fun `the publish writes the book while it still shows as loading, then clears the staging`() {
        val s = StagedLoad()
        s.stage(7, b)
        var current: AudioBook? = a
        val seenDuringPublish = mutableListOf<AudioBook?>()
        val cleared = publishStagedLoad(s, 7) {
            seenDuringPublish += s.book.value
            current = b
        }
        // Mid publish the mini player still has the staged book, so it never
        // flashes back to the previous one between the two writes.
        assertEquals(listOf<AudioBook?>(b), seenDuringPublish)
        assertEquals(b, current)
        assertTrue(cleared)
        assertNull(s.book.value)
    }

    @Test
    fun `a stale publish leaves a newer staging alone`() {
        val s = StagedLoad()
        s.stage(1, a)
        s.stage(2, b)
        assertFalse(publishStagedLoad(s, 1) {})
        assertEquals(b, s.book.value)
    }

    @Test
    fun `a delete or sign out sees the book that is loading as held`() {
        assertTrue(playerHoldsBook(a, b, "b"))
        assertTrue(playerHoldsBook(a, b, "a"))
        assertFalse(playerHoldsBook(a, b, "c"))
        assertTrue(playerHoldsBook(null, b, "b"))
        assertFalse(playerHoldsBook(null, null, "a"))
    }

    @Test
    fun `a remote book that is loading counts as held for sign out`() {
        assertTrue(playerHoldsRemoteBook(a.copy(isLocal = true), b))
        assertTrue(playerHoldsRemoteBook(a, null))
        assertFalse(playerHoldsRemoteBook(a.copy(isLocal = true), b.copy(isLocal = true)))
        assertFalse(playerHoldsRemoteBook(null, null))
    }

    @Test
    fun `cancelling a slow load clears its staging at once so play reaches the previous book`() = kotlinx.coroutines.runBlocking {
        val owner = PlaybackLoadOwner()
        val s = StagedLoad()
        val request = owner.newRequest()
        assertTrue(owner.claim(request))
        s.stage(request, b) // the load now waits on a slow server
        cancelPlaybackLoad(owner, s) // a mode switch
        assertFalse(owner.isCurrent(request))
        assertNull(s.book.value)
        val action = playAction(
            bookLoading = s.book.value != null,
            hasRetainedBook = true,
            mediaItemCount = 0,
            playerState = androidx.media3.common.Player.STATE_IDLE,
        )
        assertEquals(PlayAction.RELOAD_RETAINED, action)
        // The cancelled load's own finally later finds nothing of its own to clear.
        assertFalse(s.clear(request))
    }

    @Test
    fun `a cancelled load finishing late leaves a newer load's staging alone`() = kotlinx.coroutines.runBlocking {
        val owner = PlaybackLoadOwner()
        val s = StagedLoad()
        val old = owner.newRequest()
        assertTrue(owner.claim(old))
        s.stage(old, a)
        cancelPlaybackLoad(owner, s)
        val newer = owner.newRequest()
        assertTrue(owner.claim(newer))
        s.stage(newer, b)
        // The old load returns from the server and runs its finally.
        assertFalse(s.clear(old))
        assertEquals(b, s.book.value)
        assertTrue(owner.isCurrent(newer))
        // A second cancel before the newer load stages anything clears only its own.
        cancelPlaybackLoad(owner, s)
        assertNull(s.book.value)
    }

    @Test
    fun `a cancel with no load running leaves a staging it does not own alone`() {
        val owner = PlaybackLoadOwner()
        val s = StagedLoad()
        s.stage(7, a) // staged by a request this owner never claimed
        cancelPlaybackLoad(owner, s)
        assertEquals(a, s.book.value)
    }
}
