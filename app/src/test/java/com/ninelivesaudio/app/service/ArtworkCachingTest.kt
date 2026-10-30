package com.ninelivesaudio.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Android Auto's art caches are keyed by a cover version, so an edited cover refreshes (#70). */
class ArtworkCachingTest {

    private val v1 = "https://abs.test/api/items/li_1/cover?ts=1"
    private val v2 = "https://abs.test/api/items/li_1/cover?ts=2"

    @Test
    fun `an edited cover gets a new key`() {
        assertNotEquals(artworkVersionKey("li_1", v1, null, null), artworkVersionKey("li_1", v2, null, null))
    }

    @Test
    fun `a rewritten saved cover gets a new key`() {
        val saved = "file:///d/li_1/cover.jpg"
        assertNotEquals(artworkVersionKey("li_1", v1, saved, 1), artworkVersionKey("li_1", v1, saved, 2))
    }

    @Test
    fun `the same cover keeps its key`() {
        assertEquals(artworkVersionKey("li_1", v1, null, null), artworkVersionKey("li_1", v1, null, null))
        val saved = "file:///d/li_1/cover.jpg"
        assertEquals(artworkVersionKey("li_1", v1, saved, 9), artworkVersionKey("li_1", v1, saved, 9))
    }

    @Test
    fun `different books never share a key`() {
        assertNotEquals(artworkVersionKey("li_1", null, null, null), artworkVersionKey("li_2", null, null, null))
    }
}
