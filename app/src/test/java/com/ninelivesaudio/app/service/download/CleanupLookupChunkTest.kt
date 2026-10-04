package com.ninelivesaudio.app.service.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cancel cleanup looks up the book behind every download row. Android 11's
 * SQLite refuses more than 999 bound values, so the lookup has to go in chunks
 * or cleanup silently keeps the files once a user has 1,000 download rows.
 */
class CleanupLookupChunkTest {

    @Test
    fun `1,200 ids never bind more than 500 at once and all come back`() = runBlocking {
        val ids = (1..1_200).map { "book-$it" }
        val batchSizes = mutableListOf<Int>()

        val found = lookUpInChunks(ids) { chunk ->
            batchSizes += chunk.size
            chunk.map { "row-$it" }
        }

        assertTrue("largest batch was ${batchSizes.max()}", batchSizes.all { it <= 500 })
        assertTrue(batchSizes.max() < 999)
        assertEquals(ids.map { "row-$it" }, found)
    }

    @Test
    fun `no ids means no lookup at all`() = runBlocking {
        var calls = 0

        val found = lookUpInChunks(emptyList()) { chunk ->
            calls++
            chunk
        }

        assertEquals(0, calls)
        assertEquals(emptyList<String>(), found)
    }
}
