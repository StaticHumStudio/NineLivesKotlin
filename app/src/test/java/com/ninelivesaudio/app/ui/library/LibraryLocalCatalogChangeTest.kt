package com.ninelivesaudio.app.ui.library

import com.ninelivesaudio.app.data.local.entity.LocalBookMembership
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A folder scan writes its library row and books from Settings, and LOCAL
 * mode has no sync record to announce them. The Library tab opened during a
 * first-run scan stayed on "The Archive Stands Empty" until it was re-entered.
 * It now compares each local catalog snapshot with the last one.
 */
class LibraryLocalCatalogChangeTest {

    private val audiobooks = Library(id = "audiobooks", isLocal = true)
    private val podcasts = Library(id = "podcasts", isLocal = true)
    private val nothing = LocalCatalogSnapshot(libraryIds = emptySet(), books = emptySet())
    private val folderOnly = LocalCatalogSnapshot(libraryIds = setOf("audiobooks"), books = emptySet())
    private val folderWithBooks = folderOnly.copy(
        books = setOf(live("book-1"), live("book-2")),
    )

    @Test
    fun `the first snapshot is the baseline every load waits for`() {
        // Loads read only after it arrives, so it can never hold a write that
        // the shelf missed. Acting on it would reload every time the tab opens.
        assertEquals(
            LocalCatalogChange.NONE,
            decideLocalCatalogChange(
                null, folderWithBooks, selectedLibrary = null, shownLibraryIds = emptyList(), isLoadBaseline = true,
            ),
        )
    }

    @Test
    fun `a watch that starts after a switch into LOCAL mode checks its first snapshot`() {
        // No load waited for this one. The shelf still shows what the server
        // mode loaded, so the local folders have to be loaded now.
        assertEquals(
            LocalCatalogChange.RELOAD,
            decideLocalCatalogChange(null, folderWithBooks, Library(id = "server"), listOf("server"), isLoadBaseline = false),
        )
    }

    @Test
    fun `an unchanged catalog does nothing`() {
        assertEquals(
            LocalCatalogChange.NONE,
            decideLocalCatalogChange(folderWithBooks, folderWithBooks, audiobooks, listOf("audiobooks"), isLoadBaseline = false),
        )
    }

    @Test
    fun `a first scan's folder appearing reloads the library list`() {
        assertEquals(
            LocalCatalogChange.RELOAD,
            decideLocalCatalogChange(nothing, folderOnly, selectedLibrary = null, shownLibraryIds = emptyList(), isLoadBaseline = false),
        )
    }

    @Test
    fun `books arriving in the shown folder refilter the shelf`() {
        assertEquals(
            LocalCatalogChange.REFILTER,
            decideLocalCatalogChange(folderOnly, folderWithBooks, audiobooks, listOf("audiobooks"), isLoadBaseline = false),
        )
    }

    @Test
    fun `books arriving while nothing is selected reload the library list`() {
        assertEquals(
            LocalCatalogChange.RELOAD,
            decideLocalCatalogChange(folderOnly, folderWithBooks, selectedLibrary = null, shownLibraryIds = emptyList(), isLoadBaseline = false),
        )
    }

    @Test
    fun `a book archived by a rescan refilters the shelf`() {
        val archived = folderOnly.copy(books = setOf(live("book-1"), archived("book-2")))
        assertEquals(
            LocalCatalogChange.REFILTER,
            decideLocalCatalogChange(folderWithBooks, archived, audiobooks, listOf("audiobooks"), isLoadBaseline = false),
        )
    }

    @Test
    fun `a second folder being added reloads the library list`() {
        val twoFolders = folderWithBooks.copy(libraryIds = setOf("audiobooks", "podcasts"))
        assertEquals(
            LocalCatalogChange.RELOAD,
            decideLocalCatalogChange(folderWithBooks, twoFolders, audiobooks, listOf("audiobooks"), isLoadBaseline = false),
        )
    }

    @Test
    fun `a reload keeps a library pick whose save has not landed`() {
        // The user picked podcasts, and a catalog reload cancelled that pick
        // before its settings write finished. The reload saves the pick
        // instead of switching the shelf back to audiobooks.
        val saved = AppSettings(appMode = AppMode.LOCAL, selectedLocalLibraryId = "audiobooks")
        val kept = settingsKeepingPendingPick("podcasts", listOf(audiobooks, podcasts), saved)
        assertEquals("podcasts", kept?.selectedLocalLibraryId)
    }

    @Test
    fun `a reload with the pick already saved writes nothing`() {
        val saved = AppSettings(appMode = AppMode.LOCAL, selectedLocalLibraryId = "audiobooks")
        assertNull(settingsKeepingPendingPick("audiobooks", listOf(audiobooks, podcasts), saved))
    }

    @Test
    fun `a reload does not keep a library that is gone`() {
        val saved = AppSettings(appMode = AppMode.LOCAL, selectedLocalLibraryId = "audiobooks")
        assertNull(settingsKeepingPendingPick("podcasts", listOf(audiobooks), saved))
    }

    @Test
    fun `a reload with no unfinished pick leaves the saved choice alone`() {
        // Settings saved podcasts since the Library last picked anything.
        val saved = AppSettings(appMode = AppMode.LOCAL, selectedLocalLibraryId = "podcasts")
        assertNull(settingsKeepingPendingPick(null, listOf(audiobooks, podcasts), saved))
    }

    @Test
    fun `a reload with nothing picked leaves the saved choice to the normal rules`() {
        val saved = AppSettings(appMode = AppMode.LOCAL, selectedLocalLibraryId = null)
        assertNull(settingsKeepingPendingPick(null, listOf(audiobooks), saved))
    }

    @Test
    fun `a reload never saves a server library as the local choice`() {
        val server = Library(id = "server", isLocal = false)
        val saved = AppSettings(appMode = AppMode.LOCAL, selectedLocalLibraryId = "audiobooks")
        assertNull(settingsKeepingPendingPick("server", listOf(audiobooks, server), saved))
    }

    private fun live(id: String) = LocalBookMembership(id = id, libraryId = "audiobooks", isArchived = false)

    private fun archived(id: String) = LocalBookMembership(id = id, libraryId = "audiobooks", isArchived = true)
}
