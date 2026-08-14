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
            dossierSessionsInActiveScope(listOf(included, excluded), setOf("active-book")),
        )
        assertEquals(
            emptyList<ListeningSession>(),
            dossierSessionsInActiveScope(listOf(included, excluded), emptySet()),
        )
    }

    private fun session(id: String, bookId: String) = ListeningSession(
        id = id,
        libraryItemId = bookId,
        currentTime = Duration.ZERO,
        timeListening = Duration.ZERO,
        startedAt = 0L,
        updatedAt = 0L,
        displayTitle = null,
    )
}
