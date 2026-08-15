package com.ninelivesaudio.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A late-landing artwork fetch must only be applied if the user is still on
 * the book it was fetched for. Applying it after a book switch is exactly
 * how Android Auto ended up showing a previous book's cover (issue #89).
 */
class ShouldRepublishArtworkTest {

    @Test
    fun `applies when the fetched book is still current and tracks are loaded`() {
        assertTrue(shouldRepublishArtwork(currentBookId = "book-1", fetchedForBookId = "book-1", currentMediaItemCount = 3))
    }

    @Test
    fun `rejects when the user switched to a different book`() {
        assertFalse(shouldRepublishArtwork(currentBookId = "book-2", fetchedForBookId = "book-1", currentMediaItemCount = 3))
    }

    @Test
    fun `rejects when nothing is loaded`() {
        assertFalse(shouldRepublishArtwork(currentBookId = null, fetchedForBookId = "book-1", currentMediaItemCount = 3))
    }

    @Test
    fun `rejects when the player has no media items`() {
        assertFalse(shouldRepublishArtwork(currentBookId = "book-1", fetchedForBookId = "book-1", currentMediaItemCount = 0))
    }
}
