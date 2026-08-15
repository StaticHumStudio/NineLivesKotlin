package com.ninelivesaudio.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ArtworkFetchEpochTest {

    @Test
    fun `first attempt for a book returns true`() {
        val epoch = ArtworkFetchEpoch()
        assertTrue(epoch.attempt("book-1"))
    }

    @Test
    fun `repeated attempts for the same book within an epoch return false`() {
        val epoch = ArtworkFetchEpoch()
        assertTrue(epoch.attempt("book-1"))
        assertFalse(epoch.attempt("book-1"))
        assertFalse(epoch.attempt("book-1"))
    }

    @Test
    fun `different books are independent`() {
        val epoch = ArtworkFetchEpoch()
        assertTrue(epoch.attempt("book-1"))
        assertTrue(epoch.attempt("book-2"))
        assertFalse(epoch.attempt("book-1"))
        assertFalse(epoch.attempt("book-2"))
    }

    @Test
    fun `clear re-enables every book for a new epoch`() {
        val epoch = ArtworkFetchEpoch()
        epoch.attempt("book-1")
        assertFalse(epoch.attempt("book-1"))

        epoch.clear()

        assertTrue(epoch.attempt("book-1"))
    }

    /**
     * The actual P1: three shelves share one BrowseArtworkCache, and once
     * the browse working set exceeds it, eviction plus the notify-triggered
     * re-query it causes must NOT re-fetch a book that was already attempted
     * this epoch — otherwise eviction and re-fetch feed each other forever.
     * This reproduces that exact shape purely, without coroutines, OkHttp,
     * or MediaBrowseTree: warm the cache for a first shelf, evict it by
     * browsing a much bigger second shelf that shares the cache, then
     * re-query (simulating Auto's re-query after the resulting notify) and
     * assert the evicted book is a cache miss but is NOT re-attempted.
     */
    @Test
    fun `an evicted book already attempted this epoch is not re-scheduled on re-query`() {
        val cache = BrowseArtworkCache(maxEntries = 4, maxTotalBytes = 8L * 1024 * 1024)
        val epoch = ArtworkFetchEpoch()
        val thumbnail = ByteArray(10)

        // Recently Played renders "book-1" with art, first browse pass.
        assertTrue(epoch.attempt("book-1"))
        cache.put("book-1", thumbnail)
        assertEquals(thumbnail, cache.get("book-1"))

        // Library — a bigger, distinct shelf sharing the same cache — pushes
        // "book-1" out of the small cache used here to force the eviction.
        listOf("book-2", "book-3", "book-4", "book-5").forEach {
            epoch.attempt(it)
            cache.put(it, thumbnail)
        }
        assertNull("book-1 should have been evicted by the bigger shelf", cache.get("book-1"))

        // A re-query for Recently Played (triggered by Library's own notify)
        // now sees a cache miss for "book-1". Without the epoch gate this
        // would schedule another fetch; the gate must refuse it.
        val cacheMiss = cache.get("book-1") == null
        assertTrue(cacheMiss)
        assertFalse(
            "an already-attempted book must not be re-scheduled after eviction",
            epoch.attempt("book-1"),
        )

        // Only once the data actually changes (sync/source-change) does the
        // book become eligible again.
        epoch.clear()
        assertTrue(epoch.attempt("book-1"))
    }

    // --- age floor -------------------------------------------------------
    //
    // The event-driven clears never fire in AppMode.LOCAL (SyncManager gates
    // syncCompleted on !isLocalMode, and a local rescan changes no field the
    // source-change collector distinguishes on). Without the floor, one
    // failed local cover read is permanent for the process lifetime.

    @Test
    fun `an epoch older than the age floor re-enables every book`() {
        var now = 1_000L
        val epoch = ArtworkFetchEpoch(nowMs = { now })

        assertTrue(epoch.attempt("book-1"))
        assertFalse(epoch.attempt("book-1"))

        now += ArtworkFetchEpoch.MAX_EPOCH_AGE_MS

        assertTrue(
            "a book must become eligible again once the epoch ages out, or LOCAL mode never recovers",
            epoch.attempt("book-1"),
        )
    }

    @Test
    fun `an epoch younger than the age floor still blocks re-attempts`() {
        var now = 1_000L
        val epoch = ArtworkFetchEpoch(nowMs = { now })

        assertTrue(epoch.attempt("book-1"))
        now += ArtworkFetchEpoch.MAX_EPOCH_AGE_MS - 1

        assertFalse(
            "expiring early would reopen the eviction/notify loop the epoch exists to bound",
            epoch.attempt("book-1"),
        )
    }

    @Test
    fun `a backwards wall clock expires the epoch instead of locking books out`() {
        var now = 10_000_000L
        val epoch = ArtworkFetchEpoch(nowMs = { now })

        assertTrue(epoch.attempt("book-1"))
        assertFalse(epoch.attempt("book-1"))

        // NTP correction or a manual time change moves the clock backwards.
        // Trusting the negative age would block every book until real time
        // caught back up, which for a large jump is effectively forever.
        now -= 60L * 60L * 1000L

        assertTrue(epoch.attempt("book-1"))
    }

    @Test
    fun `clear restarts the age window`() {
        var now = 1_000L
        val epoch = ArtworkFetchEpoch(nowMs = { now })

        assertTrue(epoch.attempt("book-1"))
        now += ArtworkFetchEpoch.MAX_EPOCH_AGE_MS - 1
        epoch.clear()

        // The clear reset the window, so this book is eligible once...
        assertTrue(epoch.attempt("book-1"))
        // ...and the old window's remaining 1ms must not age the NEW epoch out.
        now += 1
        assertFalse(epoch.attempt("book-1"))
    }

    // --- release ---------------------------------------------------------

    @Test
    fun `release restores an unspent attempt without granting an extra one`() {
        val epoch = ArtworkFetchEpoch()

        assertTrue(epoch.attempt("book-1"))
        epoch.release("book-1")

        assertTrue("a released attempt must be reclaimable", epoch.attempt("book-1"))
        assertFalse("release must not grant more than the one attempt back", epoch.attempt("book-1"))
    }

    /**
     * release() must remove exactly its own key. Implemented as a clear() it
     * would pass every single-book test in this file while re-arming the
     * whole library mid-epoch on any in-flight duplicate — which is the
     * eviction loop coming straight back.
     */
    @Test
    fun `release affects only the released book`() {
        val epoch = ArtworkFetchEpoch()

        assertTrue(epoch.attempt("book-1"))
        assertTrue(epoch.attempt("book-2"))

        epoch.release("book-1")

        assertTrue("the released book must be reclaimable", epoch.attempt("book-1"))
        assertFalse("an unrelated book must stay spent for this epoch", epoch.attempt("book-2"))
    }

    @Test
    fun `release of a book that never attempted is a no-op`() {
        val epoch = ArtworkFetchEpoch()

        epoch.release("book-1")

        assertTrue(epoch.attempt("book-1"))
        assertFalse(epoch.attempt("book-1"))
    }

    // --- concurrency -----------------------------------------------------

    /**
     * The single invariant the synchronized block exists for: browse callbacks
     * arrive on multiple threads, and two of them racing on the same book must
     * not both be told to fetch it. A non-atomic contains()-then-add() would
     * pass every other test in this file and fail only here.
     */
    @Test
    fun `concurrent attempts on one book yield exactly one winner`() {
        val threads = 16
        val epoch = ArtworkFetchEpoch()
        val winners = AtomicInteger(0)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)

        try {
            repeat(threads) {
                pool.execute {
                    start.await()
                    if (epoch.attempt("book-1")) winners.incrementAndGet()
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue("workers did not finish", done.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals("exactly one thread may win the attempt", 1, winners.get())
    }
}
