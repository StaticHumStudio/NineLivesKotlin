package com.ninelivesaudio.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An empty Home must not tell someone with an imported library to add a
 * folder (#59). Unplayed books point at the Library, a truly empty library
 * keeps the original copy.
 */
class HomeEmptyCopyTest {

    @Test
    fun `empty home picks its kind from mode and whether books exist`() {
        assertEquals(HomeEmptyKind.LOCAL_NO_BOOKS, homeEmptyKind(isLocalMode = true, libraryHasBooks = false))
        assertEquals(HomeEmptyKind.LOCAL_UNPLAYED, homeEmptyKind(isLocalMode = true, libraryHasBooks = true))
        assertEquals(HomeEmptyKind.SERVER_NO_BOOKS, homeEmptyKind(isLocalMode = false, libraryHasBooks = false))
        assertEquals(HomeEmptyKind.SERVER_UNPLAYED, homeEmptyKind(isLocalMode = false, libraryHasBooks = true))
    }

    @Test
    fun `local library with books never says add a folder`() {
        val copy = homeEmptyCopy(HomeEmptyKind.LOCAL_UNPLAYED)

        assertEquals(HomeEmptyAction.OPEN_LIBRARY, copy.action)
        assertTrue(copy.body!!.contains("Library"))
        listOf(copy.subtitle, copy.body, copy.button).forEach { line ->
            assertFalse(line, line!!.contains("folder", ignoreCase = true))
            assertFalse(line, line.contains("Settings"))
        }
    }

    @Test
    fun `truly empty local library keeps the add a folder copy and Settings button`() {
        val copy = homeEmptyCopy(HomeEmptyKind.LOCAL_NO_BOOKS)

        assertEquals("No local audio yet", copy.subtitle)
        assertEquals("Add a folder of audiobooks in Settings to begin", copy.body)
        assertEquals("Open Settings", copy.button)
        assertEquals(HomeEmptyAction.OPEN_SETTINGS, copy.action)
    }

    @Test
    fun `empty server library keeps its original copy and flavor slot`() {
        val copy = homeEmptyCopy(HomeEmptyKind.SERVER_NO_BOOKS)

        assertEquals("The Archive stands empty", copy.subtitle)
        assertNull(copy.body)
        assertEquals("Enter The Archive", copy.button)
        assertEquals(HomeEmptyAction.OPEN_LIBRARY, copy.action)
    }

    @Test
    fun `server library with books no longer claims the Archive is empty`() {
        val copy = homeEmptyCopy(HomeEmptyKind.SERVER_UNPLAYED)

        assertFalse(copy.subtitle.contains("empty", ignoreCase = true))
        // A fixed body, so the empty-library Ritual and Unhinged flavors
        // ("Add something to the collection") never show for a full library.
        assertTrue(copy.body!!.contains("Library"))
        assertEquals(HomeEmptyAction.OPEN_LIBRARY, copy.action)
    }

    @Test
    fun `empty home copy has no dashes or semicolons`() {
        HomeEmptyKind.entries.map(::homeEmptyCopy).forEach { copy ->
            listOfNotNull(copy.subtitle, copy.body, copy.button).forEach { line ->
                assertFalse(line, line.contains('\u2014') || line.contains('\u2013') || line.contains(';'))
                assertFalse(line, line.contains(" - "))
            }
        }
    }
}
