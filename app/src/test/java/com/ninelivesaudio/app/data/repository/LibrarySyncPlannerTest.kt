package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.remote.LibraryHead
import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.LibrarySyncWatermark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A big library on a slow server used to download in full on every launch.
 * The change check compares one tiny head request against the last good
 * sync and does the least work that keeps the shelf truthful.
 */
class LibrarySyncPlannerTest {

    private val key = SyncAccountKey("https://abs.example.net", "jeff")
    private val day = 24 * 60 * 60_000L

    private fun watermark(
        newestAddedAt: Long? = 5_000L,
        newestItemId: String? = "li_newest",
        itemCount: Int = 100,
        lastFullSyncAtMs: Long = 0L,
    ) = LibrarySyncWatermark(
        serverUrl = key.serverUrl,
        username = key.username,
        libraryId = "lib",
        newestAddedAt = newestAddedAt,
        newestItemId = newestItemId,
        itemCount = itemCount,
        lastFullSyncAtMs = lastFullSyncAtMs,
    )

    private fun head(total: Int = 100, id: String? = "li_newest", addedAt: Long? = 5_000L) =
        LibraryHead(total = total, newestItemId = id, newestAddedAt = addedAt)

    // ─── The change check ─────────────────────────────────────────────────

    @Test
    fun `same newest book and same count is no change`() {
        assertEquals(LibraryChange.NoChange, decideLibraryChange(watermark(), head(), localServerRowCount = 100))
    }

    @Test
    fun `a newer newest book is an incremental sync`() {
        assertEquals(
            LibraryChange.Incremental,
            decideLibraryChange(watermark(), head(total = 103, id = "li_new", addedAt = 9_000L), 100),
        )
    }

    @Test
    fun `no watermark means the first sync is full`() {
        assertTrue(decideLibraryChange(null, head(), 100) is LibraryChange.Full)
    }

    @Test
    fun `fewer books with the same newest one means a removal and a full sync`() {
        assertTrue(decideLibraryChange(watermark(), head(total = 99), 100) is LibraryChange.Full)
    }

    @Test
    fun `the newest book gone means a full sync`() {
        assertTrue(decideLibraryChange(watermark(), head(total = 99, id = "li_older", addedAt = 4_000L), 100) is LibraryChange.Full)
    }

    @Test
    fun `a cache missing rows the watermark promised means a full sync`() {
        assertTrue(decideLibraryChange(watermark(itemCount = 100), head(), localServerRowCount = 0) is LibraryChange.Full)
    }

    @Test
    fun `an emptied library is a full sync so the prune can run`() {
        assertTrue(decideLibraryChange(watermark(), head(total = 0, id = null, addedAt = null), 100) is LibraryChange.Full)
        assertEquals(
            LibraryChange.NoChange,
            decideLibraryChange(watermark(newestAddedAt = null, newestItemId = null, itemCount = 0), head(0, null, null), 0),
        )
    }

    @Test
    fun `a head without an added date is not trusted`() {
        assertTrue(decideLibraryChange(watermark(), head(addedAt = null), 100) is LibraryChange.Full)
    }

    @Test
    fun `a library that was empty and now has books fetches them incrementally`() {
        assertEquals(
            LibraryChange.Incremental,
            decideLibraryChange(watermark(newestAddedAt = null, newestItemId = null, itemCount = 0), head(total = 2), 0),
        )
    }

    // ─── Plans, staleness, and metered deferral ───────────────────────────

    @Test
    fun `a full sync found by a check waits for unmetered when a shelf is cached`() {
        val full = LibraryChange.Full("removed")
        assertEquals(LibrarySyncPlan.DEFER_FULL, planLibrarySync(full, isStale = false, isMetered = true, hasCachedRows = true))
        assertEquals(LibrarySyncPlan.FULL, planLibrarySync(full, isStale = false, isMetered = false, hasCachedRows = true))
        assertEquals(LibrarySyncPlan.FULL, planLibrarySync(full, isStale = false, isMetered = true, hasCachedRows = false))
    }

