package com.ninelivesaudio.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BrowseArtworkCacheTest {

    @Test
    fun `stores and returns a put entry`() {
        val cache = BrowseArtworkCache(maxEntries = 24, maxTotalBytes = 8L * 1024 * 1024)
        val bytes = ByteArray(100)
        cache.put("book-1", bytes)
        assertEquals(bytes, cache.get("book-1"))
    }

    @Test
    fun `missing entry returns null`() {
        val cache = BrowseArtworkCache(maxEntries = 24, maxTotalBytes = 8L * 1024 * 1024)
        assertNull(cache.get("nope"))
    }

    @Test
    fun `evicts the least-recently-used entry once the entry cap is exceeded`() {
        val cache = BrowseArtworkCache(maxEntries = 3, maxTotalBytes = 8L * 1024 * 1024)
        cache.put("a", ByteArray(10))
        cache.put("b", ByteArray(10))
        cache.put("c", ByteArray(10))
        // Touch "a" so it's no longer the least-recently-used entry.
        cache.get("a")
        cache.put("d", ByteArray(10))

        assertEquals(3, cache.size())
        assertNotNull(cache.get("a"))
        assertNull(cache.get("b")) // evicted: least recently used
        assertNotNull(cache.get("c"))
        assertNotNull(cache.get("d"))
    }

    @Test
    fun `evicts oldest entries once the total-byte cap is exceeded`() {
        val cache = BrowseArtworkCache(maxEntries = 100, maxTotalBytes = 250)
        cache.put("a", ByteArray(100))
        cache.put("b", ByteArray(100))
        // Total would be 300 > 250, so "a" (oldest) is evicted first.
        cache.put("c", ByteArray(100))

        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))
        assertNotNull(cache.get("c"))
    }

    @Test
    fun `replacing an existing key does not double count its bytes`() {
        val cache = BrowseArtworkCache(maxEntries = 100, maxTotalBytes = 150)
        cache.put("a", ByteArray(100))
        // Replace with a smaller payload — should not trigger eviction of "a"
        // itself due to double-counting the old entry's bytes.
        cache.put("a", ByteArray(50))
        assertEquals(1, cache.size())
        assertEquals(50, cache.get("a")?.size)
    }

    /**
     * Reproduces the head-unit flicker from the #89 follow-up: Recently
     * Played renders 9 books with art, then Library — a bigger, distinct
     * shelf sharing the same cache — fetches many more thumbnails. A row
     * that already rendered with art must not go art-less on the next
     * browse rebuild just because a sibling shelf was also browsed.
     * Reproduces at the *old* 24-entry default (fails), fixed by widening
     * the entry cap so the byte cap is the real bound — see production
     * defaults below.
     */
    @Test
    fun `three shelves sharing the cache do not evict an already-rendered book`() {
        val cache = BrowseArtworkCache() // production defaults
        val thumbnail = ByteArray(50 * 1024) // ~50KB, realistic 256px JPEG

        val recentlyPlayedIds = (1..9).map { "book-$it" }
        recentlyPlayedIds.forEach { cache.put(it, thumbnail) }

        // Library shelf browses a much bigger, mostly-distinct collection.
        (10..60).forEach { cache.put("book-$it", thumbnail) }

        recentlyPlayedIds.forEach { id ->
            assertNotNull("expected $id to still be cached after Library was browsed", cache.get(id))
        }
    }

    @Test
    fun `production entry cap does not evict a realistic library well under the byte cap`() {
        val cache = BrowseArtworkCache() // production defaults: entries=300, bytes=8MB
        val thumbnail = ByteArray(50 * 1024) // ~50KB
        // 100 books * 50KB = ~5MB, under the 8MB byte cap and the 300 entry cap.
        val ids = (1..100).map { "book-$it" }
        ids.forEach { cache.put(it, thumbnail) }
        ids.forEach { id -> assertNotNull(cache.get(id)) }
    }
}
