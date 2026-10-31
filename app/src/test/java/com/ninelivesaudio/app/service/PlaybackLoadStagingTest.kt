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
}
