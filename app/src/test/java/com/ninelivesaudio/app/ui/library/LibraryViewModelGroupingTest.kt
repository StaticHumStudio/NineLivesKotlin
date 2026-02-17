package com.ninelivesaudio.app.ui.library

import com.ninelivesaudio.app.domain.model.AudioBook
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import org.junit.Test

class LibraryViewModelGroupingTest {

    @Test
    fun `series grouping uses fallback bucket and expected counts`() {
        val books = listOf(
            sampleBook(id = "1", title = "A", seriesName = "Dungeon Crawler Carl"),
            sampleBook(id = "2", title = "B", seriesName = "Dungeon Crawler Carl"),
            sampleBook(id = "3", title = "C", seriesName = null),
        )

        val sections = buildGroupedSections(books, ViewMode.SERIES, SortMode.TITLE_AZ)

        assertEquals(2, sections.size)
        assertEquals("Dungeon Crawler Carl", sections[0].title)
        assertEquals(2, sections[0].books.size)
        assertEquals("Standalone/Unknown Series", sections[1].title)
        assertEquals(1, sections[1].books.size)
    }

    @Test
    fun `genre grouping uses multi placement and fallback`() {
        val books = listOf(
            sampleBook(id = "1", title = "Dual", genres = listOf("LitRPG", "Sci-Fi")),
            sampleBook(id = "2", title = "Solo", genres = emptyList()),
        )

        val sections = buildGroupedSections(books, ViewMode.GENRE, SortMode.TITLE_AZ)

        val counts = sections.associate { it.title to it.books.size }
        assertEquals(1, counts["LitRPG"])
        assertEquals(1, counts["Sci-Fi"])
        assertEquals(1, counts["Uncategorized Genre"])
    }

    @Test
    fun `flattened items include only books for expanded groups`() {
        val sections = listOf(
            GroupedSection(
                key = "Author A",
                title = "Author A",
                books = listOf(sampleBook(id = "1", title = "One", author = "Author A")),
            ),
            GroupedSection(
                key = "Author B",
                title = "Author B",
                books = listOf(sampleBook(id = "2", title = "Two", author = "Author B")),
            ),
        )

        val flattened = flattenGroupedItems(sections, expandedGroups = setOf("Author A"))

        assertEquals(3, flattened.size)
        assertTrue(flattened[0] is LibraryListItem.GroupHeader)
        assertTrue(flattened[1] is LibraryListItem.BookRow)
        assertTrue(flattened[2] is LibraryListItem.GroupHeader)
    }

    private fun sampleBook(
        id: String,
        title: String,
        author: String = "Author",
        seriesName: String? = null,
        genres: List<String> = emptyList(),
    ): AudioBook = AudioBook(
        id = id,
        title = title,
        author = author,
        seriesName = seriesName,
        genres = genres,
        duration = 1.hours,
    )
}
