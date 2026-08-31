package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.domain.model.Library
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * GitHub codex review of PR #30, finding A: the reconciliation gap fixed for
 * books (ServerLibraryReconciliationTest) exists one level up for
 * libraries. LibraryRepository.syncFromServer() only upserted the libraries
 * a complete /libraries fetch returned -- a library omitted from that
 * response (deleted or renamed away server-side) was never removed from the
 * cache, so getAudiobookshelf() kept serving it after an offline load or
 * process restart, and it could remain selected by Home or MediaBrowseTree.
 *
 * reconcileServerLibraries() mirrors reconcileServerLibrary()'s semantics:
 * a complete (Ok) fetch is authoritative and prunes what it no longer
 * reports (including everything, when the fetch is confirmed empty); a
 * Partial fetch only upserts what it got and never prunes. Pruning a
 * library also prunes its cached books via the same
 * downloaded-book-exempt mechanism as reconcileServerLibrary (pruneLibraryBooks).
 * Local libraries are never touched by any of this -- they aren't part of
 * cachedServerLibraryIds and are never passed to upsertAll/deleteMissing.
 */
class ServerLibraryListReconciliationTest {

    private fun lib(id: String) = Library(id = id, name = id)

    @Test
    fun `a complete fetch that omits a previously cached library prunes it and its books`() = runBlocking {
        // The literal scenario from finding A: cached libraries A and B,
        // complete Ok returns only A.
        val upserted = mutableListOf<Library>()
        val prunedLibraries = mutableListOf<String>()
        val prunedBooksFor = mutableListOf<String>()

        reconcileServerLibraries(
            isComplete = true,
            fetched = listOf(lib("lib-a")),
            cachedServerLibraryIds = { listOf("lib-a", "lib-b") },
            upsertAll = { upserted.addAll(it) },
            deleteMissing = { keptIds -> prunedLibraries.add("deleteMissing($keptIds)") },
            deleteAllServerLibraries = { fail("a non-empty fetch prunes what's missing, not everything") },
            pruneLibraryBooks = { libraryId -> prunedBooksFor.add(libraryId) },
        )

        assertEquals(listOf(lib("lib-a")), upserted)
        assertEquals(listOf("deleteMissing([lib-a])"), prunedLibraries)
        assertEquals(listOf("lib-b"), prunedBooksFor)
    }

    @Test
    fun `a complete fetch reporting zero libraries prunes every cached server library and its books`() = runBlocking {
        val prunedBooksFor = mutableListOf<String>()
        var deletedAll = false

        reconcileServerLibraries(
            isComplete = true,
            fetched = emptyList(),
            cachedServerLibraryIds = { listOf("lib-a", "lib-b") },
            upsertAll = { fail("nothing to upsert for an empty fetch") },
            deleteMissing = { fail("an empty complete fetch deletes everything, not a scoped subset") },
            deleteAllServerLibraries = { deletedAll = true },
            pruneLibraryBooks = { libraryId -> prunedBooksFor.add(libraryId) },
        )

        assertEquals(true, deletedAll)
        assertEquals(listOf("lib-a", "lib-b"), prunedBooksFor)
    }

    @Test
    fun `a complete fetch that reports everything already cached prunes nothing`() = runBlocking {
        val upserted = mutableListOf<Library>()
        var prunedAnyBooks = false

        reconcileServerLibraries(
            isComplete = true,
            fetched = listOf(lib("lib-a"), lib("lib-b")),
            cachedServerLibraryIds = { listOf("lib-a", "lib-b") },
            upsertAll = { upserted.addAll(it) },
            deleteMissing = { keptIds -> assertEquals(listOf("lib-a", "lib-b"), keptIds) },
            deleteAllServerLibraries = { fail("not empty") },
            pruneLibraryBooks = { prunedAnyBooks = true },
        )

        assertEquals(listOf(lib("lib-a"), lib("lib-b")), upserted)
        assertEquals(false, prunedAnyBooks)
    }

    @Test
    fun `a partial fetch retains the cache untouched, same as the book-level fix`() = runBlocking {
        reconcileServerLibraries(
            isComplete = false,
            fetched = emptyList(),
            cachedServerLibraryIds = { fail("a partial fetch never needs to know what's cached -- it never prunes"); emptyList() },
            upsertAll = { fail("a partial fetch with nothing new must not touch the DAO") },
            deleteMissing = { fail("a partial fetch never prunes") },
            deleteAllServerLibraries = { fail("a partial fetch never prunes") },
            pruneLibraryBooks = { fail("a partial fetch never prunes") },
        )
    }

    @Test
    fun `a partial fetch with some libraries upserts them but never prunes what it couldn't reach`() = runBlocking {
        val upserted = mutableListOf<Library>()

        reconcileServerLibraries(
            isComplete = false,
            fetched = listOf(lib("lib-a")),
            cachedServerLibraryIds = { fail("a partial fetch never prunes"); emptyList() },
            upsertAll = { upserted.addAll(it) },
            deleteMissing = { fail("a partial fetch never prunes") },
            deleteAllServerLibraries = { fail("a partial fetch never prunes") },
            pruneLibraryBooks = { fail("a partial fetch never prunes") },
        )

        assertEquals(listOf(lib("lib-a")), upserted)
    }
}
