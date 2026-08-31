package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.domain.model.AudioBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryItemFetchSerializationTest {

    private fun book(id: String) = AudioBook(id = id, title = id)

    private fun fakeSync(
        mutex: Mutex,
        cache: MutableList<String>,
        fetchItems: suspend () -> RemoteResult<List<AudioBook>>,
    ): suspend () -> RemoteResult<List<AudioBook>> = {
        runSerializedLibraryItemSync(
            mutex = mutex,
            libraryId = "lib-a",
            fetchItems = fetchItems,
            mergeItems = { it },
            upsertAll = { books -> books.forEach { if (it.id !in cache) cache.add(it.id) } },
            cachedNonDownloadedIds = { cache.toList() },
            deleteByIds = { _, ids -> cache.retainAll { it !in ids } },
            deleteAllServerBooks = { cache.clear() },
        )
    }

    @Test
    fun `a stale item response cannot resurrect a book a later complete sync pruned`() = runBlocking {
        val mutex = Mutex()
        val cache = mutableListOf("book-a", "book-b")
        val oldFetchStarted = CompletableDeferred<Unit>()
        val releaseOldFetch = CompletableDeferred<Unit>()

        val oldCall = fakeSync(mutex, cache) {
            oldFetchStarted.complete(Unit)
            releaseOldFetch.await()
            RemoteResult.Ok(listOf(book("book-a"), book("book-b")))
        }
        val oldCallJob = launch { oldCall() }
        oldFetchStarted.await()

        val newCall = fakeSync(mutex, cache) {
            RemoteResult.Ok(listOf(book("book-a")))
        }
        val newCallJob = launch { newCall() }

        releaseOldFetch.complete(Unit)
        oldCallJob.join()
        newCallJob.join()

        assertEquals(listOf("book-a"), cache)
    }

    @Test
    fun `a complete library removal cannot be undone by an older item sync`() = runBlocking {
        val mutex = Mutex()
        val cache = mutableListOf("book-a", "book-b")
        val oldFetchStarted = CompletableDeferred<Unit>()
        val releaseOldFetch = CompletableDeferred<Unit>()
        val removalRequested = CompletableDeferred<Unit>()

        val oldCall = fakeSync(mutex, cache) {
            oldFetchStarted.complete(Unit)
            releaseOldFetch.await()
            RemoteResult.Ok(listOf(book("book-a"), book("book-b")))
        }
        val oldCallJob = launch { oldCall() }
        oldFetchStarted.await()

        val removalJob = launch {
            removalRequested.complete(Unit)
            runSerializedLibraryItemPrune(mutex) { cache.clear() }
        }
        removalRequested.await()

        releaseOldFetch.complete(Unit)
        oldCallJob.join()
        removalJob.join()

        assertEquals(emptyList<String>(), cache)
    }

    @Test
    fun `cancelling after upsert still completes a complete sync prune`() = runBlocking {
        val mutex = Mutex()
        val cache = mutableListOf("stale-book")
        val upserted = CompletableDeferred<Unit>()
        val pruneBlocked = CompletableDeferred<Unit>()
        val releasePrune = CompletableDeferred<Unit>()

        val sync = launch {
            runSerializedLibraryItemSync(
                mutex = mutex,
                libraryId = "lib-a",
                fetchItems = { RemoteResult.Ok(listOf(book("current-book"))) },
                mergeItems = { it },
                upsertAll = { books ->
                    cache.addAll(books.map { it.id })
                    upserted.complete(Unit)
                },
                cachedNonDownloadedIds = { cache.toList() },
                deleteByIds = { _, ids ->
                    pruneBlocked.complete(Unit)
                    releasePrune.await()
                    cache.retainAll { it !in ids }
                },
                deleteAllServerBooks = { error("a non-empty complete fetch uses the scoped prune") },
            )
        }

        upserted.await()
        pruneBlocked.await()
        sync.cancel()
        releasePrune.complete(Unit)
        sync.join()

        assertEquals(listOf("current-book"), cache)
    }
}
