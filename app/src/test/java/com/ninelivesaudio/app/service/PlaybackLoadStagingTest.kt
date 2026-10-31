package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Chapter
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

    @Test
    fun `removing the download of the loading book clears its staging at once`() = kotlinx.coroutines.runBlocking {
        val owner = PlaybackLoadOwner()
        val s = StagedLoad()
        val request = owner.newRequest()
        owner.trackLoad(request, "b")
        assertTrue(owner.claim(request))
        s.stage(request, b) // the load now waits on a session open or a backfill
        assertTrue(cancelDiskLoadsOf(owner, s, "b"))
        assertFalse(owner.isCurrent(request))
        assertNull(s.book.value)
        assertFalse(s.clear(request)) // its own finally finds nothing left
    }

    @Test
    fun `removing a download leaves a newer load's staging alone`() = kotlinx.coroutines.runBlocking {
        val owner = PlaybackLoadOwner()
        val s = StagedLoad()
        val old = owner.newRequest()
        owner.trackLoad(old, "a")
        assertTrue(owner.claim(old))
        s.stage(old, a)
        val newer = owner.newRequest()
        owner.trackLoad(newer, "b")
        assertTrue(owner.claim(newer))
        s.stage(newer, b)
        // The old load of "a" was superseded but is still tracked as a disk load.
        assertTrue(cancelDiskLoadsOf(owner, s, "a"))
        assertEquals(b, s.book.value)
        assertTrue(owner.isCurrent(newer))
        assertFalse(cancelDiskLoadsOf(owner, s, "c"))
        assertEquals(b, s.book.value)
    }

    private val aChapters = listOf(Chapter(id = 0, start = 0.0, end = 60.0, title = "A one"), Chapter(id = 1, start = 60.0, end = 120.0, title = "A two"))
    private val bChapters = listOf(Chapter(id = 1, start = 300.0, end = 600.0, title = "B two"), Chapter(id = 0, start = 0.0, end = 300.0, title = "B one"))

    @Test
    fun `a failed load of B leaves A's chapter cache alone`() {
        val s = StagedLoad()
        var cache = aChapters // what seekToChapter indexes while A is current
        s.stage(1, b.copy(chapters = bChapters))
        s.fillChaptersIfEmpty(1, listOf(Chapter(title = "ignored, B has chapters")))
        // B fails: its staging clears and nothing published.
        assertTrue(s.clear(1))
        assertEquals(aChapters, cache)
        assertNull(s.chapters(1))
        assertEquals(60.0, cache[1].start, 0.0)
        assertEquals("A two", cache[1].title)
    }

    @Test
    fun `B's chapters switch in the same step that makes B current`() {
        val s = StagedLoad()
        var cache = aChapters
        var current: AudioBook? = a
        s.stage(2, b) // no chapters on the list row
        s.fillChaptersIfEmpty(2, bChapters) // from the backfill or the session
        s.fillChaptersIfEmpty(1, aChapters) // a stale load cannot fill B's
        assertEquals(aChapters, cache) // still A until publish
        publishStagedLoad(s, 2) { chapters ->
            current = b
            cache = chapters
        }
        assertEquals(b, current)
        assertEquals(listOf("B one", "B two"), cache.map { it.title })
    }

    @Test
    fun `a stale load cannot read the newer load's chapters`() {
        val s = StagedLoad()
        s.stage(1, a.copy(chapters = aChapters))
        s.stage(2, b.copy(chapters = bChapters))
        assertNull(s.chapters(1))
        assertEquals(listOf("B one", "B two"), s.chapters(2)?.map { it.title })
    }
}
