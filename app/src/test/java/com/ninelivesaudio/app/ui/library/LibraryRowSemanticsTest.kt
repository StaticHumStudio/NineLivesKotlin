package com.ninelivesaudio.app.ui.library

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** What TalkBack reads for a Library row, and for the search box above it. */
class LibraryRowSemanticsTest {

    private fun chapters(count: Int) =
        (0 until count).map { Chapter(id = it, start = it * 100.0, end = (it + 1) * 100.0, title = "Ch $it") }

    @Test
    fun `a new book reads its title and author once`() {
        val book = AudioBook(id = "1", title = "Dune", author = "Frank Herbert")

        assertEquals("Dune, by Frank Herbert", bookRowDescription(book))
    }

    @Test
    fun `the title is said once even when it is long`() {
        val book = AudioBook(id = "1", title = "The Long Way", author = "Becky Chambers")

        val spoken = bookRowDescription(book)

        assertEquals(1, Regex("The Long Way").findAll(spoken).count())
    }

    @Test
    fun `a book with no author reads just the title`() {
        assertEquals("Dune", bookRowDescription(AudioBook(id = "1", title = "Dune", author = "  ")))
    }

    @Test
    fun `progress is said in words`() {
        val book = AudioBook(id = "1", title = "Dune", author = "Frank Herbert", progress = 0.42)

        assertEquals("Dune, by Frank Herbert, 42 percent listened", bookRowDescription(book))
    }

    @Test
    fun `progress that came from the server as a whole percent reads the same`() {
        val book = AudioBook(id = "1", title = "Dune", author = "Frank Herbert", progress = 42.0)

        assertEquals("Dune, by Frank Herbert, 42 percent listened", bookRowDescription(book))
    }

    @Test
    fun `the chapter is said in words after the progress`() {
        val book = AudioBook(
            id = "1", title = "Dune", author = "Frank Herbert", progress = 0.42,
            chapters = chapters(10), currentTime = 250.seconds,
        )

        assertEquals(
            "Dune, by Frank Herbert, 42 percent listened, chapter 3 of 10",
            bookRowDescription(book),
        )
    }

    @Test
    fun `a downloaded book says so`() {
        val book = AudioBook(id = "1", title = "Dune", author = "Frank Herbert", isDownloaded = true)

        assertEquals("Dune, by Frank Herbert, downloaded", bookRowDescription(book))
    }

    @Test
    fun `a finished book says finished instead of a percent`() {
        val book = AudioBook(
            id = "1", title = "Dune", author = "Frank Herbert", progress = 1.0, isFinished = true,
        )

        assertEquals("Dune, by Frank Herbert, finished", bookRowDescription(book))
    }

    @Test
    fun `an archived book says so`() {
        val book = AudioBook(id = "1", title = "Dune", author = "Frank Herbert", archivedAt = 5L)

        assertEquals("Dune, by Frank Herbert, archived", bookRowDescription(book))
    }

    @Test
    fun `everything together reads in a steady order`() {
        val book = AudioBook(
            id = "1", title = "Dune", author = "Frank Herbert", progress = 0.5,
            chapters = chapters(4), currentTime = 150.seconds,
            isDownloaded = true, archivedAt = 5L,
        )

        assertEquals(
            "Dune, by Frank Herbert, archived, 50 percent listened, chapter 2 of 4, downloaded",
            bookRowDescription(book),
        )
    }

    @Test
    fun `no row description uses a percent sign or a bullet`() {
        val book = AudioBook(
            id = "1", title = "Dune", author = "Frank Herbert", progress = 0.5,
            chapters = chapters(4), currentTime = 150.seconds, isDownloaded = true,
        )

        val spoken = bookRowDescription(book)

        assertFalse(spoken.contains('%'))
        assertFalse(spoken.contains('•'))
    }

    @Test
    fun `the search box has a label that does not depend on the flavor text`() {
        assertEquals("Search the library", SEARCH_FIELD_LABEL)
    }
}
