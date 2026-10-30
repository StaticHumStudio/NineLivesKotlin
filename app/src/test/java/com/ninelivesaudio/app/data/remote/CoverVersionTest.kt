package com.ninelivesaudio.app.data.remote

import com.ninelivesaudio.app.data.remote.dto.ApiLibraryItem
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * An edited cover keeps the same item URL, so every cache keyed by it kept
 * the old picture (#70). The stored URL now carries the item's updated time
 * as `ts`, the way the Audiobookshelf web client does.
 */
class CoverVersionTest {

    private val cover = "https://abs.test/api/items/li_1/cover"

    @Test
    fun `a cover url carries the item's updated time`() {
        assertEquals("$cover?ts=1730000000000", versionedCoverUrl(cover, 1730000000000))
    }

    @Test
    fun `no updated time leaves the url as it was`() {
        assertEquals(cover, versionedCoverUrl(cover, null))
        assertEquals(cover, versionedCoverUrl(cover, 0))
        assertEquals(cover, versionedCoverUrl(cover, -5))
    }

    @Test
    fun `the version reads back from the url`() {
        assertEquals(1730000000000, coverVersionOf("$cover?ts=1730000000000"))
        assertEquals(5L, coverVersionOf("$cover?ts=5&width=256"))
        assertEquals(7L, coverVersionOf("$cover?width=256&ts=7"))
        assertEquals(1730000000000, coverVersionOf(versionedCoverUrl(cover, 1730000000000)))
    }

    @Test
    fun `a url without a version has none`() {
        assertNull(coverVersionOf(cover))
        assertNull(coverVersionOf("$cover?ts=abc"))
        assertNull(coverVersionOf("$cover?ts="))
        assertNull(coverVersionOf("$cover?width=256"))
        assertNull(coverVersionOf("$cover?rts=5"))
        assertNull(coverVersionOf(null))
        assertNull(coverVersionOf("file:///d/cover.jpg"))
    }

    @Test
    fun `the library list's updated time decodes`() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(
            1730000000000,
            json.decodeFromString<ApiLibraryItem>("""{"id":"li_1","updatedAt":1730000000000}""").updatedAt,
        )
        assertNull(json.decodeFromString<ApiLibraryItem>("""{"id":"li_1"}""").updatedAt)
    }
}
