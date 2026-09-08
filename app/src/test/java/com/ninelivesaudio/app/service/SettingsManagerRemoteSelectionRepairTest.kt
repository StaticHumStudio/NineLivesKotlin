package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsManagerRemoteSelectionRepairTest {
    @Test
    fun `invalid remote selection clears without rebinding or changing local selection`() {
        val settings = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            selectedLibraryId = "raw-library",
            selectedLocalLibraryId = "local-library",
        )

        assertEquals(
            settings.copy(selectedLibraryId = null),
            clearInvalidRemoteLibrarySelection(settings) { false },
        )
    }

    @Test
    fun `valid remote selection is retained`() {
        val settings = AppSettings(selectedLibraryId = "namespaced-library")

        assertEquals(settings, clearInvalidRemoteLibrarySelection(settings) { it == "namespaced-library" })
    }
}
