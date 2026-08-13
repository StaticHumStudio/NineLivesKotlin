package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibrarySelectionRepairTest {

    @Test
    fun `replaces historically polluted server selection with a cached server library`() {
        val libraries = listOf(
            Library(id = "local-library", isLocal = true),
            Library(id = "server-library", isLocal = false),
        )

        assertEquals(
            "server-library",
            repairedServerLibraryId(
                selectedLibraryId = "local-library",
                libraries = libraries,
            ),
        )
    }

    @Test
    fun `repair preserves the independent local selection and current mode`() {
        val repaired = repairedLibrarySelections(
            settings = AppSettings(
                appMode = AppMode.LOCAL,
                selectedLibraryId = "local-library",
                selectedLocalLibraryId = "local-library",
            ),
            libraries = listOf(
                Library(id = "local-library", isLocal = true),
                Library(id = "server-library", isLocal = false),
            ),
        )

        assertEquals(AppMode.LOCAL, repaired.appMode)
        assertEquals("local-library", repaired.selectedLocalLibraryId)
        assertEquals("server-library", repaired.selectedLibraryId)
    }

    @Test
    fun `valid cached server selection is preserved`() {
        assertEquals(
            "server-two",
            repairedServerLibraryId(
                selectedLibraryId = "server-two",
                libraries = listOf(
                    Library(id = "server-one", isLocal = false),
                    Library(id = "server-two", isLocal = false),
                ),
            ),
        )
    }

    @Test
    fun `unknown server selection is preserved until its cache returns`() {
        assertEquals(
            "server-not-cached",
            repairedServerLibraryId(
                selectedLibraryId = "server-not-cached",
                libraries = listOf(Library(id = "local-library", isLocal = true)),
            ),
        )
    }

    @Test
    fun `polluted selection clears when no cached server library exists`() {
        assertNull(
            repairedServerLibraryId(
                selectedLibraryId = "local-library",
                libraries = listOf(Library(id = "local-library", isLocal = true)),
            )
        )
    }
}
