package com.ninelivesaudio.app.ui.dossier

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.ListeningSession
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration

class DossierLibraryScopeTest {

    @Test
    fun `local mode Dossier uses local selection instead of server selection`() {
        val localBook = AudioBook(id = "local", libraryId = "local-library", isLocal = true)
        val serverBook = AudioBook(id = "server", libraryId = "server-library", isLocal = false)
        val settings = AppSettings(
            appMode = AppMode.LOCAL,
            selectedLibraryId = "server-library",
            selectedLocalLibraryId = "local-library",
        )

        val result = dossierBooksInActiveScope(listOf(localBook, serverBook), settings)

        assertEquals(listOf(localBook), result)
    }

    @Test
    fun `Dossier returns no books when current mode has no active library`() {
        val books = listOf(
            AudioBook(id = "local", libraryId = "local-library", isLocal = true),
            AudioBook(id = "server", libraryId = "server-library", isLocal = false),
        )
        val settings = AppSettings(appMode = AppMode.LOCAL)

        assertEquals(emptyList<AudioBook>(), dossierBooksInActiveScope(books, settings))
    }

    @Test
    fun `Dossier rejects a server book carrying the local library id`() {
        val localBook = AudioBook(id = "local", libraryId = "local-library", isLocal = true)
        val wrongSource = AudioBook(id = "server", libraryId = "local-library", isLocal = false)
        val settings = AppSettings(
            appMode = AppMode.LOCAL,
            selectedLocalLibraryId = "local-library",
        )

        assertEquals(listOf(localBook), dossierBooksInActiveScope(listOf(localBook, wrongSource), settings))
    }

    @Test
    fun `Dossier sessions are limited to scoped book ids`() {
        val included = session(id = "included", bookId = "active-book")
        val excluded = session(id = "excluded", bookId = "inactive-book")

        assertEquals(
            listOf(included),
            dossierSessionsInActiveScope(listOf(included, excluded), setOf("active-book"), "active-library"),
        )
        assertEquals(
            emptyList<ListeningSession>(),
            dossierSessionsInActiveScope(listOf(included, excluded), emptySet(), "active-library"),
        )
    }

    // Issue #66: a book pruned from the cache took its listening history out
    // of the Dossier with it. A session now also counts through its own
    // library id, which the server sends with every session.

    @Test
    fun `a session for a book no longer cached still counts in its own library`() {
        val orphan = session(id = "o", bookId = "pruned-book", libraryId = "active-library")
        assertEquals(listOf(orphan), dossierSessionsInActiveScope(listOf(orphan), emptySet(), "active-library"))
    }

    @Test
    fun `a session from another library never counts here`() {
        val other = session(id = "x", bookId = "pruned-book", libraryId = "other-library")
        assertEquals(emptyList<ListeningSession>(), dossierSessionsInActiveScope(listOf(other), emptySet(), "active-library"))
    }

    @Test
    fun `a session with no library id for an uncached book stays out`() {
        val old = session(id = "n", bookId = "pruned-book", libraryId = null)
        assertEquals(emptyList<ListeningSession>(), dossierSessionsInActiveScope(listOf(old), emptySet(), "active-library"))
    }

    @Test
    fun `with no active library an uncached book's session stays out`() {
        val orphan = session(id = "o", bookId = "pruned-book", libraryId = "active-library")
        assertEquals(emptyList<ListeningSession>(), dossierSessionsInActiveScope(listOf(orphan), emptySet(), null))
    }

    @Test
    fun `a cached book counts by its cache scope whatever library the session names`() {
        // The cached book already passed the active library and source check,
        // so the session's own library id is not asked twice.
        val cached = session(id = "c", bookId = "active-book", libraryId = "renamed-library")
        assertEquals(listOf(cached), dossierSessionsInActiveScope(listOf(cached), setOf("active-book"), "active-library"))
    }

    @Test
    fun `a book no longer cached takes its title from its newest session that has one`() {
        assertEquals(
            "Dracula",
            uncachedBookTitle(
                listOf(
                    session("a", "b"),
                    session("b", "b", displayTitle = "Dracula"),
                    session("c", "b", displayTitle = "Old name"),
                ),
            ),
        )
        assertEquals("Unknown Title", uncachedBookTitle(listOf(session("a", "b", displayTitle = " "))))
    }

    private fun session(
        id: String,
        bookId: String,
        libraryId: String? = null,
        displayTitle: String? = null,
    ) = ListeningSession(
        id = id,
        libraryItemId = bookId,
        currentTime = Duration.ZERO,
        timeListening = Duration.ZERO,
        startedAt = 0L,
        updatedAt = 0L,
        displayTitle = displayTitle,
        libraryId = libraryId,
    )
}
