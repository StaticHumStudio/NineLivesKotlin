package com.ninelivesaudio.app.ui.library

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tabs keep the Library alive while another tab is open. A server with
 * thousands of books took minutes to re-download on every return, so a return
 * now keeps the shelf unless what it was loaded for changed.
 */
class LibraryReturnReloadTest {

    private val shown = ShelfIdentity(AppMode.AUDIOBOOKSHELF, "https://abs", "jeff", "books")

    @Test
    fun `an unchanged return keeps the shelf`() {
        assertFalse(shouldReloadOnLibraryReturn(shown, shown, lastFetchFailed = false))
    }

    @Test
    fun `a library picked in Settings reloads`() {
        assertTrue(shouldReloadOnLibraryReturn(shown, shown.copy(activeLibraryId = "podcasts"), false))
    }

    @Test
    fun `a server, account, or mode switch reloads`() {
        assertTrue(shouldReloadOnLibraryReturn(shown, shown.copy(serverUrl = "https://other"), false))
        assertTrue(shouldReloadOnLibraryReturn(shown, shown.copy(username = "guest"), false))
        assertTrue(shouldReloadOnLibraryReturn(shown, shown.copy(appMode = AppMode.LOCAL), false))
    }

    @Test
    fun `an empty failed shelf retries on return`() {
        assertTrue(shouldReloadOnLibraryReturn(shown, shown, lastFetchFailed = true))
    }

    @Test
    fun `the first entry leaves the shelf to the load already starting`() {
        assertFalse(shouldReloadOnLibraryReturn(null, shown, lastFetchFailed = true))
    }

    @Test
    fun `identity follows the active mode's library`() {
        val settings = AppSettings(
            appMode = AppMode.LOCAL,
            serverUrl = "https://abs",
            username = "jeff",
            selectedLibraryId = "books",
            selectedLocalLibraryId = "folder",
        )
        assertEquals("folder", settings.shelfIdentity().activeLibraryId)
        assertEquals("books", settings.copy(appMode = AppMode.AUDIOBOOKSHELF).shelfIdentity().activeLibraryId)
    }
}
