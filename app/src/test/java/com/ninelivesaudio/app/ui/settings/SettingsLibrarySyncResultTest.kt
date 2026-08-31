package com.ninelivesaudio.app.ui.settings

import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.domain.model.Library
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsLibrarySyncResultTest {

    private fun library(id: String) = Library(id = id, name = id)

    @Test
    fun `an empty complete server sync replaces the stale Settings library snapshot`() {
        val cached = listOf(library("lib-a"), library("lib-b"))

        val libraries = settingsLibrariesAfterServerSync(
            cached = cached,
            result = RemoteResult.Ok(emptyList()),
        )

        assertEquals(emptyList<Library>(), libraries)
    }

    @Test
    fun `a failed server sync keeps the cached Settings library snapshot`() {
        val cached = listOf(library("lib-a"), library("lib-b"))

        val libraries = settingsLibrariesAfterServerSync(
            cached = cached,
            result = RemoteResult.Failed("HTTP 500"),
        )

        assertEquals(cached, libraries)
    }

    @Test
    fun `an incomplete server sync keeps the cached Settings library snapshot`() {
        val cached = listOf(library("lib-a"), library("lib-b"))

        val libraries = settingsLibrariesAfterServerSync(
            cached = cached,
            result = RemoteResult.Partial(listOf(library("lib-c")), "page 1: timeout"),
        )

        assertEquals(cached, libraries)
    }
}
