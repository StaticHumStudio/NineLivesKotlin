package com.ninelivesaudio.app.widget.home

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Chapter
import com.ninelivesaudio.app.service.PlaybackState
import com.ninelivesaudio.app.ui.home.AltarCopy
import com.ninelivesaudio.app.ui.home.formatListeningTime
import com.ninelivesaudio.app.widget.FakeSource
import com.ninelivesaudio.app.widget.WidgetAccess
import com.ninelivesaudio.app.widget.WidgetContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class HomeWidgetModelTest {

    private val books = (1..5).map { i -> AudioBook(id = "b$i", title = "Book $i", currentTime = (i * 600).seconds) }
    private val ch = listOf(Chapter(0, 0.0, 300.0, "One"), Chapter(1, 300.0, 900.0, "Two"))
    private val lib = WidgetAccess.Library("lib_s", false)

    private fun ready(
        current: AudioBook?,
        liveChapter: String? = null,
        state: PlaybackState = PlaybackState.STOPPED,
        loading: Boolean = false,
        playable: Boolean = true,
    ) = buildHomeWidgetModel(lib, true, 5, books, current, liveChapter, state, loading, playable)

    @Test fun `a locked large widget reads nothing and shows nothing personal`() = runBlocking {
        val src = FakeSource(books = books)
        assertEquals(HomeWidgetInputs(false, 0, emptyList()), loadHomeWidgetInputs(WidgetAccess.Locked, src))
        assertEquals(emptyList<String>(), src.calls)
        val m = buildHomeWidgetModel(WidgetAccess.Locked, false, 0, emptyList(), null, null, PlaybackState.STOPPED, false, false)
        assertEquals(WidgetContent.LOCKED, m.content)
        assertNull(m.rank)
        assertNull(m.current)
        assertTrue(m.lives.isEmpty())
    }

    @Test fun `a locked widget shows nothing even when handed books`() {
        val m = buildHomeWidgetModel(WidgetAccess.Locked, true, 5, books, books[0], "Live", PlaybackState.PLAYING, false, true)
        assertEquals(WidgetContent.LOCKED, m.content)
        assertNull(m.rank)
        assertNull(m.current)
        assertTrue(m.lives.isEmpty())
    }

    @Test fun `signed out and no library read nothing`() = runBlocking {
        val src = FakeSource(books = books)
        loadHomeWidgetInputs(WidgetAccess.SignedOut, src)
        loadHomeWidgetInputs(WidgetAccess.NoLibrary(isLocalMode = true), src)
        assertEquals(emptyList<String>(), src.calls)
    }

    @Test fun `a local library with its folder gone stops before reading books`() = runBlocking {
        val src = FakeSource(folderOk = false, books = books)
        val inputs = loadHomeWidgetInputs(WidgetAccess.Library("lib_l", true), src)
        assertEquals(listOf("folderAccessible"), src.calls)
        val m = buildHomeWidgetModel(WidgetAccess.Library("lib_l", true), inputs.folderAccessible, inputs.bookCount, inputs.recent, null, null, PlaybackState.STOPPED, false, true)
        assertEquals(WidgetContent.FOLDER_LOST, m.content)
        assertNull(m.current)
    }

    @Test fun `a server library reads its count and the same nine recent books as Home`() = runBlocking {
        val many = (1..12).map { AudioBook(id = "m$it") }
        val src = FakeSource(books = many)
        val inputs = loadHomeWidgetInputs(lib, src)
        assertEquals(listOf("countBooks", "recent"), src.calls)
        assertEquals("lib_s", src.lastLibraryId)
        assertEquals(9, inputs.recent.size)
        assertEquals(12, inputs.bookCount)
        assertTrue(inputs.folderAccessible)
    }

    @Test fun `rank card matches Home's altar for the same books`() {
        val m = ready(current = null)
        val total = books.sumOf { it.currentTime.inWholeSeconds.toDouble() }
        assertEquals(AltarCopy.epithet(total), m.rank!!.epithet)
        assertEquals("Total time given: " + formatListeningTime(total), m.rank!!.totalTimeText)
    }

    @Test fun `the current book is the one in the player, else the most recent`() {
        assertEquals("b3", ready(current = books[2]).current!!.bookId)
        assertEquals("b1", ready(current = null).current!!.bookId)
    }

    @Test fun `the next lives skip the current book and stop at four`() {
        assertEquals(listOf("b1", "b2", "b4", "b5"), ready(current = books[2]).lives.map { it.bookId })
        assertEquals(listOf("b2", "b3", "b4", "b5"), ready(current = null).lives.map { it.bookId })
    }

    @Test fun `chapter comes from the player when live, else from the saved position`() {
        assertEquals("Live", ready(current = books[0], liveChapter = "Live").current!!.chapterTitle)
        val cold = buildHomeWidgetModel(lib, true, 5, listOf(books[0].copy(chapters = ch, currentTime = 400.seconds)), null, null, PlaybackState.STOPPED, false, true)
        assertEquals("Two", cold.current!!.chapterTitle)
        assertNull(ready(current = books[0], liveChapter = "  ").current!!.chapterTitle)
    }

    @Test fun `chapter lookup handles edges`() {
        assertNull(chapterTitleAt(emptyList(), 10.0))
        assertEquals("One", chapterTitleAt(ch, 0.0))
        assertEquals("Two", chapterTitleAt(ch, 300.0))
        assertEquals("Two", chapterTitleAt(ch, 5_000.0))
        assertEquals("One", chapterTitleAt(ch, -5.0))
    }

    @Test fun `the button follows the player and never offers play during a load`() {
        assertEquals(CurrentButton.WAIT, ready(current = books[0], state = PlaybackState.PAUSED, loading = true).current!!.button)
        assertEquals(CurrentButton.WAIT, ready(current = books[0], state = PlaybackState.LOADING).current!!.button)
        assertEquals(CurrentButton.PAUSE, ready(current = books[0], state = PlaybackState.PLAYING).current!!.button)
        assertEquals(CurrentButton.PAUSE, ready(current = books[0], state = PlaybackState.BUFFERING).current!!.button)
        assertEquals(CurrentButton.PLAY, ready(current = books[0], state = PlaybackState.STOPPED).current!!.button)
        assertEquals(CurrentButton.PLAY, ready(current = books[0], state = PlaybackState.PAUSED, playable = false).current!!.button)
        assertEquals(CurrentButton.OPEN, ready(current = null, playable = false).current!!.button)
        assertEquals(CurrentButton.PLAY, ready(current = null, playable = true).current!!.button)
    }

    @Test fun `a book in the player with no saved progress yet still shows`() {
        val m = buildHomeWidgetModel(lib, true, 5, emptyList(), books[0], null, PlaybackState.PAUSED, false, true)
        assertEquals(WidgetContent.READY, m.content)
        assertEquals("b1", m.current!!.bookId)
        assertTrue(m.lives.isEmpty())
    }

    @Test fun `books but nothing played yet is its own calm state`() {
        val m = buildHomeWidgetModel(lib, true, 5, emptyList(), null, null, PlaybackState.STOPPED, false, true)
        assertEquals(WidgetContent.NOTHING_PLAYED, m.content)
        assertNull(m.current)
        assertNull(m.rank)
    }

    @Test fun `an empty library shows no books, never an old rank`() {
        val m = buildHomeWidgetModel(lib, true, 0, emptyList(), null, null, PlaybackState.STOPPED, false, true)
        assertEquals(WidgetContent.NO_BOOKS, m.content)
        assertNull(m.rank)
    }
}
