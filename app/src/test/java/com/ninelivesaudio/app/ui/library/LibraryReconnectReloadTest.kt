package com.ninelivesaudio.app.ui.library

import com.ninelivesaudio.app.domain.model.Library
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fresh offline start with no saved library list has nothing selected, and
 * re-filtering a shelf with no library does nothing. So when the server came
 * back, the background sync cleared the failure banner and the Library stayed
 * empty. A sync that brings libraries back while nothing is selected, or that
 * changes the library list, now reloads the libraries and the selection.
 */
class LibraryReconnectReloadTest {

    private val books = Library(id = "books")
    private val podcasts = Library(id = "podcasts")

    @Test
    fun `nothing selected and libraries came back reloads the list`() {
        assertTrue(shouldReloadLibrariesAfterSync(null, emptyList(), listOf("books")))
    }

    @Test
    fun `a changed library list reloads the list`() {
        assertTrue(shouldReloadLibrariesAfterSync(books, listOf("books"), listOf("books", "podcasts")))
        assertTrue(shouldReloadLibrariesAfterSync(books, listOf("books", "podcasts"), listOf("books")))
    }

    @Test
    fun `same libraries with one selected only refilters`() {
        assertFalse(shouldReloadLibrariesAfterSync(podcasts, listOf("books", "podcasts"), listOf("books", "podcasts")))
    }

    @Test
    fun `a server with no libraries does not reload forever`() {
        assertFalse(shouldReloadLibrariesAfterSync(null, emptyList(), emptyList()))
    }
}
