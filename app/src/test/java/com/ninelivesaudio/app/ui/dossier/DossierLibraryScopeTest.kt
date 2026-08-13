package com.ninelivesaudio.app.ui.dossier

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.AudioBook
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
