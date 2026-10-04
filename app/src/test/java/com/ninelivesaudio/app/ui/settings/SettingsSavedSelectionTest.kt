package com.ninelivesaudio.app.ui.settings

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.Library
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tabs keep Settings alive while another tab is open. A library picked on
 * the Library tab meanwhile has to show in Settings on return, or the local
 * cleanup actions ("All Books", "All Deleted Books") target the old library.
 */
class SettingsSavedSelectionTest {

    private val folderA = Library(id = "a", name = "A", isLocal = true, folderUri = "content://a")
    private val folderB = Library(id = "b", name = "B", isLocal = true, folderUri = "content://b")
    private val books = Library(id = "books", name = "Books")
    private val podcasts = Library(id = "podcasts", name = "Podcasts")

    private val shown = SettingsViewModel.UiState(
        localLibraries = listOf(folderA, folderB),
        selectedLocalLibrary = folderA,
        libraries = listOf(books, podcasts),
        selectedLibrary = books,
    )

    @Test
    fun `a local folder picked on another tab shows here`() {
        val settings = AppSettings(appMode = AppMode.LOCAL, selectedLocalLibraryId = "b", selectedLibraryId = "books")

        assertEquals(folderB, shown.withSavedSelections(settings).selectedLocalLibrary)
    }

    @Test
    fun `a server library picked on another tab shows here`() {
        val settings = AppSettings(appMode = AppMode.AUDIOBOOKSHELF, selectedLibraryId = "podcasts", selectedLocalLibraryId = "a")

        assertEquals(podcasts, shown.withSavedSelections(settings).selectedLibrary)
    }

    @Test
    fun `an unchanged selection stays put`() {
        val settings = AppSettings(selectedLocalLibraryId = "a", selectedLibraryId = "books")

        assertEquals(shown, shown.withSavedSelections(settings))
    }

    @Test
    fun `an id this screen does not know yet leaves the pick alone`() {
        val settings = AppSettings(selectedLocalLibraryId = "new-folder", selectedLibraryId = null)

        val after = shown.withSavedSelections(settings)
        assertEquals(folderA, after.selectedLocalLibrary)
        assertEquals(books, after.selectedLibrary)
    }
}
