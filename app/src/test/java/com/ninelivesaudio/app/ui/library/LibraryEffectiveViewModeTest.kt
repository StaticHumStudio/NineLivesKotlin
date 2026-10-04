package com.ninelivesaudio.app.ui.library

import com.ninelivesaudio.app.domain.model.AudioBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A grouped view whose unlock was lost (a refund, a Play account pin) used to
 * show a blank shelf: the books were clamped to ALL but the screen still laid
 * out the stored SERIES view, which had no groups. The shelf now carries the
 * mode in effect.
 */
class LibraryEffectiveViewModeTest {

    private val books = listOf(
        AudioBook(id = "1", title = "One", seriesName = "Saga"),
        AudioBook(id = "2", title = "Two", seriesName = "Saga"),
        AudioBook(id = "3", title = "Three"),
    )

    @Test
    fun `stored series without the unlock lays out the flat shelf with every book`() = runBlocking {
        val shelf = arrangeShelf(books, SortMode.TITLE_AZ, storedViewMode = ViewMode.SERIES, isUnlocked = false)

        assertEquals(ViewMode.ALL, shelf.viewMode)
        assertTrue(shelf.sections.isEmpty())
        assertEquals(setOf("1", "2", "3"), shelf.books.map { it.id }.toSet())
    }

    @Test
    fun `stored series with the unlock groups the shelf`() = runBlocking {
        val shelf = arrangeShelf(books, SortMode.TITLE_AZ, storedViewMode = ViewMode.SERIES, isUnlocked = true)

        assertEquals(ViewMode.SERIES, shelf.viewMode)
        assertEquals(listOf("Saga", "Standalone/Unknown Series"), shelf.sections.map { it.key })
    }

    @Test
    fun `every locked grouped view falls back to all`() = runBlocking {
        for (mode in listOf(ViewMode.SERIES, ViewMode.AUTHOR, ViewMode.GENRE)) {
            val shelf = arrangeShelf(books, SortMode.RECENTLY_PLAYED, storedViewMode = mode, isUnlocked = false)
            assertEquals("$mode", ViewMode.ALL, shelf.viewMode)
            assertEquals("$mode", 3, shelf.books.size)
        }
    }
}
