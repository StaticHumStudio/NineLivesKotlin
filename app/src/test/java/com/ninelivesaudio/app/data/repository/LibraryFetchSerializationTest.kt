package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.domain.model.Library
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GitHub codex review of PR #30, finding B: LibraryRepository.syncFromServer()
 * is called independently by SyncManager.syncNow() (the account-wide
 * background/manual sync) and LibraryViewModel.loadLibraries() (a Library
 * screen visit), with no shared coordination. Overlapping calls could apply
 * their complete responses out of order: an older, slower fetch captures
 * [A, B]; a newer, faster fetch captures [A] and correctly prunes B; the
 * older fetch then finishes and upserts B back, resurrecting what the
 * newer fetch just removed.
 *
 * runSerializedLibrarySync() is the actual body syncFromServer() runs
 * (fetch, then reconcileServerLibraries() if warranted), serialized behind
 * a shared Mutex so no caller's network fetch can even START until every
 * earlier call's fetch-and-reconcile has fully finished — a later fetch is
 * therefore always based on a same-or-newer look at the server, which is
 * what actually closes the race (not just avoiding concurrent DB writes).
 */
class LibraryFetchSerializationTest {

    private fun lib(id: String) = Library(id = id, name = id)

    private fun fakeSync(
        mutex: Mutex,
        cache: MutableList<String>,
        fetchLibraries: suspend () -> RemoteResult<List<Library>>,
    ): suspend () -> RemoteResult<List<Library>> = {
        runSerializedLibrarySync(
            mutex = mutex,
            fetchLibraries = fetchLibraries,
            cachedServerLibraryIds = { cache.toList() },
            upsertAll = { libraries -> libraries.forEach { if (it.id !in cache) cache.add(it.id) } },
            deleteMissing = { keptIds -> cache.retainAll { it in keptIds } },
            deleteAllServerLibraries = { cache.clear() },
            pruneLibraryBooks = { false },
        )
    }

    @Test
    fun `a stale response arriving after a newer completed sync does not resurrect a pruned library`() = runBlocking {
        val mutex = Mutex()
        val cache = mutableListOf("lib-a", "lib-b") // as if from an earlier, already-applied sync

        val oldFetchStarted = CompletableDeferred<Unit>()
        val releaseOldFetch = CompletableDeferred<Unit>()

        // The OLD (slower) call's fetch holds the mutex for its whole
        // duration -- exactly like syncFromServer() will -- so the NEW call
        // below cannot even start its own network request until this one
        // is completely done.
        val oldCall = fakeSync(mutex, cache) {
            oldFetchStarted.complete(Unit)
            releaseOldFetch.await()
            RemoteResult.Ok(listOf(lib("lib-a"), lib("lib-b"))) // stale: still reports lib-b
        }
        val oldCallJob = launch { oldCall() }
        oldFetchStarted.await()

        // While the old call is still blocked mid-fetch (holding the
        // mutex), the newer, more recent call is triggered. It must queue
        // behind the old one rather than racing it.
        val newCall = fakeSync(mutex, cache) {
            RemoteResult.Ok(listOf(lib("lib-a"))) // fresh: lib-b is really gone now
        }
        val newCallJob = launch { newCall() }

        releaseOldFetch.complete(Unit)
        oldCallJob.join()
        newCallJob.join()

        assertEquals(listOf("lib-a"), cache)
    }

    @Test
    fun `a normal, non-overlapping sync reconciles exactly as before`() = runBlocking {
        val mutex = Mutex()
        val cache = mutableListOf("lib-a", "lib-b")

        val result = runSerializedLibrarySync(
            mutex = mutex,
            fetchLibraries = { RemoteResult.Ok(listOf(lib("lib-a"))) },
            cachedServerLibraryIds = { cache.toList() },
            upsertAll = { libraries -> libraries.forEach { if (it.id !in cache) cache.add(it.id) } },
            deleteMissing = { keptIds -> cache.retainAll { it in keptIds } },
            deleteAllServerLibraries = { cache.clear() },
            pruneLibraryBooks = { false },
        )

        assertEquals(RemoteResult.Ok(listOf(lib("lib-a"))), result)
        assertEquals(listOf("lib-a"), cache)
    }

    @Test
    fun `a normal Partial sync retains the cache untouched, same as today`() = runBlocking {
        val mutex = Mutex()
        val cache = mutableListOf("lib-a", "lib-b")

        val result = runSerializedLibrarySync(
            mutex = mutex,
            fetchLibraries = { RemoteResult.Partial(listOf(lib("lib-a")), "page 1: timeout") },
            cachedServerLibraryIds = { cache.toList() },
            upsertAll = { libraries -> libraries.forEach { if (it.id !in cache) cache.add(it.id) } },
            deleteMissing = { org.junit.Assert.fail("a partial fetch never prunes") },
            deleteAllServerLibraries = { org.junit.Assert.fail("a partial fetch never prunes") },
            pruneLibraryBooks = { org.junit.Assert.fail("a partial fetch never prunes"); false },
        )

        assertEquals(RemoteResult.Partial(listOf(lib("lib-a")), "page 1: timeout"), result)
        assertEquals(listOf("lib-a", "lib-b"), cache)
    }

    @Test
    fun `a Failed sync retains the cache and skips reconciliation entirely`() = runBlocking {
        val mutex = Mutex()
        val cache = mutableListOf("lib-a", "lib-b")

        val result = runSerializedLibrarySync(
            mutex = mutex,
            fetchLibraries = { RemoteResult.Failed("HTTP 500") },
            cachedServerLibraryIds = { org.junit.Assert.fail("a failed fetch never reconciles"); emptyList() },
            upsertAll = { org.junit.Assert.fail("a failed fetch never reconciles") },
            deleteMissing = { org.junit.Assert.fail("a failed fetch never reconciles") },
            deleteAllServerLibraries = { org.junit.Assert.fail("a failed fetch never reconciles") },
            pruneLibraryBooks = { org.junit.Assert.fail("a failed fetch never reconciles"); false },
        )

        assertEquals(RemoteResult.Failed("HTTP 500"), result)
        assertEquals(listOf("lib-a", "lib-b"), cache)
    }
}
