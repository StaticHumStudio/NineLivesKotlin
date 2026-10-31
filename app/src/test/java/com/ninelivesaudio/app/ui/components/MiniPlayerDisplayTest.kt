package com.ninelivesaudio.app.ui.components

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Chapter
import com.ninelivesaudio.app.service.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

/** What the mini player shows while books switch (#42, decision J1). */
class MiniPlayerDisplayTest {

    private val a = AudioBook(id = "a", title = "Alpha")
    private val b = AudioBook(id = "b", title = "Beta")

    @Test
    fun `a book that is loading shows its own title with an empty bar and no chapter`() {
        val d = miniPlayerDisplay(a, b, PlaybackState.LOADING, 40.seconds, 100.seconds, Chapter(title = "Alpha 2"))!!
        assertEquals(b, d.book)
        assertEquals(0f, d.progress)
        assertNull(d.chapterTitle)
        assertTrue(d.loading)
    }

    @Test
    fun `the loaded book shows its progress and chapter`() {
        val d = miniPlayerDisplay(a, null, PlaybackState.PAUSED, 25.seconds, 100.seconds, Chapter(title = "One"))!!
        assertEquals(a, d.book)
        assertEquals(0.25f, d.progress)
        assertEquals("One", d.chapterTitle)
        assertFalse(d.loading)
    }

    @Test
    fun `a failed switch shows the previous book again`() {
        assertEquals(a, miniPlayerDisplay(a, null, PlaybackState.STOPPED, 40.seconds, 100.seconds, null)!!.book)
    }

    @Test
    fun `a first load shows the loading book with nothing current`() {
        assertEquals(b, miniPlayerDisplay(null, b, PlaybackState.LOADING, ZERO, ZERO, null)!!.book)
    }

    @Test
    fun `a stopped book with no title shows nothing`() {
        assertNull(miniPlayerDisplay(AudioBook(id = "x"), null, PlaybackState.STOPPED, ZERO, ZERO, null))
    }

    @Test
    fun `nothing current and nothing loading shows nothing`() {
        assertNull(miniPlayerDisplay(null, null, PlaybackState.STOPPED, ZERO, ZERO, null))
    }

    @Test
    fun `progress never leaves the bar`() {
        assertEquals(1f, miniPlayerDisplay(a, null, PlaybackState.PAUSED, 200.seconds, 100.seconds, null)!!.progress)
        assertEquals(0f, miniPlayerDisplay(a, null, PlaybackState.PAUSED, 20.seconds, ZERO, null)!!.progress)
    }
}
