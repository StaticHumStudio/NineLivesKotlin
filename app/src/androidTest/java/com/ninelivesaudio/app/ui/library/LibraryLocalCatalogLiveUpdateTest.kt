package com.ninelivesaudio.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.NineLivesApp
import com.ninelivesaudio.app.data.local.AppDatabase
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.data.local.entity.LibraryEntity
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.data.repository.LibraryRepository
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.service.local.LocalFolderAccess
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A first-run folder scan creates the folder's library row and imports its
 * books only after the scan finishes. Opening the Library tab during the scan
 * loaded an empty shelf, and in local mode nothing ever asked it to look
 * again, so it said "The Archive Stands Empty" until the tab was re-entered.
 * These drive the real [LibraryViewModel] against a real Room database and
 * write rows the way the scan does, with the ViewModel already open.
 */
@RunWith(AndroidJUnit4::class)
class LibraryLocalCatalogLiveUpdateTest {

    private val app = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as NineLivesApp
    private lateinit var database: AppDatabase
    private val viewModels = ViewModelStore()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        // Stop the ViewModel's collectors before the database they watch goes away.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModels.clear() }
        database.close()
    }

    @Test
    fun booksAFirstScanImportsWhileTheLibraryIsOpenAppearWithoutReenteringTheTab() = runBlocking {
        withLocalMode {
            val viewModel = openLibrary()
            awaitState(viewModel, "the empty shelf opened mid-scan") {
                !it.isLoading && it.libraries.isEmpty() && it.selectedLibrary == null
            }

            // The scan's own order: the folder row once the folder reads, then the books.
            database.libraryDao().upsert(folder)
            database.audioBookDao().upsertAll((1..9).map { book("book-$it") })

            val shown = awaitState(viewModel, "the nine scanned books") {
                it.selectedLibrary?.id == folder.id && it.filteredBooks.size == 9
            }
            assertEquals(9, shown.totalBookCount)
        }
    }

    @Test
    fun aBookARescanAddsAppearsOnTheOpenShelf() = runBlocking {
        database.libraryDao().upsert(folder)
        database.audioBookDao().upsertAll(listOf(book("book-1"), book("book-2")))
        withLocalMode {
            val viewModel = openLibrary()
            awaitState(viewModel, "the two books already on the shelf") {
                it.selectedLibrary?.id == folder.id && it.filteredBooks.size == 2
            }

            database.audioBookDao().upsert(book("book-3"))

            val shown = awaitState(viewModel, "the book the rescan added") { it.filteredBooks.size == 3 }
            assertEquals(listOf("book-1", "book-2", "book-3"), shown.filteredBooks.map { it.id }.sorted())
        }
    }

    @Test
    fun aBookARescanArchivesLeavesTheOpenShelf() = runBlocking {
        database.libraryDao().upsert(folder)
        database.audioBookDao().upsertAll(listOf(book("book-1"), book("book-2")))
        withLocalMode {
            val viewModel = openLibrary()
            awaitState(viewModel, "the two books already on the shelf") {
                it.selectedLibrary?.id == folder.id && it.filteredBooks.size == 2
            }

            database.audioBookDao().archiveByIds(listOf("book-2"), archivedAt = 1_727_000_000_000L)

            val shown = awaitState(viewModel, "the archived book to leave") { it.filteredBooks.size == 1 }
            assertEquals(listOf("book-1"), shown.filteredBooks.map { it.id })
        }
    }

    @Test
    fun aRescanThatRetitlesABookUpdatesTheOpenShelf() = runBlocking {
        database.libraryDao().upsert(folder)
        database.audioBookDao().upsertAll(listOf(book("book-1"), book("book-2")))
        withLocalMode {
            val viewModel = openLibrary()
            awaitState(viewModel, "the two books already on the shelf") {
                it.selectedLibrary?.id == folder.id && it.filteredBooks.size == 2
            }

            // Same file, same folder, new tags: only what the shelf shows changes.
            database.audioBookDao().upsert(book("book-2").copy(title = "Retagged Title"))

            val shown = awaitState(viewModel, "the rescanned title") { state ->
                state.filteredBooks.any { it.title == "Retagged Title" }
            }
            assertEquals(2, shown.filteredBooks.size)
        }
    }

    @Test
    fun aRescanThatSplitsAChapterUpdatesTheOpenShelf() = runBlocking {
        val oneChapter = """[{"Id":0,"Start":0.0,"End":600.0,"Title":"01"}]"""
        val twoChapters = """[{"Id":0,"Start":0.0,"End":300.0,"Title":"01"},""" +
            """{"Id":1,"Start":300.0,"End":600.0,"Title":"02"}]"""
        database.libraryDao().upsert(folder)
        database.audioBookDao().upsertAll(listOf(book("book-1").copy(durationSeconds = 600.0, chaptersJson = oneChapter)))
        withLocalMode {
            val viewModel = openLibrary()
            awaitState(viewModel, "the one-chapter book on the shelf") {
                it.filteredBooks.singleOrNull()?.chapters?.size == 1
            }

            // Same file length, new track layout: the card's "Ch 1/N" count changes.
            database.audioBookDao().upsert(book("book-1").copy(durationSeconds = 600.0, chaptersJson = twoChapters))

            awaitState(viewModel, "the split chapters") { it.filteredBooks.singleOrNull()?.chapters?.size == 2 }
        }
    }

    private fun openLibrary(): LibraryViewModel {
        val audioBookRepository = AudioBookRepository(
            context = app,
            audioBookDao = database.audioBookDao(),
            apiService = app.apiService,
            localListeningSessionDao = database.localListeningSessionDao(),
            localBookmarkDao = database.localBookmarkDao(),
            playbackProgressDao = database.playbackProgressDao(),
            database = database,
        )
        val libraryRepository = LibraryRepository(
            libraryDao = database.libraryDao(),
            audioBookDao = database.audioBookDao(),
            audioBookRepository = audioBookRepository,
            apiService = app.apiService,
        )
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(
                libraryRepository = libraryRepository,
                audioBookRepository = audioBookRepository,
                apiService = app.apiService,
                connectivityMonitor = app.connectivityMonitor,
                settingsManager = app.settingsManager,
                entitlements = app.entitlementRepository,
                localFolderAccess = LocalFolderAccess(app),
            ) as T
        }
        lateinit var viewModel: LibraryViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel = ViewModelProvider(viewModels, factory)[LibraryViewModel::class.java]
        }
        return viewModel
    }

    private suspend fun withLocalMode(block: suspend () -> Unit) {
        val originalSettings = app.settingsManager.currentSettings
        app.settingsManager.saveSettings(
            originalSettings.copy(appMode = AppMode.LOCAL, selectedLocalLibraryId = null)
        )
        try {
            block()
        } finally {
            app.settingsManager.saveSettings(originalSettings)
        }
    }

    private suspend fun awaitState(
        viewModel: LibraryViewModel,
        waitingFor: String,
        predicate: (LibraryViewModel.UiState) -> Boolean,
    ): LibraryViewModel.UiState =
        withTimeoutOrNull(5_000) { viewModel.uiState.filter(predicate).first() }
            ?: throw AssertionError(
                "Timed out waiting for $waitingFor. Shelf shows " +
                    viewModel.uiState.value.filteredBooks.map { it.id } +
                    " from library " + viewModel.uiState.value.selectedLibrary?.id
            )

    private val folder = LibraryEntity(id = "audiobooks", name = "Audiobooks", isLocal = 1)

    private fun book(id: String) = AudioBookEntity(
        id = id,
        libraryId = folder.id,
        isLocal = 1,
        title = id,
        author = "Author",
        isDownloaded = 1,
    )
}
