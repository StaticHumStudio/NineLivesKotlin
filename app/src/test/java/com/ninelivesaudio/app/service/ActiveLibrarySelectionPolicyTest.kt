package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveLibrarySelectionPolicyTest {

    private val local = Library(id = "local", name = "Local", isLocal = true)
    private val serverOne = Library(id = "server-1", name = "Server One", isLocal = false)
    private val serverTwo = Library(id = "server-2", name = "Server Two", isLocal = false)

    @Test
    fun `stale server selection falls back and requires persistence`() {
        val result = resolveActiveLibrarySelection(
            libraries = listOf(local, serverOne, serverTwo),
            settings = AppSettings(
                appMode = AppMode.AUDIOBOOKSHELF,
                selectedLibraryId = "missing",
                selectedLocalLibraryId = local.id,
            ),
        )

        assertEquals(serverOne, result.library)
        assertTrue(result.requiresPersistence)
        assertEquals(serverOne.id, result.settings.selectedLibraryId)
        assertEquals(local.id, result.settings.selectedLocalLibraryId)
    }

    @Test
    fun `valid active selection is preserved without persistence`() {
        val result = resolveActiveLibrarySelection(
            libraries = listOf(local, serverOne, serverTwo),
            settings = AppSettings(
                appMode = AppMode.AUDIOBOOKSHELF,
                selectedLibraryId = serverTwo.id,
            ),
        )

        assertEquals(serverTwo, result.library)
        assertFalse(result.requiresPersistence)
    }

    @Test
    fun `empty eligible libraries preserve saved selection without persistence`() {
        val settings = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            selectedLibraryId = serverTwo.id,
            selectedLocalLibraryId = local.id,
        )

        val result = resolveActiveLibrarySelection(
            libraries = listOf(local),
            settings = settings,
        )

        assertEquals(null, result.library)
        assertEquals(settings, result.settings)
        assertFalse(result.requiresPersistence)
    }
}
