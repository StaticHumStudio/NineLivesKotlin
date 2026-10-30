package com.ninelivesaudio.app.service

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ─── Now playing art (#1) ────────────────────────────────────────────

    @Test
    fun `switching A to B and back to A reuses A's art`() {
        val cache = NowPlayingArtworkCache()
        val a = byteArrayOf(1)
        cache.put("A", "https://abs.test/api/items/A/cover?ts=1", a)
        cache.put("B", "https://abs.test/api/items/B/cover?ts=1", byteArrayOf(2))
        assertArrayEquals(a, cache.get("A", "https://abs.test/api/items/A/cover?ts=1"))
    }

    @Test
    fun `an edited cover misses the now playing cache`() {
        val cache = NowPlayingArtworkCache()
        cache.put("A", "https://abs.test/api/items/A/cover?ts=1", byteArrayOf(1))
        assertNull(cache.get("A", "https://abs.test/api/items/A/cover?ts=2"))
    }

    @Test
    fun `the now playing cache stays small`() {
        val cache = NowPlayingArtworkCache(maxEntries = 4, maxTotalBytes = 1_000)
        (1..5).forEach { cache.put("b$it", null, ByteArray(100)) }
        assertNull(cache.get("b1", null))
        assertNotNull(cache.get("b5", null))
        cache.put("big", null, ByteArray(900))
        assertNull(cache.get("b2", null))
        cache.clear()
        assertNull(cache.get("big", null))
    }

    @Test
    fun `a signal sent before anyone listens still arrives`() = runBlocking {
        val signal = artworkSignal()
        assertTrue(signal.tryEmit(Unit))
        withTimeout(1_000) { signal.first() }
        Unit
    }

    @Test
    fun `a burst of signals never fails to send`() {
        val signal = artworkSignal()
        repeat(50) { assertTrue(signal.tryEmit(Unit)) }
    }

    @Test
    fun `hiding the phone UI keeps the art`() {
        // TRIM_MEMORY_UI_HIDDEN, RUNNING_MODERATE, RUNNING_LOW: the screen
        // turning off mid drive must not make every row refetch over cellular.
        listOf(20, 5, 10).forEach { assertFalse("level $it", shouldDropArtworkOnTrim(it)) }
    }

    @Test
    fun `real memory pressure drops the art`() {
        // TRIM_MEMORY_RUNNING_CRITICAL, BACKGROUND, MODERATE, COMPLETE.
        listOf(15, 40, 60, 80).forEach { assertTrue("level $it", shouldDropArtworkOnTrim(it)) }
    }

    @Test
    fun `now playing never sends a local uri to the network`() {
        listOf("content://x/cover", "file:///d/cover.jpg", "", null).forEach {
            assertNull(it.toString(), nowPlayingRemoteArtworkUrl(it))
        }
    }

    @Test
    fun `now playing asks the server for a 512 pixel cover`() {
        assertEquals(
            "https://abs.test/api/items/li_1/cover?ts=5&width=512",
            nowPlayingRemoteArtworkUrl("https://abs.test/api/items/li_1/cover?ts=5"),
        )
    }
}
