package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AudioBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The actively-playing item's session metadata is read by out-of-process
 * controllers (Android Auto/Automotive, Wear OS) that resolve MediaMetadata's
 * artwork URI themselves. A downloaded book's local file:// cover isn't
 * readable outside this app's process (same constraint already fixed for
 * MediaBrowseTree's browse-item covers), so this must always be the remote
 * URL, never AudioBook.effectiveCoverPath.
 */
class SessionArtworkUriTest {

    @Test
    fun `uses the remote cover even when a local cover is downloaded`() {
        val book = AudioBook(
            id = "1",
            coverPath = "https://server/api/items/1/cover",
            localCoverPath = "file:///data/data/com.ninelivesaudio.app/files/local_covers/1.jpg",
        )
        assertEquals("https://server/api/items/1/cover", PlaybackManager.sessionArtworkUri(book))
    }

    @Test
    fun `falls back to null when there is no remote cover`() {
        val book = AudioBook(
            id = "1",
            coverPath = null,
            localCoverPath = "file:///data/data/com.ninelivesaudio.app/files/local_covers/1.jpg",
        )
        assertNull(PlaybackManager.sessionArtworkUri(book))
    }
}
