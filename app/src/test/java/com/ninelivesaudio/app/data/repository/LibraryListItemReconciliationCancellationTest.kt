package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.domain.model.Library
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryListItemReconciliationCancellationTest {

    @Test
    fun `cancelling a queued library prune still removes its library and books together`() = runBlocking {
        val librarySyncMutex = Mutex()
        val itemSyncMutex = Mutex()
        val libraries = mutableListOf("lib-a")
        val books = mutableListOf("book-a")
        val itemLockHeld = CompletableDeferred<Unit>()
        val releaseItemLock = CompletableDeferred<Unit>()
        val pruneRequested = CompletableDeferred<Unit>()

        val itemSync = launch {
            itemSyncMutex.withLock {
                itemLockHeld.complete(Unit)
                releaseItemLock.await()
            }
        }
        itemLockHeld.await()

        val librarySync = launch {
            runSerializedLibrarySync(
                mutex = librarySyncMutex,
                fetchLibraries = { RemoteResult.Ok(emptyList()) },
                cachedServerLibraryIds = { libraries.toList() },
                upsertAll = { },
                deleteMissing = { keptIds -> libraries.retainAll { it in keptIds } },
                deleteAllServerLibraries = { libraries.clear() },
                pruneLibraryBooks = {
                    pruneRequested.complete(Unit)
                    runSerializedLibraryItemPrune(itemSyncMutex) { books.clear() }
                    false
                },
            )
        }
        pruneRequested.await()

        librarySync.cancel()
        releaseItemLock.complete(Unit)
        librarySync.join()
        itemSync.join()

        assertEquals(emptyList<String>(), libraries)
        assertEquals(emptyList<String>(), books)
    }
}
