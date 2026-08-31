package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.domain.model.AudioBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * GitHub codex review of PR #30: RemoteResult.Ok(emptyList()) for a
 * previously populated server library skipped the reconcile block that a
 * non-empty Ok fetch runs (gated on `remote.isNotEmpty()`), so a genuinely
 * emptied-out library kept serving its stale cached rows on the shelf while
 * the new sync record reported a successful zero-book fetch.
 *
 * reconcileServerLibrary() is the fix: a complete (Ok) fetch is authoritative
 * for the library, including a confirmed-empty one, so the cache is pruned
 * to match it. A Partial fetch is not authoritative — it saves what it got
 * (some of the shelf beats none of it) but never removes what it couldn't
 * reach, since a partial failure is not proof those books are gone. That
 * retention is unchanged from today.
 *
 * These tests exercise reconcileServerLibrary() against a hand-rolled
 * in-memory cache whose callbacks simulate the cached-id query and chunked
 * deletes used by AudioBookDao, so they pin both the branching decision AND
 * that a populated cache actually ends up reconciled — not just that the
 * right DAO method name gets called.
 */
class ServerLibraryReconciliationTest {

    private data class CachedRow(val id: String, val libraryId: String, val isDownloaded: Boolean)

    /** Simulates: SELECT Id ... WHERE LibraryId = :libraryId AND IsLocal = 0 AND IsDownloaded = 0 */
    private fun fakeCachedNonDownloadedIds(cache: List<CachedRow>, libraryId: String): List<String> =
        cache.filter { it.libraryId == libraryId && !it.isDownloaded }.map { it.id }

    /** Simulates: DELETE ... WHERE LibraryId = :libraryId AND IsLocal = 0 AND IsDownloaded = 0 AND Id IN (:ids) */
    private fun fakeDeleteByIdsInLibrary(
        cache: MutableList<CachedRow>,
        libraryId: String,
        ids: List<String>,
    ) {
        cache.removeAll { it.libraryId == libraryId && !it.isDownloaded && it.id in ids }
    }

    /** Simulates: DELETE ... WHERE LibraryId = :libraryId AND IsLocal = 0 AND IsDownloaded = 0 */
    private fun fakeDeleteAll(cache: MutableList<CachedRow>, libraryId: String) {
        cache.removeAll { it.libraryId == libraryId && !it.isDownloaded }
    }

    private fun book(id: String) = AudioBook(id = id, title = id)

    @Test
    fun `a complete empty fetch reconciles away non-downloaded cached rows, preserving downloads and other libraries`() = runBlocking {
        // The exact bug: a library that HAD books, populated in the cache,
        // now genuinely has none. Cover: it must not keep rendering.
        val cache = mutableListOf(
            CachedRow("book-1", "lib-a", isDownloaded = false),
            CachedRow("book-2", "lib-a", isDownloaded = true),
            CachedRow("book-3", "lib-b", isDownloaded = false), // a different library, must survive untouched
        )
        val upserted = mutableListOf<AudioBook>()

        reconcileServerLibrary(
            isComplete = true,
            merged = emptyList(),
            libraryId = "lib-a",
            upsertAll = { upserted.addAll(it) },
            cachedNonDownloadedIds = { error("an empty complete fetch deletes everything, not a scoped subset") },
            deleteByIds = { _, _ -> fail("an empty complete fetch deletes everything, not a scoped subset") },
            deleteAllServerBooks = { id -> fakeDeleteAll(cache, id) },
        )

        assertEquals(emptyList<AudioBook>(), upserted)
        assertEquals(
            listOf(
                CachedRow("book-2", "lib-a", isDownloaded = true),
                CachedRow("book-3", "lib-b", isDownloaded = false),
            ),
            cache,
        )
    }

    @Test
    fun `a partial empty fetch retains the cache untouched, same as today`() = runBlocking {
        val cache = mutableListOf(
            CachedRow("book-1", "lib-a", isDownloaded = false),
            CachedRow("book-2", "lib-a", isDownloaded = true),
        )
        val originalCache = cache.toList()

        reconcileServerLibrary(
            isComplete = false,
            merged = emptyList(),
            libraryId = "lib-a",
            upsertAll = { fail("a partial fetch with nothing new must not touch the DAO") },
            cachedNonDownloadedIds = { error("a partial fetch never prunes -- it isn't proof anything is gone") },
            deleteByIds = { _, _ -> fail("a partial fetch never prunes -- it isn't proof anything is gone") },
            deleteAllServerBooks = { fail("a partial fetch never prunes -- it isn't proof anything is gone") },
        )

        assertEquals(originalCache, cache)
    }

