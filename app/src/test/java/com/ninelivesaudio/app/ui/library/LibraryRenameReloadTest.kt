package com.ninelivesaudio.app.ui.library

import com.ninelivesaudio.app.domain.model.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A library renamed on the server keeps its id, so the id comparison saw an
 * unchanged list and the selector kept showing the old name until the list
 * itself changed. A name that differs for the same id now counts as a change.
 */
class LibraryRenameReloadTest {

    private val books = Library(id = "books", name = "Books")
    private val podcasts = Library(id = "podcasts", name = "Podcasts")

    @Test
    fun `a renamed library is noticed`() {
        assertTrue(librariesRenamed(shown = listOf(books, podcasts), cached = listOf(books, podcasts.copy(name = "Shows"))))
    }

    @Test
    fun `an unchanged list is not a rename, whatever its order`() {
        assertFalse(librariesRenamed(shown = listOf(books, podcasts), cached = listOf(books, podcasts)))
        assertFalse(librariesRenamed(shown = listOf(books, podcasts), cached = listOf(podcasts, books)))
    }

    @Test
    fun `added and removed libraries are not renames`() {
        // Membership changes are the id comparison's job.
        assertFalse(librariesRenamed(shown = listOf(books), cached = listOf(books, podcasts)))
        assertFalse(librariesRenamed(shown = listOf(books, podcasts), cached = listOf(podcasts)))
    }

    @Test
    fun `a rename reloads the list even though the ids match`() {
        val renamed = librariesRenamed(shown = listOf(books, podcasts), cached = listOf(books.copy(name = "Audiobooks"), podcasts))
        assertTrue(
            shouldReloadLibrariesAfterSync(
                selectedLibrary = books,
                shownLibraryIds = listOf("books", "podcasts"),
                cachedLibraryIds = listOf("books", "podcasts"),
                librariesRenamed = renamed,
            ),
        )
        assertEquals(
            AfterProgressPull.RELOAD_LIBRARIES,
            afterProgressPull(
                selectedLibrary = books,
                shownLibraryIds = listOf("books", "podcasts"),
                cachedLibraryIds = listOf("books", "podcasts"),
                librariesRenamed = renamed,
            ),
        )
    }

    @Test
    fun `an unchanged list still only refilters`() {
        val renamed = librariesRenamed(shown = listOf(books, podcasts), cached = listOf(podcasts, books))
        assertFalse(
            shouldReloadLibrariesAfterSync(
                selectedLibrary = books,
                shownLibraryIds = listOf("books", "podcasts"),
                cachedLibraryIds = listOf("podcasts", "books"),
                librariesRenamed = renamed,
            ),
        )
        assertEquals(
            AfterProgressPull.REFILTER,
            afterProgressPull(
                selectedLibrary = books,
                shownLibraryIds = listOf("books", "podcasts"),
                cachedLibraryIds = listOf("podcasts", "books"),
                librariesRenamed = renamed,
            ),
        )
    }
}
