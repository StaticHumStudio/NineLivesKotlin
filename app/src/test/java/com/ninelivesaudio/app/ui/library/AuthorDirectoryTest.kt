package com.ninelivesaudio.app.ui.library

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.LibraryAuthor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Author view groups a server book by its Audiobookshelf author (issue #87).
 * The library list only sends the authors joined into one line, so the line
 * is matched against the library's author names, longest name first. A piece
 * the list does not know falls back to the old comma split, so an empty or
 * stale list never drops a book from the view.
 */
class AuthorDirectoryTest {

    private val smithJohn = LibraryAuthor("au_1", "Smith, John")
    private val gaiman = LibraryAuthor("au_np", "Neil Gaiman")
    private val pratchett = LibraryAuthor("au_tp", "Terry Pratchett")

    @Test
    fun `a Last comma First author from the server is one group`() {
        val sections = group(listOf(book("b", "Smith, John")), smithJohn)

        assertEquals(listOf("author-id:au_1"), sections.map { it.key })
        assertEquals(listOf("Smith, John"), sections.map { it.title })
    }

    @Test
    fun `a co-authored book is under each author's entry`() {
        val sections = group(listOf(book("omens", "Terry Pratchett, Neil Gaiman")), gaiman, pratchett)

        assertEquals(setOf("author-id:au_np", "author-id:au_tp"), sections.map { it.key }.toSet())
        assertEquals(listOf("Neil Gaiman", "Terry Pratchett"), sections.map { it.title })
        sections.forEach { assertEquals(listOf("omens"), it.books.map { b -> b.id }) }
    }

    @Test
    fun `every book by the same author lands in one group`() {
        val books = listOf(book("one", "Smith, John"), book("two", "Smith, John"), book("three", "Smith, John, Neil Gaiman"))

        val sections = group(books, smithJohn, gaiman)

        assertEquals(listOf("one", "three", "two"), sections.single { it.key == "author-id:au_1" }.books.map { it.id })
        assertEquals(listOf("three"), sections.single { it.key == "author-id:au_np" }.books.map { it.id })
        assertEquals(2, sections.size)
    }

    @Test
    fun `pieces the author list does not know fall back to the old split`() {
        val groups = AuthorDirectory(listOf(gaiman)).groupsFor("Neil Gaiman, Martin Luther King, Jr., Ann Lee")

        assertEquals(listOf("Neil Gaiman", "Martin Luther King, Jr.", "Ann Lee"), groups.map { it.title })
        assertEquals(listOf("author-id:au_np", "Martin Luther King, Jr.", "Ann Lee"), groups.map { it.key })
    }

    @Test
    fun `the longest known name wins when shorter names are also authors`() {
        val directory = AuthorDirectory(listOf(LibraryAuthor("au_s", "Smith"), LibraryAuthor("au_j", "John"), smithJohn))

        assertEquals(listOf(AuthorGroup("author-id:au_1", "Smith, John")), directory.groupsFor("Smith, John"))
    }

    @Test
    fun `extra spaces in the line still find the author`() {
        assertEquals(listOf(AuthorGroup("author-id:au_np", "Neil Gaiman")), AuthorDirectory(listOf(gaiman)).groupsFor("Neil  Gaiman"))
    }

    @Test
    fun `an author repeated in the line is one group`() {
        assertEquals(
            listOf(AuthorGroup("author-id:au_np", "Neil Gaiman")),
            AuthorDirectory(listOf(gaiman)).groupsFor("Neil Gaiman, Neil Gaiman"),
        )
    }

    @Test
    fun `with no author list grouping is exactly what it was`() {
        for (line in listOf("Terry Pratchett, Neil Gaiman", "Martin Luther King, Jr., Coretta Scott King", "Jane Doe, PhD, Ann Lee, M.D.", "Ann, , Bob, Ann", "Solo")) {
            val groups = AuthorDirectory.EMPTY.groupsFor(line)
            assertEquals(line, splitServerAuthorNames(line), groups.map { it.title })
            assertEquals(line, groups.map { it.title }, groups.map { it.key })
        }
    }

    @Test
    fun `a local book never uses the server author list`() {
        val local = book("hobbit", "Tolkien, J.R.R.", isLocal = true)

        assertEquals(listOf("Tolkien, J.R.R."), authorGroups(local, AuthorDirectory(listOf(LibraryAuthor("x", "Tolkien")))).map { it.title })
        assertEquals(listOf("Tolkien, J.R.R."), group(listOf(local), LibraryAuthor("x", "Tolkien")).map { it.key })
    }

    @Test
    fun `a book with no author is under Unknown Author with a list too`() {
        val sections = group(listOf(book("x", "  ")), smithJohn)

        assertEquals(listOf("Unknown Author"), sections.map { it.key })
        assertEquals(listOf("Unknown Author"), sections.map { it.title })
    }

    @Test
    fun `other views ignore the author list`() {
        val books = listOf(book("b", "Smith, John").copy(seriesName = "Saga", genres = listOf("Horror")))

        assertEquals(listOf("Saga"), buildGroupedSections(books, ViewMode.SERIES, SortMode.TITLE_AZ, AuthorDirectory(listOf(smithJohn))).map { it.key })
        assertEquals(listOf("Horror"), buildGroupedSections(books, ViewMode.GENRE, SortMode.TITLE_AZ, AuthorDirectory(listOf(smithJohn))).map { it.key })
    }

    private fun group(books: List<AudioBook>, vararg authors: LibraryAuthor) =
        buildGroupedSections(sortBooks(books, SortMode.TITLE_AZ), ViewMode.AUTHOR, SortMode.TITLE_AZ, AuthorDirectory(authors.toList()))

    private fun book(id: String, author: String, isLocal: Boolean = false) =
        AudioBook(id = id, title = id, author = author, isLocal = isLocal)
}
