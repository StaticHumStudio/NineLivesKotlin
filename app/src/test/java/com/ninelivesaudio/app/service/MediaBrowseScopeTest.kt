package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.AudioBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `completed sync also refreshes folders Auto has open`() {
        assertEquals(
            listOf(
                MediaBrowseTree.RECENTLY_PLAYED_ID,
                MediaBrowseTree.LIBRARY_ID,
                MediaBrowseTree.DOWNLOADED_ID,
                "library/T",
            ),
            autoBrowseParentsChangedAfterSync(listOf("library/T")),
        )
    }

    @Test
    fun `source change refreshes the root, the shelves and open folders`() {
        assertEquals(
            listOf(
                MediaBrowseTree.ROOT_ID,
                MediaBrowseTree.RECENTLY_PLAYED_ID,
                MediaBrowseTree.LIBRARY_ID,
                MediaBrowseTree.DOWNLOADED_ID,
                "library/TH/2",
                "downloaded/#",
            ),
            autoBrowseParentsChangedAfterSourceChange(listOf("library/TH/2", "downloaded/#")),
        )
    }

    @Test
    fun `recent folders keep the last eight, newest first`() {
        val recent = RecentAutoFolders()
        (1..10).forEach { recent.record("library/T/$it") }
        assertEquals((10 downTo 3).map { "library/T/$it" }, recent.snapshot())

        recent.record("library/T/5")
        assertEquals(
            listOf("library/T/5") + (10 downTo 3).filter { it != 5 }.map { "library/T/$it" },
            recent.snapshot(),
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
    fun `the downloaded shelf stays inside the selected server library`() {
        val settings = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            selectedLibraryId = "server-active",
            selectedLocalLibraryId = "local",
        )

        assertEquals(AutoScope("server-active", isLocal = false), autoScope(settings))
    }

    @Test
    fun `local mode browses the local library`() {
        val settings = AppSettings(
            appMode = AppMode.LOCAL,
            selectedLibraryId = "server-active",
            selectedLocalLibraryId = "local-lib",
        )

        assertEquals(AutoScope("local-lib", isLocal = true), autoScope(settings))
    }

    @Test
    fun `no active library browses nothing`() {
        assertNull(autoScope(AppSettings(appMode = AppMode.AUDIOBOOKSHELF)))
        assertNull(autoScope(AppSettings(appMode = AppMode.LOCAL, selectedLibraryId = "server")))
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

    private val noCount: suspend (String, Boolean) -> Int = { _, _ -> error("recent count should not run") }
    private val noTree: suspend (AutoFolderId) -> Int = { error("tree count should not run") }

    @Test
    fun `library and downloaded counts go through the tree`() = runBlocking {
        val asked = mutableListOf<AutoFolderId>()
        val tree: suspend (AutoFolderId) -> Int = { asked += it; 27 }

        assertEquals(27, autoBrowseChildCount(MediaBrowseTree.LIBRARY_ID, true, "active", false, noCount, tree))
        assertEquals(27, autoBrowseChildCount(MediaBrowseTree.DOWNLOADED_ID, true, "active", false, noCount, tree))
        assertEquals(
            listOf(AutoFolderId(AutoBranch.LIBRARY, null), AutoFolderId(AutoBranch.DOWNLOADED, null)),
            asked,
        )
    }

    @Test
    fun `a folder counts through the tree`() = runBlocking {
        val asked = mutableListOf<AutoFolderId>()
        val count = autoBrowseChildCount("library/T", true, "active", false, noCount) { asked += it; 12 }

        assertEquals(12, count)
        assertEquals(listOf(AutoFolderId(AutoBranch.LIBRARY, "T")), asked)
    }

    @Test
    fun `a malformed folder id counts zero`() = runBlocking {
        listOf("library/t", "library/T/2", "book_1", "podcasts").forEach {
            assertEquals(it, 0, autoBrowseChildCount(it, true, "active", false, noCount, noTree))
        }
    }

    @Test
    fun `nothing is counted without a browsable source`() = runBlocking {
        listOf(MediaBrowseTree.RECENTLY_PLAYED_ID, MediaBrowseTree.LIBRARY_ID, MediaBrowseTree.DOWNLOADED_ID, "library/T").forEach {
            assertEquals(it, 0, autoBrowseChildCount(it, false, "server", false, noCount, noTree))
            assertEquals(it, 0, autoBrowseChildCount(it, true, null, false, noCount, noTree))
        }
    }

    @Test
    fun `browse notification counts preserve root auth cap and local source`() = runBlocking {
        assertEquals(1, autoBrowseChildCount(MediaBrowseTree.ROOT_ID, false, null, false, noCount, noTree))
        assertEquals(3, autoBrowseChildCount(MediaBrowseTree.ROOT_ID, true, "server", false, noCount, noTree))
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
                countTree = noTree,
            ),
        )
        assertEquals(
            4,
            autoBrowseChildCount(
                MediaBrowseTree.RECENTLY_PLAYED_ID,
                canBrowse = true,
                activeLibraryId = "local",
                activeIsLocal = true,
                countRecent = { libraryId, isLocal ->
                    assertEquals("local", libraryId)
                    assertEquals(true, isLocal)
                    4
                },
                countTree = noTree,
            ),
        )
    }
}