    @Test
    fun `an explicit refresh never waits for unmetered`() {
        assertTrue(shouldDeferFullSync(isMetered = true, explicit = false, hasCachedRows = true))
        assertFalse(shouldDeferFullSync(isMetered = true, explicit = true, hasCachedRows = true))
        assertFalse(shouldDeferFullSync(isMetered = false, explicit = false, hasCachedRows = true))
    }

    @Test
    fun `incremental updates run on metered networks`() {
        assertEquals(
            LibrarySyncPlan.INCREMENTAL,
            planLibrarySync(LibraryChange.Incremental, isStale = false, isMetered = true, hasCachedRows = true),
        )
    }

    @Test
    fun `a day-old full sync refreshes everything on unmetered and steps down on metered`() {
        assertEquals(LibrarySyncPlan.FULL, planLibrarySync(LibraryChange.NoChange, isStale = true, isMetered = false, hasCachedRows = true))
        assertEquals(LibrarySyncPlan.SKIP, planLibrarySync(LibraryChange.NoChange, isStale = true, isMetered = true, hasCachedRows = true))
        assertEquals(LibrarySyncPlan.INCREMENTAL, planLibrarySync(LibraryChange.Incremental, isStale = true, isMetered = true, hasCachedRows = true))
        assertEquals(LibrarySyncPlan.SKIP, planLibrarySync(LibraryChange.NoChange, isStale = false, isMetered = false, hasCachedRows = true))
    }

    @Test
    fun `staleness is a day, and a clock that went backward counts as stale`() {
        val w = watermark(lastFullSyncAtMs = 10 * day)
        assertFalse(isWatermarkStale(w, nowMs = 10 * day + day - 1))
        assertTrue(isWatermarkStale(w, nowMs = 11 * day))
        assertTrue(isWatermarkStale(w, nowMs = 9 * day))
    }

    // ─── The added-since fetch and its deletion check ─────────────────────

    @Test
    fun `the cutoff reaches one minute before the stored newest book`() {
        assertEquals(5_000L - 60_000L, incrementalCutoff(watermark(newestAddedAt = 5_000L)))
        assertEquals(Long.MIN_VALUE, incrementalCutoff(watermark(newestAddedAt = null)))
    }

    @Test
    fun `counts that disagree after the added books mean something was removed`() {
        assertTrue(incrementalCountsAgree(previousCount = 100, newBookCount = 3, serverTotal = 103))
        assertFalse(incrementalCountsAgree(previousCount = 100, newBookCount = 3, serverTotal = 102))
        assertFalse(incrementalCountsAgree(previousCount = 100, newBookCount = 1, serverTotal = 100))
    }

    @Test
    fun `a retry after one removed and one added still disagrees when the added book is already cached`() {
        // Server deleted one book and added li_new, keeping 100. The first
        // check saved li_new and deferred. The retry finds it cached.
        val fetched = listOf(AudioBook(id = "li_new", addedAt = 9_000L), AudioBook(id = "li_newest", addedAt = 5_000L))
        val newCount = newBooksSinceWatermark(watermark(), fetched, alreadyCached = setOf("li_new", "li_newest"))
        assertEquals(1, newCount)
        assertFalse(incrementalCountsAgree(previousCount = 100, newBookCount = newCount, serverTotal = 100))
    }

    @Test
    fun `a book inside the overlap window counts as new only when the cache lacks it`() {
        val overlap = listOf(AudioBook(id = "li_late", addedAt = 4_990L))
        assertEquals(0, newBooksSinceWatermark(watermark(), overlap, alreadyCached = setOf("li_late")))
        assertEquals(1, newBooksSinceWatermark(watermark(), overlap, alreadyCached = emptySet()))
    }

