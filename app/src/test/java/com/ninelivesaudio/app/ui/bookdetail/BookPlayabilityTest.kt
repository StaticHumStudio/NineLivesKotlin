package com.ninelivesaudio.app.ui.bookdetail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An archived book's source file is gone, so it is never playable. */
class BookPlayabilityTest {

    @Test
    fun `local book is playable`() {
        assertTrue(canPlayBook(isLocal = true, isDownloaded = false, isArchived = false))
    }

    @Test
    fun `downloaded remote book is playable`() {
        assertTrue(canPlayBook(isLocal = false, isDownloaded = true, isArchived = false))
    }

    @Test
    fun `archived book is never playable`() {
        assertFalse(canPlayBook(isLocal = true, isDownloaded = true, isArchived = true))
    }

    @Test
    fun `remote not-downloaded book is not playable`() {
        assertFalse(canPlayBook(isLocal = false, isDownloaded = false, isArchived = false))
    }
}
