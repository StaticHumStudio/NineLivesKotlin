package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.domain.model.LibraryAuthor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * When a library's Audiobookshelf author list is fetched after a sync, and
 * what a fetch may do to the list already stored (issue #87). A failure never
 * touches the stored list, and nothing a refresh throws may escape, since it
 * runs in the sync scope with no exception handler.
 */
class LibraryAuthorSyncTest {

    private val authors = listOf(LibraryAuthor("au_1", "Smith, John"), LibraryAuthor("au_2", "Neil Gaiman"))

    @Test
    fun `authors refresh after a sync that wrote books`() {
        listOf(
            LibraryRefreshOutcome.Full(RemoteResult.Ok(5)),
            LibraryRefreshOutcome.Full(RemoteResult.Partial(3, "x")),
            LibraryRefreshOutcome.Incremental(1, 6),
        ).forEach { outcome ->
            assertTrue(outcome.toString(), shouldRefreshAuthors(outcome, storedThisRun = true))
            assertTrue(outcome.toString(), shouldRefreshAuthors(outcome, storedThisRun = false))
        }
    }

    @Test
    fun `authors refresh once per run when nothing changed`() {
        listOf(LibraryRefreshOutcome.Unchanged(5), LibraryRefreshOutcome.Deferred("m")).forEach { outcome ->
            assertTrue(outcome.toString(), shouldRefreshAuthors(outcome, storedThisRun = false))
            assertFalse(outcome.toString(), shouldRefreshAuthors(outcome, storedThisRun = true))
        }
    }

    @Test
    fun `a failed check or failed download never asks for authors`() {
        listOf(LibraryRefreshOutcome.CheckFailed("x"), LibraryRefreshOutcome.Full(RemoteResult.Failed("x"))).forEach { outcome ->
            assertFalse(outcome.toString(), shouldRefreshAuthors(outcome, storedThisRun = false))
            assertFalse(outcome.toString(), shouldRefreshAuthors(outcome, storedThisRun = true))
        }
    }

    @Test
    fun `a failed fetch never touches the stored list`() = runBlocking {
        var stores = 0
        val stored = refreshLibraryAuthors(
            fetch = { RemoteResult.Failed("HTTP 500") },
            storeIfCurrent = { stores++; true },
        )

        assertFalse(stored)
        assertEquals(0, stores)
    }

    @Test
    fun `a partial list is never stored`() = runBlocking {
        var stores = 0
        val stored = refreshLibraryAuthors(
            fetch = { RemoteResult.Partial(authors, "cut short") },
            storeIfCurrent = { stores++; true },
        )

        assertFalse(stored)
        assertEquals(0, stores)
    }

    @Test
    fun `a fetched list is stored whole`() = runBlocking {
        val received = mutableListOf<List<LibraryAuthor>>()
        val stored = refreshLibraryAuthors(
            fetch = { RemoteResult.Ok(authors) },
            storeIfCurrent = { received += it; true },
        )

        assertTrue(stored)
        assertEquals(listOf(authors), received)
    }

    @Test
    fun `an author list for an account that signed out is never stored`() = runBlocking {
        val stored = refreshLibraryAuthors(
            fetch = { RemoteResult.Ok(authors) },
            storeIfCurrent = { false },
        )

        assertFalse(stored)
    }

    @Test
    fun `a thrown fetch or store error is a failed refresh, never a crash`() = runBlocking {
        assertFalse(refreshLibraryAuthors(fetch = { throw IOException("reset") }, storeIfCurrent = { true }))
        assertFalse(
            refreshLibraryAuthors(fetch = { RemoteResult.Ok(authors) }, storeIfCurrent = { throw IllegalStateException("disk") })
        )
        try {
            refreshLibraryAuthors(fetch = { throw CancellationException("left") }, storeIfCurrent = { true })
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
    }
}
