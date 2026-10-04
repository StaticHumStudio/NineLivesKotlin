package com.ninelivesaudio.app.ui.library

import com.ninelivesaudio.app.domain.model.AudioBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The grouped Library crashed with "Key book-Caleb Ibarra-li_mock_00105 was
 * already used" while a big library synced. The shelf read spans several
 * cursor windows, and a sync page saved between two of them moved re-saved
 * books to the end of the read, so the read returned them twice. One book
 * twice in a group is two list rows with one key, and the list throws.
 */
class LibraryDuplicateRowTest {

    @Test
    fun `a book the shelf read returned twice shows once`() = runBlocking {
        // What the read returns when a sync page lands between cursor
        // windows: the re-saved book again at the end, identical.
        val caleb = book("li_mock_00105", "Mockbook 0105 Ashen Tide Hollow", "Caleb Ibarra")
        val stored = listOf(
            caleb,
            book("li_mock_00106", "Mockbook 0106 Iron Saint", "Mara Vance"),
            book("li_mock_00412", "Mockbook 0412 Glass River", "Caleb Ibarra"),
            caleb,
        )

        val shelf = arrangeShelf(stored, SortMode.TITLE_AZ, ViewMode.AUTHOR, isUnlocked = true)
        val items = flattenGroupedItems(shelf.sections, mapOf("Caleb Ibarra" to true, "Mara Vance" to true))

        assertEquals(listOf("li_mock_00105", "li_mock_00106", "li_mock_00412"), shelf.books.map { it.id })
        assertNoRepeatedRows(items)
        val calebHeader = items.filterIsInstance<LibraryListItem.GroupHeader>().single { it.groupKey == "Caleb Ibarra" }
        assertEquals(2, calebHeader.count)
    }

    @Test
    fun `an author line that repeats a name lists the book once`() {
        val books = listOf(
            book("a", "A", "John Smith, John Smith"),
            book("b", "B", "John Smith, john  smith"),
            book("c", "C", " John Smith ,  JOHN SMITH"),
        )

        val sections = buildGroupedSections(books, ViewMode.AUTHOR, SortMode.TITLE_AZ)

        assertEquals(listOf("John Smith"), sections.map { it.key })
        assertEquals(listOf("a", "b", "c"), sections.single().books.map { it.id })
        assertNoRepeatedRows(flattenGroupedItems(sections, mapOf("John Smith" to true)))
    }

    @Test
    fun `names that differ only in capitals or spacing are one group`() {
        val books = listOf(
            book("a", "A", "Ursula K. Le Guin"),
            book("b", "B", "ursula k. le guin"),
            book("c", "C", "Ursula  K.  Le Guin, Neil Gaiman"),
            book("d", "D", "NEIL GAIMAN"),
        )

        val sections = buildGroupedSections(books, ViewMode.AUTHOR, SortMode.TITLE_AZ)

        // The first spelling seen names the group.
        assertEquals(listOf("Neil Gaiman", "Ursula K. Le Guin"), sections.map { it.key })
        assertEquals(listOf("c", "d"), sections[0].books.map { it.id })
        assertEquals(listOf("a", "b", "c"), sections[1].books.map { it.id })
    }

    @Test
    fun `genre and series variants never make two groups with one key`() {
        val books = listOf(
            book("a", "A", "X", genres = listOf("Fantasy", "fantasy ", "Sci Fi")),
            book("b", "B", "X", genres = listOf(" FANTASY"), series = "The Expanse"),
            book("c", "C", "X", series = "the  expanse"),
        )

        for (mode in listOf(ViewMode.GENRE, ViewMode.SERIES, ViewMode.AUTHOR)) {
            val sections = buildGroupedSections(books, mode, SortMode.TITLE_AZ)
            assertEquals("$mode keys", sections.map { it.key }.distinct(), sections.map { it.key })
            sections.forEach { section ->
                assertEquals("$mode ${section.key}", section.books.map { it.id }.distinct(), section.books.map { it.id })
            }
        }
        assertEquals(
            listOf("Fantasy", "Sci Fi", "Uncategorized Genre"),
            buildGroupedSections(books, ViewMode.GENRE, SortMode.TITLE_AZ).map { it.key },
        )
    }

    @Test
    fun `a stray repeat in the groups never repeats a list row`() {
        val one = AudioBook(id = "one", title = "One")
        val two = AudioBook(id = "two", title = "Two")
        val sections = listOf(
            GroupedSection(key = "Ann", title = "Ann", books = listOf(one, one, two)),
            GroupedSection(key = "Ann", title = "Ann", books = listOf(two)),
            GroupedSection(key = "Bob", title = "Bob", books = listOf(one)),
        )

        val items = flattenGroupedItems(sections, emptyMap())

        assertNoRepeatedRows(items)
        // The first of each is kept, in order.
        assertEquals(
            listOf("header-Ann", "Ann/one", "Ann/two", "header-Bob", "Bob/one"),
            items.map {
                when (it) {
                    is LibraryListItem.GroupHeader -> "header-${it.groupKey}"
                    is LibraryListItem.BookRow -> "${it.groupKey}/${it.book.id}"
                }
            },
        )
    }

    @Test
    fun `list keys cannot run together`() {
        val a = LibraryListItem.BookRow(groupKey = "Smith-Jones", book = AudioBook(id = "x"))
        val b = LibraryListItem.BookRow(groupKey = "Smith", book = AudioBook(id = "Jones-x"))
        val header = LibraryListItem.GroupHeader(groupKey = "Smith", title = "Smith", count = 1, isExpanded = true)

        assertEquals(3, setOf(a.listKey, b.listKey, header.listKey).size)
    }

    @Test
    fun `an eight thousand book library has one row per book per group`() = runBlocking {
        val firsts = listOf("Mara", "Jonas", "Ilse", "Theo", "Ruth", "Caleb", "Wren", "Otto", "Nadia", "Silas", "Petra", "Hugo")
        val lasts = listOf("Vance", "Okafor", "Lindqvist", "Marsh", "Delacroix", "Hale", "Ibarra", "Kowal", "Strand", "Achebe")
        val books = (1..8000).map { i ->
            val author = "${firsts[i % firsts.size]} ${lasts[(i / firsts.size) % lasts.size]}"
            book("li_mock_%05d".format(i), "Mockbook %04d".format(i), author)
        }
        // Two pages saved mid-read: their books came back again at the end.
        val stored = books + books.subList(99, 299)

        val shelf = arrangeShelf(stored, SortMode.TITLE_AZ, ViewMode.AUTHOR, isUnlocked = true)
        val everyGroupOpen = shelf.sections.associate { it.key to true }
        val items = flattenGroupedItems(shelf.sections, everyGroupOpen)

        assertEquals(8000, shelf.books.size)
        assertEquals(8000, items.count { it is LibraryListItem.BookRow })
        assertNoRepeatedRows(items)
    }

    private fun assertNoRepeatedRows(items: List<LibraryListItem>) {
        val keys = items.map { it.listKey }
        assertEquals("repeated list keys", emptyList<String>(), keys.groupBy { it }.filterValues { it.size > 1 }.keys.toList())
    }

    private fun book(
        id: String,
        title: String,
        author: String,
        genres: List<String> = emptyList(),
        series: String? = null,
    ) = AudioBook(id = id, title = title, author = author, genres = genres, seriesName = series)
}
