package com.ninelivesaudio.app.ui.home

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSourceGateTest {

    @Test
    fun `server cache requires token url and selected library`() {
        assertFalse(canShowHomeBooks(AppSettings(appMode = AppMode.AUDIOBOOKSHELF), hasAuthToken = true))
        assertFalse(
            canShowHomeBooks(
                AppSettings(appMode = AppMode.AUDIOBOOKSHELF, selectedLibraryId = "server"),
                hasAuthToken = true,
            ),
        )
        assertTrue(
            canShowHomeBooks(
                AppSettings(
                    appMode = AppMode.AUDIOBOOKSHELF,
                    selectedLibraryId = "server",
                    serverUrl = "https://server.example",
                ),
                hasAuthToken = true,
            ),
        )
    }

    @Test
    fun `selected local folder remains visible without server login`() {
        assertTrue(
            canShowHomeBooks(
                AppSettings(appMode = AppMode.LOCAL, selectedLocalLibraryId = "local"),
                hasAuthToken = false,
            ),
        )
    }
}
