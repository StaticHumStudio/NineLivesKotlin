package com.ninelivesaudio.app.ui.settings

import com.ninelivesaudio.app.domain.model.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Orphaned" books are the ones the user can no longer manage manually: their
 * source folder was removed, so the app has released the persisted permission
 * and can't rescan/restore them. A library is manageable only while the app
 * still holds access to its folder URI.
 */
class ArchiveSweepTest {

    private fun local(id: String, folderUri: String?) =
        Library(id = id, name = id, folderUri = folderUri, isLocal = true)

    @Test
    fun `a library with a held folder permission is accessible`() {
        assertTrue(
            isLibraryFolderAccessible("content://tree/A", setOf("content://tree/A", "content://tree/B")),
        )
    }

    @Test
    fun `a library whose permission was released is not accessible`() {
        assertFalse(
            isLibraryFolderAccessible("content://tree/A", setOf("content://tree/B")),
        )
    }

    @Test
    fun `a library with no folder uri is not accessible`() {
        assertFalse(isLibraryFolderAccessible(null, setOf("content://tree/A")))
        assertFalse(isLibraryFolderAccessible("", setOf("content://tree/A")))
    }

    @Test
    fun `orphaned libraries are exactly those the app cannot access`() {
        val held = local("held", "content://tree/HELD")
        val removed = local("removed", "content://tree/REMOVED")
        val noUri = local("nouri", null)
        val accessible = setOf("content://tree/HELD")

        val orphaned = orphanedLibraries(listOf(held, removed, noUri), accessible)

        assertEquals(listOf("removed", "nouri"), orphaned.map { it.id })
    }
}
