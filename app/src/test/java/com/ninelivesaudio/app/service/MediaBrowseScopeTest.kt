package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Library
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaBrowseScopeTest {

    @Test
    fun `Android Auto advertises only the active library and its playable books`() {
        val settings = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            selectedLibraryId = "server-active",
            selectedLocalLibraryId = "local-active",
        )
        val libraries = listOf(
            Library(id = "local-active", isLocal = true),
            Library(id = "server-active", isLocal = false),
            Library(id = "server-other", isLocal = false),
        )
        val books = listOf(
            AudioBook(id = "active", libraryId = "server-active", isLocal = false),
            AudioBook(id = "other", libraryId = "server-other", isLocal = false),
            AudioBook(id = "local", libraryId = "local-active", isLocal = true),
            AudioBook(id = "archived", libraryId = "server-active", isLocal = false, archivedAt = 1L),
        )

        assertEquals(listOf("server-active"), browseLibrariesInActiveScope(libraries, settings).map { it.id })
        assertEquals(listOf("active"), browseBooksInActiveScope(books, settings).map { it.id })
    }

    @Test
    fun `Android Auto advertises no playable book before an active library resolves`() {
        val books = listOf(
            AudioBook(id = "server", libraryId = "server-active", isLocal = false),
        )

        assertEquals(
            emptyList<AudioBook>(),
            browseBooksInActiveScope(
                books,
                AppSettings(appMode = AppMode.AUDIOBOOKSHELF, selectedLibraryId = null),
            ),
        )
    }

    @Test
    fun `rejected Android Auto load never reuses the previous player queue`() {
        assertEquals(false, shouldReturnPlayerQueueToAuto(loadSucceeded = false, playerItemCount = 5))
        assertEquals(true, shouldReturnPlayerQueueToAuto(loadSucceeded = true, playerItemCount = 5))
        assertEquals(false, shouldReturnPlayerQueueToAuto(loadSucceeded = true, playerItemCount = 0))
    }

    @Test
    fun `Android Auto recent limit is applied inside the active library query`() = runBlocking {
        val settings = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            selectedLibraryId = "server-active",
        )
        val activeBook = AudioBook(id = "active", libraryId = "server-active", isLocal = false)
        val inactiveBooks = List(25) { index ->
            AudioBook(id = "inactive-$index", libraryId = "server-other", isLocal = false)
        }

        val result = recentBooksInActiveScope(settings, limit = 20) { libraryId, limit ->
            if (libraryId == "server-active") listOf(activeBook).take(limit)
            else inactiveBooks.take(limit)
        }

        assertEquals(listOf(activeBook), result)
    }
}
