package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.domain.model.Library
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryRepositoryOwnerScopeTest {
    @Test
    fun `stale scope immediately before library reconciliation writes nothing`() = runBlocking {
        var writes = 0

        val reconciled = reconcileServerLibraries(
            isComplete = true,
            fetched = listOf(Library(id = "nlr1:owner:book", name = "B")),
            cachedServerLibraryIds = { error("stale scope must stop before cache reads") },
            upsertAll = { writes++ },
            deleteMissing = { writes++ },
            deleteAllServerLibraries = { writes++ },
            pruneLibraryBooks = { error("stale scope must not prune") },
            isCurrentScope = { false },
        )

        assertEquals(false, reconciled)
        assertEquals(0, writes)
    }

    @Test
    fun `B reconciliation does not prune A or raw legacy cache rows`() = runBlocking {
        val cache = mutableListOf("A:library", "B:library", "raw-library")
        val fetched = listOf(Library(id = "B:new-library", name = "B"))

        reconcileServerLibraries(
            isComplete = true,
            fetched = fetched,
            cachedServerLibraryIds = { cache.filter { it.startsWith("B:") } },
            upsertAll = { libraries -> cache.removeAll { it.startsWith("B:") }; cache += libraries.map { it.id } },
            deleteMissing = { kept -> cache.removeAll { it.startsWith("B:") && it !in kept } },
            deleteAllServerLibraries = { cache.removeAll { it.startsWith("B:") } },
            pruneLibraryBooks = { false },
        )

        assertEquals(listOf("A:library", "raw-library", "B:new-library"), cache)
    }
}
