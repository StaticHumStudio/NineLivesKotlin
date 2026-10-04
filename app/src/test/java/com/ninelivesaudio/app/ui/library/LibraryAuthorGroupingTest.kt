package com.ninelivesaudio.app.ui.library

import com.ninelivesaudio.app.domain.model.AudioBook
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Audiobookshelf's library list sends a co-authored book's authors as one
 * string, "Terry Pratchett, Neil Gaiman". Author view filed that under a
 * group of its own. It now lists the book under each author (issue #64).
 */
class LibraryAuthorGroupingTest {

    @Test
    fun `a co-authored server book is listed under each author`() {
        val books = listOf(
            book("good-omens", "Terry Pratchett, Neil Gaiman"),
            book("mort", "Terry Pratchett"),
            book("coraline", "Neil Gaiman"),
        )

        val sections = buildGroupedSections(sortBooks(books, SortMode.TITLE_AZ), ViewMode.AUTHOR, SortMode.TITLE_AZ)

        assertEquals(listOf("Neil Gaiman", "Terry Pratchett"), sections.map { it.key })
        assertEquals(listOf("coraline", "good-omens"), sections[0].books.map { it.id })
        assertEquals(listOf("good-omens", "mort"), sections[1].books.map { it.id })
    }

    @Test
    fun `the book keeps its full author line for display`() {
        val sections = buildGroupedSections(
            listOf(book("good-omens", "Terry Pratchett, Neil Gaiman")),
            ViewMode.AUTHOR,
            SortMode.TITLE_AZ,
        )

        sections.forEach { section ->
            assertEquals("Terry Pratchett, Neil Gaiman", section.books.single().author)
        }
    }

    @Test
    fun `a name suffix stays with the name before it`() {
        assertEquals(
            listOf("Martin Luther King, Jr.", "Coretta Scott King"),
            splitServerAuthorNames("Martin Luther King, Jr., Coretta Scott King"),
        )
        assertEquals(listOf("John Smith, III"), splitServerAuthorNames("John Smith, III"))
        assertEquals(listOf("Jane Doe, PhD", "Ann Lee, M.D."), splitServerAuthorNames("Jane Doe, PhD, Ann Lee, M.D."))
    }

    @Test
    fun `blank pieces and repeats are dropped`() {
        assertEquals(listOf("Ann", "Bob"), splitServerAuthorNames("Ann, , Bob, Ann"))
        assertEquals(listOf("Solo"), splitServerAuthorNames("Solo"))
    }

    @Test
    fun `a local book's author tag is one name, commas and all`() {
        // Tags often put the last name first.
        val local = book("hobbit", "Tolkien, J.R.R.", isLocal = true)

        assertEquals(listOf("Tolkien, J.R.R."), authorGroupNames(local))
    }

    @Test
    fun `a book with no author is under Unknown Author`() {
        val sections = buildGroupedSections(listOf(book("x", "  ")), ViewMode.AUTHOR, SortMode.TITLE_AZ)

        assertEquals(listOf("Unknown Author"), sections.map { it.key })
    }

    private fun book(id: String, author: String, isLocal: Boolean = false) =
        AudioBook(id = id, title = id, author = author, isLocal = isLocal)
}
