package com.ninelivesaudio.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers must survive offline. A downloaded book saves its cover to local
 * storage; effectiveCoverPath prefers that local file so the cover renders
 * with no network, falling back to the remote server URL otherwise.
 */
class AudioBookCoverTest {

    @Test
    fun `prefers the local cover when downloaded`() {
        val book = AudioBook(
            id = "1",
            coverPath = "https://server/api/items/1/cover",
            localCoverPath = "file:///data/books/1/cover.jpg",
        )
        assertEquals("file:///data/books/1/cover.jpg", book.effectiveCoverPath)
    }

    @Test
    fun `falls back to the remote cover when no local cover`() {
        val book = AudioBook(
            id = "1",
            coverPath = "https://server/api/items/1/cover",
            localCoverPath = null,
        )
        assertEquals("https://server/api/items/1/cover", book.effectiveCoverPath)
    }

    @Test
    fun `is null when neither cover is present`() {
        assertNull(AudioBook(id = "1").effectiveCoverPath)
    }
}
