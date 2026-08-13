package com.ninelivesaudio.app.service.local

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFolderAccessPolicyTest {

    private val localLibrary = Library(
        id = "local-library",
        isLocal = true,
        folderUri = "content://books/tree/local",
    )

    private val localBook = AudioBook(
        id = "local-book",
        libraryId = localLibrary.id,
        isLocal = true,
        isDownloaded = true,
        localPath = "content://books/document/local%2Fbook.m4b",
    )

    @Test
    fun `read grant makes matching local library accessible`() {
        val accessible = accessibleLocalLibraryIds(
            libraries = listOf(localLibrary),
            persistedReadGrantUris = setOf(localLibrary.folderUri!!),
        )

        assertEquals(setOf(localLibrary.id), accessible)
    }

    @Test
    fun `missing read grant leaves stored library inaccessible`() {
        val accessible = accessibleLocalLibraryIds(
            libraries = listOf(localLibrary),
            persistedReadGrantUris = emptySet(),
        )

        assertTrue(accessible.isEmpty())
    }

    @Test
    fun `inaccessible local book is neither downloaded nor playable and needs recovery`() {
        val result = reconcileLocalBookAccess(localBook, accessibleLocalLibraryIds = emptySet())

        assertFalse(result.book.isDownloaded)
        assertFalse(result.isPlayable)
        assertTrue(result.needsFolderRecovery)
    }

    @Test
    fun `accessible local book remains downloaded and playable`() {
        val result = reconcileLocalBookAccess(
            localBook,
            accessibleLocalLibraryIds = setOf(localLibrary.id),
        )

        assertTrue(result.book.isDownloaded)
        assertTrue(result.isPlayable)
        assertFalse(result.needsFolderRecovery)
    }

    @Test
    fun `archived local book stays unplayable even with a grant`() {
        val result = reconcileLocalBookAccess(
            localBook.copy(archivedAt = 1L),
            accessibleLocalLibraryIds = setOf(localLibrary.id),
        )

        assertFalse(result.isPlayable)
        assertFalse(result.needsFolderRecovery)
    }
}
