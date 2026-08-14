package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.AudioBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaBrowseScopeTest {

    @Test
    fun `Android Auto root offers recent library and downloaded in that order`() {
        assertEquals(
            listOf(
                MediaBrowseTree.RECENTLY_PLAYED_ID,
                MediaBrowseTree.LIBRARY_ID,
                MediaBrowseTree.DOWNLOADED_ID,
            ),
            MediaBrowseTree.rootItemIds(),
        )
    }

    @Test
    fun `unconfigured server shows phone setup row instead of blank Auto screen`() {
        assertEquals(
            listOf(MediaBrowseTree.SETUP_REQUIRED_ID),
            autoRootItemIds(canBrowse = false),
        )
    }

    @Test
    fun `completed sync invalidates all dynamic Auto shelves`() {
        assertEquals(
            listOf(
                MediaBrowseTree.RECENTLY_PLAYED_ID,
                MediaBrowseTree.LIBRARY_ID,
                MediaBrowseTree.DOWNLOADED_ID,
            ),
            autoBrowseParentsChangedAfterSync(),
        )
    }

    @Test
    fun `source change also invalidates the Auto root`() {
        assertEquals(
            listOf(
                MediaBrowseTree.ROOT_ID,
                MediaBrowseTree.RECENTLY_PLAYED_ID,
                MediaBrowseTree.LIBRARY_ID,
                MediaBrowseTree.DOWNLOADED_ID,
            ),
            autoBrowseParentsChangedAfterSourceChange(),
        )
    }

    @Test
    fun `downloaded shelf stays inside the selected server library`() {
        val settings = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            selectedLibraryId = "server-active",
        )
        val books = listOf(
            AudioBook(id = "active", libraryId = "server-active", isDownloaded = true),
            AudioBook(id = "other", libraryId = "server-other", isDownloaded = true),
            AudioBook(id = "stream-only", libraryId = "server-active", isDownloaded = false),
            AudioBook(id = "local", libraryId = "local", isLocal = true, isDownloaded = true),
            AudioBook(id = "archived", libraryId = "server-active", isDownloaded = true, archivedAt = 1L),
        )

        assertEquals(
            listOf("active"),
            downloadedBooksForAuto(books, settings).map { it.id },
        )
    }

    @Test
    fun `Android Auto browse stays inside the selected source`() {
        val settings = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            selectedLibraryId = "server-active",
            selectedLocalLibraryId = "local-active",
        )
        val books = listOf(
            AudioBook(id = "active", libraryId = "server-active", isLocal = false),
            AudioBook(id = "other", libraryId = "server-other", isLocal = false),
            AudioBook(id = "local", libraryId = "local-active", isLocal = true),
            AudioBook(id = "archived", libraryId = "server-active", isLocal = false, archivedAt = 1L),
        )

        assertEquals(listOf("active"), browseBooksForAuto(books, settings).map { it.id })
    }

    @Test
    fun `Android Auto browse is empty before an active library resolves`() {
        val books = listOf(
            AudioBook(id = "server", libraryId = "server-active", isLocal = false),
        )

        assertEquals(
            emptyList<String>(),
            browseBooksForAuto(
                books,
                AppSettings(appMode = AppMode.AUDIOBOOKSHELF, selectedLibraryId = null),
            ).map { it.id },
        )
    }

    @Test
    fun `logged out server mode is hidden while local mode remains available`() {
        assertEquals(
            false,
            canBrowseAuto(
                AppSettings(appMode = AppMode.AUDIOBOOKSHELF, selectedLibraryId = "server"),
                isAuthenticated = false,
            ),
        )
        assertEquals(
            true,
            canBrowseAuto(
                AppSettings(appMode = AppMode.LOCAL, selectedLocalLibraryId = "local"),
                isAuthenticated = false,
            ),
        )
    }

    @Test
    fun `stale Auto queue cannot load logged out or cross source books`() {
        val serverSettings = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            selectedLibraryId = "server-active",
        )
        val localSettings = AppSettings(
            appMode = AppMode.LOCAL,
            selectedLocalLibraryId = "local-active",
        )

        assertEquals(
            false,
            autoBookLoadAllowed(
                AudioBook(id = "server", libraryId = "server-active"),
                serverSettings,
                isAuthenticated = false,
            ),
        )
        assertEquals(
            false,
            autoBookLoadAllowed(
                AudioBook(id = "other-local", libraryId = "local-other", isLocal = true),
                localSettings,
                isAuthenticated = false,
            ),
        )
        assertEquals(
            true,
            autoBookLoadAllowed(
                AudioBook(id = "active-local", libraryId = "local-active", isLocal = true),
                localSettings,
                isAuthenticated = false,
            ),
        )
    }

    @Test
    fun `rejected Android Auto load never reuses the previous player queue`() {
        assertEquals(false, shouldReturnPlayerQueueToAuto(loadSucceeded = false, playerItemCount = 5))
        assertEquals(true, shouldReturnPlayerQueueToAuto(loadSucceeded = true, playerItemCount = 5))
        assertEquals(false, shouldReturnPlayerQueueToAuto(loadSucceeded = true, playerItemCount = 0))
    }

    @Test
    fun `Android Auto recent query uses selected library`() = runBlocking {
        val settings = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            selectedLibraryId = "server-active",
        )
        val active = AudioBook(id = "active", libraryId = "server-active", isLocal = false)

        val result = recentBooksForAuto(settings, maxItems = 20, page = 0, pageSize = 20) { libraryId, isLocal, limit ->
            assertEquals("server-active", libraryId)
            assertEquals(false, isLocal)
            listOf(active).take(limit)
        }

        assertEquals(listOf(active), result)
    }

    @Test
    fun `Android Auto recent query pages without duplicating the first page`() = runBlocking {
        val settings = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            selectedLibraryId = "server-active",
        )
        val books = (0 until 5).map { index ->
            AudioBook(id = "book-$index", libraryId = "server-active")
        }

        val result = recentBooksForAuto(
            settings = settings,
            maxItems = 20,
            page = 1,
            pageSize = 2,
        ) { _, isLocal, limit ->
            assertEquals(false, isLocal)
            books.take(limit)
        }

        assertEquals(listOf("book-2", "book-3"), result.map { it.id })
    }

    @Test
    fun `Android Auto recent query applies local source before its limit`() = runBlocking {
        val settings = AppSettings(
            appMode = AppMode.LOCAL,
            selectedLocalLibraryId = "local-active",
        )

        val result = recentBooksForAuto(settings, maxItems = 20, page = 0, pageSize = 5) {
                libraryId, isLocal, limit ->
            assertEquals("local-active", libraryId)
            assertEquals(true, isLocal)
            assertEquals(5, limit)
            emptyList()
        }
        assertEquals(emptyList<AudioBook>(), result)
    }

    @Test
    fun `browse notifications use count queries instead of materializing books`() = runBlocking {
        val queried = mutableListOf<String>()
        val count = autoBrowseChildCount(
            parentId = MediaBrowseTree.DOWNLOADED_ID,
            canBrowse = true,
            activeLibraryId = "active",
            activeIsLocal = false,
            countRecent = { _, _ -> error("recent count should not run") },
            countLibrary = { _, _ -> error("library count should not run") },
            countDownloaded = { libraryId, isLocal ->
                queried += libraryId
                assertEquals(false, isLocal)
                37
            },
        )

        assertEquals(37, count)
        assertEquals(listOf("active"), queried)
    }

    @Test
    fun `browse notification counts preserve root auth cap and local source`() = runBlocking {
        val unused: suspend (String, Boolean) -> Int = { _, _ -> error("count should not run") }
        assertEquals(
            1,
            autoBrowseChildCount(
                MediaBrowseTree.ROOT_ID,
                canBrowse = false,
                activeLibraryId = null,
                activeIsLocal = false,
                countRecent = unused,
                countLibrary = unused,
                countDownloaded = unused,
            ),
        )
        assertEquals(
            0,
            autoBrowseChildCount(
                MediaBrowseTree.LIBRARY_ID,
                canBrowse = false,
                activeLibraryId = "server",
                activeIsLocal = false,
                countRecent = unused,
                countLibrary = unused,
                countDownloaded = unused,
            ),
        )
        assertEquals(
            20,
            autoBrowseChildCount(
                MediaBrowseTree.RECENTLY_PLAYED_ID,
                canBrowse = true,
                activeLibraryId = "server",
                activeIsLocal = false,
                countRecent = { libraryId, isLocal ->
                    assertEquals("server", libraryId)
                    assertEquals(false, isLocal)
                    99
                },
                countLibrary = unused,
                countDownloaded = unused,
            ),
        )
        assertEquals(
            12,
            autoBrowseChildCount(
                MediaBrowseTree.LIBRARY_ID,
                canBrowse = true,
                activeLibraryId = "local",
                activeIsLocal = true,
                countRecent = unused,
                countLibrary = { libraryId, isLocal ->
                    assertEquals("local", libraryId)
                    assertEquals(true, isLocal)
                    12
                },
                countDownloaded = unused,
            ),
        )
    }
}