    @Test
    fun `a partial non-empty fetch upserts what it got but still retains what it couldn't reach`() = runBlocking {
        val cache = mutableListOf(
            CachedRow("book-1", "lib-a", isDownloaded = false), // NOT in this fetch -- a partial page miss, not proof it's gone
        )
        val upserted = mutableListOf<AudioBook>()

        reconcileServerLibrary(
            isComplete = false,
            merged = listOf(book("book-2")),
            libraryId = "lib-a",
            upsertAll = { upserted.addAll(it) },
            cachedNonDownloadedIds = { error("a partial fetch never prunes") },
            deleteByIds = { _, _ -> fail("a partial fetch never prunes") },
            deleteAllServerBooks = { fail("a partial fetch never prunes") },
        )

        assertEquals(listOf(book("book-2")), upserted)
        assertEquals(listOf(CachedRow("book-1", "lib-a", isDownloaded = false)), cache)
    }

    @Test
    fun `a complete non-empty fetch upserts and prunes only what it no longer reports`() = runBlocking {
        val cache = mutableListOf(
            CachedRow("book-1", "lib-a", isDownloaded = false), // dropped from the server -- stale, must go
            CachedRow("book-2", "lib-a", isDownloaded = true), // dropped from the server too, but downloaded -- must survive
            CachedRow("book-3", "lib-b", isDownloaded = false), // a different library -- must survive
        )
        val upserted = mutableListOf<AudioBook>()

        reconcileServerLibrary(
            isComplete = true,
            merged = listOf(book("book-9")), // the server's current, complete set for lib-a
            libraryId = "lib-a",
            upsertAll = { upserted.addAll(it) },
            cachedNonDownloadedIds = { libraryId -> fakeCachedNonDownloadedIds(cache, libraryId) },
            deleteByIds = { libraryId, ids -> fakeDeleteByIdsInLibrary(cache, libraryId, ids) },
            deleteAllServerBooks = { fail("a non-empty fetch prunes what's missing, not everything") },
        )

        assertEquals(listOf(book("book-9")), upserted)
        assertEquals(
            listOf(
                CachedRow("book-2", "lib-a", isDownloaded = true),
                CachedRow("book-3", "lib-b", isDownloaded = false),
            ),
            cache,
        )
    }

    @Test
    fun `a complete large fetch deletes stale rows in bind safe chunks`() = runBlocking {
        val keptIds = (1..1_001).map { "kept-$it" }
        val staleIds = (1..501).map { "stale-$it" }
        val cache = (
            keptIds.map { CachedRow(it, "lib-a", isDownloaded = false) } +
                staleIds.map { CachedRow(it, "lib-a", isDownloaded = false) } +
                CachedRow("downloaded", "lib-a", isDownloaded = true) +
                CachedRow("other-library", "lib-b", isDownloaded = false)
            ).toMutableList()
        val deleteBatches = mutableListOf<List<String>>()

        reconcileServerLibrary(
            isComplete = true,
            merged = keptIds.map(::book),
            libraryId = "lib-a",
            upsertAll = {},
            cachedNonDownloadedIds = { libraryId -> fakeCachedNonDownloadedIds(cache, libraryId) },
            deleteByIds = { libraryId, ids ->
                deleteBatches += ids
                fakeDeleteByIdsInLibrary(cache, libraryId, ids)
            },
            deleteAllServerBooks = { fail("a non-empty fetch prunes only rows it no longer reports") },
        )

        assertEquals(2, deleteBatches.size)
        assertTrue(deleteBatches.all { it.size <= 499 })
        assertEquals(
            keptIds.toSet() + setOf("downloaded", "other-library"),
            cache.map { it.id }.toSet(),
        )
    }

    @Test
    fun `a large server merge reads cached rows in bind safe chunks`() = runBlocking {
        val remote = (1..1_001).map { book("remote-$it") }
        val lookupBatches = mutableListOf<List<String>>()

        val merged = mergeSyncedBooks(remote) { ids ->
            lookupBatches += ids
            emptyList()
        }

        assertEquals(remote, merged)
        assertEquals(3, lookupBatches.size)
        assertTrue(lookupBatches.all { it.size <= 500 })
    }

    @Test
    fun `a row rehomed after stale lookup survives the library scoped delete`() = runBlocking {
        val cache = mutableListOf(CachedRow("rehomed", "lib-a", isDownloaded = false))

        reconcileServerLibrary(
            isComplete = true,
            merged = listOf(book("current")),
            libraryId = "lib-a",
            upsertAll = {},
            cachedNonDownloadedIds = { libraryId -> fakeCachedNonDownloadedIds(cache, libraryId) },
            deleteByIds = { libraryId, ids ->
                cache[0] = cache.single().copy(libraryId = "lib-b")
                fakeDeleteByIdsInLibrary(cache, libraryId, ids)
            },
            deleteAllServerBooks = { fail("a non-empty fetch uses the scoped delete") },
        )

        assertEquals(listOf(CachedRow("rehomed", "lib-b", isDownloaded = false)), cache)
    }
}