    @Test
    fun `an incremental sync moves the watermark to the server's newest book`() {
        val fetched = listOf(AudioBook(id = "li_new", addedAt = 9_000L), AudioBook(id = "li_newest", addedAt = 5_000L))
        val next = watermarkAfterIncremental(watermark(lastFullSyncAtMs = 7L), fetched, serverTotal = 101, nowMs = 50L)
        assertEquals(9_000L, next.newestAddedAt)
        assertEquals("li_new", next.newestItemId)
        assertEquals(101, next.itemCount)
        assertEquals(7L, next.lastFullSyncAtMs)
    }

    @Test
    fun `a full sync tally finds the newest book and refuses a watermark without added dates`() {
        val tally = FullSyncTally()
        tally.add(listOf(AudioBook(id = "a", addedAt = 3L), AudioBook(id = "b", addedAt = 9L)))
        tally.add(listOf(AudioBook(id = "b", addedAt = 9L), AudioBook(id = "c", addedAt = 5L)))
        val w = watermarkAfterFullSync(key, "lib", tally, RemoteResult.Ok(3), nowMs = 42L)!!
        assertEquals(3, w.itemCount)
        assertEquals("b", w.newestItemId)
        assertEquals(9L, w.newestAddedAt)
        assertEquals(42L, w.lastFullSyncAtMs)

        val missing = FullSyncTally().apply { add(listOf(AudioBook(id = "x", addedAt = null))) }
        assertNull(watermarkAfterFullSync(key, "lib", missing, RemoteResult.Ok(1), nowMs = 42L))
    }

    @Test
    fun `a full download that stops short forgets the watermark so the next check downloads again`() {
        val tally = FullSyncTally().apply { add(listOf(AudioBook(id = "a", addedAt = 3L))) }
        assertNull(watermarkAfterFullSync(key, "lib", tally, RemoteResult.Partial(1, "page 2: HTTP 500"), nowMs = 42L))
        assertNull(watermarkAfterFullSync(key, "lib", tally, RemoteResult.Failed("timeout"), nowMs = 42L))
        // With no watermark the next check cannot answer "nothing new".
        assertTrue(decideLibraryChange(null, head(), localServerRowCount = 100) is LibraryChange.Full)
    }

    @Test
    fun `watermarks are kept per server, account, and library`() {
        val mine = watermark()
        val other = mine.copy(username = "guest", itemCount = 5)
        val list = emptyList<LibrarySyncWatermark>().withWatermark(mine).withWatermark(other)
        assertEquals(100, list.watermarkFor(key, "lib")!!.itemCount)
        assertEquals(5, list.watermarkFor(key.copy(username = "guest"), "lib")!!.itemCount)
        assertNull(list.watermarkFor(key.copy(serverUrl = "https://other"), "lib"))
        assertEquals(1, list.withoutWatermark(key, "lib").size)
        val capped = (1..10).fold(emptyList<LibrarySyncWatermark>()) { acc, i ->
            acc.withWatermark(mine.copy(libraryId = "lib$i", updatedAtMs = i.toLong()), cap = 3)
        }
        assertEquals(listOf("lib10", "lib9", "lib8"), capped.map { it.libraryId })
    }

    // ─── Failure backoff ──────────────────────────────────────────────────

    @Test
    fun `background full downloads back off after failures and cap at four hours`() {
        val minute = 60_000L
        assertEquals(15 * minute, fullSyncBackoffMs(1))
        assertEquals(30 * minute, fullSyncBackoffMs(2))
        assertEquals(4 * 60 * minute, fullSyncBackoffMs(20))
        val failures = FullSyncFailures(count = 1, lastFailureAtMs = 1_000L)
        assertTrue(isFullSyncBackedOff(failures, nowMs = 1_000L + 14 * minute))
        assertFalse(isFullSyncBackedOff(failures, nowMs = 1_000L + 15 * minute))
        assertFalse(isFullSyncBackedOff(null, nowMs = 0L))
        assertFalse(isFullSyncBackedOff(failures, nowMs = 0L))
    }
}
