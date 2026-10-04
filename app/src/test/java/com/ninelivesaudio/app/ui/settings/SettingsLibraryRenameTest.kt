package com.ninelivesaudio.app.ui.settings

import com.ninelivesaudio.app.domain.model.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SettingsLibraryRenameTest {

    @Test
    fun `a library renamed in the cache shows its new name`() {
        val shown = listOf(Library(id = "a", name = "Mock Shelf"), Library(id = "b", name = "Podcasts"))
        val cached = listOf(Library(id = "a", name = "Renamed Shelf"), Library(id = "b", name = "Podcasts"))

        assertEquals(listOf("Renamed Shelf", "Podcasts"), withCachedLibraryNames(shown, cached).map { it.name })
    }

    @Test
    fun `only names change, never which libraries are shown`() {
        val shown = listOf(Library(id = "a", name = "A"))
        val cached = listOf(Library(id = "a", name = "A2"), Library(id = "new", name = "New"))

        assertEquals(listOf("a"), withCachedLibraryNames(shown, cached).map { it.id })
        assertEquals(shown, withCachedLibraryNames(shown, emptyList()))
    }

    @Test
    fun `an unchanged cache hands back the same list`() {
        val shown = listOf(Library(id = "a", name = "A"))

        assertSame(shown.single(), withCachedLibraryNames(shown, listOf(Library(id = "a", name = "A"))).single())
    }
}
