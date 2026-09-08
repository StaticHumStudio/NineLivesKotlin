package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.domain.model.AudioBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioBookRepositoryOwnerScopeTest {
    @Test
    fun `stale scope immediately before book reconciliation writes nothing`() = runBlocking {
        var writes = 0

        val reconciled = reconcileServerLibrary(
            isComplete = true,
            merged = listOf(AudioBook(id = "nlr1:owner:book", libraryId = "nlr1:owner:library")),
            libraryId = "nlr1:owner:library",
            upsertAll = { writes++ },
            cachedNonDownloadedIds = { error("stale scope must stop before cache reads") },
            deleteByIds = { _, _ -> writes++ },
            deleteAllServerBooks = { writes++ },
            isCurrentScope = { false },
        )

        assertEquals(false, reconciled)
        assertEquals(0, writes)
    }

    @Test
    fun `B book reconciliation deletes only B rows when raw IDs match`() = runBlocking {
        val cache = mutableListOf("A:book", "B:stale-book", "raw-book")

        reconcileServerLibrary(
            isComplete = true,
            merged = listOf(AudioBook(id = "B:book", libraryId = "B:library")),
            libraryId = "B:library",
            upsertAll = { cache += it.map { book -> book.id } },
            cachedNonDownloadedIds = { cache.filter { it.startsWith("B:") } },
            deleteByIds = { _, ids -> cache.removeAll { it in ids } },
            deleteAllServerBooks = { error("a non-empty result reconciles only stale IDs") },
        )

        assertEquals(listOf("A:book", "raw-book", "B:book"), cache)
    }
}
