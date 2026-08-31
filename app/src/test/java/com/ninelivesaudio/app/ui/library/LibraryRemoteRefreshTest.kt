package com.ninelivesaudio.app.ui.library

import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.data.repository.ReconciledServerLibraryList
import com.ninelivesaudio.app.data.repository.reconcileServerLibraries
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.LastSyncRecord
import com.ninelivesaudio.app.domain.model.Library
import com.ninelivesaudio.app.domain.model.SyncResult
import com.ninelivesaudio.app.service.resolveActiveLibrarySelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRemoteRefreshTest {

    @Test
    fun `library refresh awaits the targeted list fetch and uses its rows`() = runBlocking {
        val cached = listOf(Library(id = "cached", name = "Cached"))
        val fetched = listOf(Library(id = "remote", name = "Remote"))
        var fetchCount = 0

        val refresh = refreshRemoteLibraryList(
            readCached = { cached },
            fetchRemote = {
                fetchCount += 1
                ReconciledServerLibraryList(
                    result = RemoteResult.Ok(fetched),
                    reconciledLibraries = fetched,
                )
            },
        )

        assertEquals(1, fetchCount)
        assertEquals(fetched, refresh.libraries)
        assertEquals(RemoteResult.Ok(fetched), refresh.result)
    }

    @Test
    fun `failed library refresh keeps cached rows and its failure`() = runBlocking {
        val cached = listOf(Library(id = "cached", name = "Cached"))
        val failure = RemoteResult.Failed("HTTP 500")

        val refresh = refreshRemoteLibraryList(
            readCached = { cached },
            fetchRemote = {
                ReconciledServerLibraryList(
                    result = failure,
                    reconciledLibraries = null,
                )
            },
        )

        assertEquals(cached, refresh.libraries)
        assertSame(failure, refresh.result)
    }

    @Test
    fun `successful empty library refresh replaces stale cached libraries`() = runBlocking {
        val cached = listOf(Library(id = "stale", name = "Stale"))

        val refresh = refreshRemoteLibraryList(
            readCached = { cached },
            fetchRemote = {
                ReconciledServerLibraryList(
                    result = RemoteResult.Ok(emptyList()),
                    reconciledLibraries = emptyList(),
                )
            },
        )

        assertEquals(emptyList<Library>(), refresh.libraries)
        assertEquals(RemoteResult.Ok(emptyList<Library>()), refresh.result)
    }

    @Test
    fun `successful library refresh uses its captured reconcile snapshot to keep downloads selectable`() = runBlocking {
        val fetched = Library(id = "server", name = "Server")
        val retainedForDownloads = Library(id = "downloaded", name = "Downloaded")
        var reconciledCache = listOf(fetched, retainedForDownloads)

        val refresh = refreshRemoteLibraryList(
            readCached = { reconciledCache },
            fetchRemote = {
                val capturedReconcile = listOf(fetched, retainedForDownloads)
                // Another complete sync can update Room after this result
                // returns. The screen must keep using this sync's snapshot.
                reconciledCache = listOf(Library(id = "newer", name = "Newer"))
                ReconciledServerLibraryList(
                    result = RemoteResult.Ok(listOf(fetched)),
                    reconciledLibraries = capturedReconcile,
                )
            },
        )
        val selection = resolveActiveLibrarySelection(
            libraries = refresh.libraries,
            settings = AppSettings(
                appMode = AppMode.AUDIOBOOKSHELF,
                selectedLibraryId = retainedForDownloads.id,
            ),
        )

        assertEquals(listOf(fetched, retainedForDownloads), refresh.libraries)
        assertEquals(retainedForDownloads, selection.library)
        assertFalse(selection.requiresPersistence)
    }

    @Test
    fun `successful library refresh does not restore a fully pruned library`() = runBlocking {
        val fetched = Library(id = "server", name = "Server")
        val fullyPruned = Library(id = "pruned", name = "Pruned")
        var reconciledCache = listOf(fetched, fullyPruned)

        val refresh = refreshRemoteLibraryList(
            readCached = { reconciledCache },
            fetchRemote = {
                reconciledCache = listOf(fetched)
                ReconciledServerLibraryList(
                    result = RemoteResult.Ok(listOf(fetched)),
                    reconciledLibraries = reconciledCache,
                )
            },
        )
        val selection = resolveActiveLibrarySelection(
            libraries = refresh.libraries,
            settings = AppSettings(
                appMode = AppMode.AUDIOBOOKSHELF,
                selectedLibraryId = fullyPruned.id,
            ),
        )

        assertEquals(listOf(fetched), refresh.libraries)
        assertEquals(fetched, selection.library)
        assertTrue(selection.requiresPersistence)
    }

    @Test
    fun `offline restart keeps a downloaded retained library selectable after an empty sync`() = runBlocking {
        val retainedForDownloads = Library(id = "downloaded", name = "Downloaded")
        val cachedLibraries = mutableListOf(retainedForDownloads)
        val cachedBooks = mutableListOf(
            AudioBook(
                id = "downloaded-book",
                libraryId = retainedForDownloads.id,
                isDownloaded = true,
            ),
        )

        reconcileServerLibraries(
            isComplete = true,
            fetched = emptyList(),
            cachedServerLibraryIds = { cachedLibraries.map { it.id } },
            upsertAll = { throw AssertionError("empty sync must not upsert libraries") },
            deleteMissing = { keptIds ->
                cachedLibraries.retainAll { it.id in keptIds }
            },
            deleteAllServerLibraries = { cachedLibraries.clear() },
            pruneLibraryBooks = { libraryId ->
                cachedBooks.removeAll { it.libraryId == libraryId && !it.isDownloaded }
                cachedBooks.any { it.libraryId == libraryId && it.isDownloaded }
            },
        )
        val restoredSettings = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            selectedLibraryId = retainedForDownloads.id,
            lastSync = LastSyncRecord(
                result = SyncResult.SUCCESS,
                libraryCount = 0,
                bookCount = 0,
                completedAtMs = 123L,
            ),
        )

        val visible = visibleCachedLibraries(restoredSettings, cachedLibraries)
        val selection = resolveActiveLibrarySelection(visible, restoredSettings)

        assertEquals(listOf(retainedForDownloads), visible)
        assertEquals(retainedForDownloads, selection.library)
        assertFalse(selection.requiresPersistence)
    }

    @Test
    fun `offline restart after successful empty sync suppresses cache when no downloads were retained`() {
        val cached = emptyList<Library>()
        val restoredSettings = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            lastSync = LastSyncRecord(
                result = SyncResult.SUCCESS,
                libraryCount = 0,
                bookCount = 0,
                completedAtMs = 123L,
            ),
        )

        assertEquals(
            emptyList<Library>(),
            visibleCachedLibraries(restoredSettings, cached),
        )
    }

    @Test
    fun `a successful empty sync recorded for a different server does not suppress the current server's cache`() {
        // A record from server A must not decide what renders on server B's
        // shelf. An offline launch against B would otherwise show B's real
        // cached libraries suppressed by A's unrelated "confirmed empty".
        val stale = listOf(Library(id = "cached", name = "Cached"))
        val restoredSettings = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            serverUrl = "https://b.example.com",
            lastSync = LastSyncRecord(
                result = SyncResult.SUCCESS,
                libraryCount = 0,
                bookCount = 0,
                serverUrl = "https://a.example.com",
                completedAtMs = 123L,
            ),
        )

        assertEquals(stale, visibleCachedLibraries(restoredSettings, stale))
    }

    @Test
    fun `offline restart after failed sync keeps cached libraries`() {
        val stale = listOf(Library(id = "cached", name = "Cached"))
        val restoredSettings = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            lastSync = LastSyncRecord(
                result = SyncResult.FAILED,
                libraryCount = 0,
                bookCount = 0,
                failure = "offline",
                completedAtMs = 123L,
            ),
        )

        assertEquals(stale, visibleCachedLibraries(restoredSettings, stale))
    }

    @Test
    fun `clearing the library selection clears stale shelf rows`() {
        val staleBook = AudioBook(id = "stale", libraryId = "stale")
        val current = LibraryViewModel.UiState(
            libraries = listOf(Library(id = "stale", name = "Stale")),
            selectedLibrary = Library(id = "stale", name = "Stale"),
            filteredBooks = listOf(staleBook),
            groupedSections = listOf(
                GroupedSection(key = "stale", title = "Stale", books = listOf(staleBook)),
            ),
            availableGroups = listOf("Stale"),
            totalBookCount = 1,
        )

        val cleared = current.withLibrarySelection(
            libraries = emptyList(),
            selectedLibrary = null,
            isLocalMode = false,
        )

        assertEquals(emptyList<AudioBook>(), cleared.filteredBooks)
        assertEquals(emptyList<GroupedSection>(), cleared.groupedSections)
        assertEquals(emptyList<String>(), cleared.availableGroups)
        assertEquals(0, cleared.totalBookCount)
    }

    @Test
    fun `library load cancellation escapes the broad failure path`() {
        val cancellation = CancellationException("leave screen")

        try {
            rethrowLibraryLoadCancellation(cancellation)
            throw AssertionError("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun `selected shelf refresh awaits only its targeted item fetch`() = runBlocking {
        val books = listOf(AudioBook(id = "book", libraryId = "selected"))
        val requestedIds = mutableListOf<String>()

        val result = refreshSelectedLibraryItems("selected") { libraryId ->
            requestedIds += libraryId
            RemoteResult.Ok(books)
        }

        assertEquals(listOf("selected"), requestedIds)
        assertEquals(RemoteResult.Ok(books), result)
    }
}
