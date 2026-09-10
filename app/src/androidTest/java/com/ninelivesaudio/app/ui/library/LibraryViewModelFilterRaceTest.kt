package com.ninelivesaudio.app.ui.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.NineLivesApp
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.dao.LibraryDao
import com.ninelivesaudio.app.data.local.dao.LocalBookmarkDao
import com.ninelivesaudio.app.data.local.dao.LocalListeningSessionDao
import com.ninelivesaudio.app.data.local.dao.PlaybackProgressDao
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.data.local.entity.LibraryEntity
import com.ninelivesaudio.app.data.local.entity.RecentlyPlayedResult
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.data.repository.LibraryRepository
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.Library
import com.ninelivesaudio.app.service.local.LocalFolderAccess
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real [LibraryViewModel] callbacks with a DAO query that ignores
 * cancellation once. The test fixture controls only its Room-facing DAOs; the
 * ViewModel, repositories, input callbacks, debounce, and publication lane are
 * the production code used by the screen.
 */
@RunWith(AndroidJUnit4::class)
class LibraryViewModelFilterRaceTest {

    @Test
    fun staleSearchQueryCannotOverwriteTheNewerQueryResult() = runBlocking {
        withFixture { fixture ->
            fixture.awaitInitialShelf()
            fixture.dao.delayNextFilteredQuery()

            fixture.viewModel.onSearchQueryChanged("old")
            assertTrue("old query did not reach the DAO", fixture.dao.awaitDelayedQuery())

            fixture.viewModel.onSearchQueryChanged("new")
            fixture.awaitState {
                it.searchQuery == "new" && it.filteredBooks.singleOrNull()?.id == "beta-book"
            }

            fixture.dao.releaseDelayedQuery()
            drainMainLooper()
            val settled = fixture.viewModel.uiState.value
            assertEquals("new", settled.searchQuery)
            assertEquals(listOf("beta-book"), settled.filteredBooks.map { it.id })
            assertEquals(2, settled.totalBookCount)
        }
    }

    @Test
    fun staleSortQueryCannotOverwriteTheNewlySelectedLibrary() = runBlocking {
        withFixture { fixture ->
            fixture.awaitInitialShelf()
            fixture.dao.delayNextFilteredQuery()

            fixture.viewModel.onSortModeChanged(SortMode.TITLE_AZ)
            assertTrue("sort query did not reach the DAO", fixture.dao.awaitDelayedQuery())

            fixture.viewModel.onLibrarySelected(FixtureLibraries.beta)
            fixture.awaitState {
                it.selectedLibrary?.id == FixtureLibraries.beta.id &&
                    it.filteredBooks.singleOrNull()?.id == "beta-book"
            }

            fixture.dao.releaseDelayedQuery()
            drainMainLooper()
            val settled = fixture.viewModel.uiState.value
            assertEquals(FixtureLibraries.beta.id, settled.selectedLibrary?.id)
            assertEquals(listOf("beta-book"), settled.filteredBooks.map { it.id })
            assertEquals(2, settled.totalBookCount)
        }
    }

    private suspend fun withFixture(block: suspend (LibraryViewModelFixture) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NineLivesApp
        val originalSettings = app.settingsManager.currentSettings
        app.settingsManager.saveSettings(
            originalSettings.copy(
                appMode = AppMode.LOCAL,
                selectedLocalLibraryId = FixtureLibraries.alpha.id,
            )
        )
        try {
            block(LibraryViewModelFixture(app))
        } finally {
            app.settingsManager.saveSettings(originalSettings)
        }
    }

    private fun drainMainLooper() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {}
    }
}

private class LibraryViewModelFixture(app: NineLivesApp) {
    val dao = DelayedLibraryFilterDao()
    private val audioBookRepository = AudioBookRepository(
        context = app,
        audioBookDao = dao.audioBookDao,
        apiService = app.apiService,
        localListeningSessionDao = emptyDao(),
        localBookmarkDao = emptyDao(),
        playbackProgressDao = emptyDao(),
    )
    private val libraryRepository = LibraryRepository(
        libraryDao = localLibraryDao(),
        audioBookDao = dao.audioBookDao,
        audioBookRepository = audioBookRepository,
        apiService = app.apiService,
    )
    val viewModel = LibraryViewModel(
        libraryRepository = libraryRepository,
        audioBookRepository = audioBookRepository,
        apiService = app.apiService,
        connectivityMonitor = app.connectivityMonitor,
        settingsManager = app.settingsManager,
        entitlements = app.entitlementRepository,
        localFolderAccess = LocalFolderAccess(app),
    )

    suspend fun awaitInitialShelf() {
        awaitState { it.selectedLibrary?.id == FixtureLibraries.alpha.id && it.filteredBooks.isNotEmpty() }
    }

    suspend fun awaitState(predicate: (LibraryViewModel.UiState) -> Boolean): LibraryViewModel.UiState =
        withTimeout(5_000) { viewModel.uiState.filter(predicate).first() }
}

private object FixtureLibraries {
    val alpha = Library(id = "alpha", name = "Alpha", isLocal = true)
    val beta = Library(id = "beta", name = "Beta", isLocal = true)
}

private class DelayedLibraryFilterDao {
    private val delayedQueryStarted = CountDownLatch(1)
    @Volatile private var delayNextQuery = false
    @Volatile private var delayedContinuation: Continuation<List<RecentlyPlayedResult>>? = null

    val audioBookDao: AudioBookDao = proxy { method, args ->
        when (method.name) {
            "getFilteredBooks" -> filteredBooks(args)
            "countByLibrary" -> 2
            "getDistinctSeries", "getDistinctAuthors", "getDistinctGenresJson" -> emptyList<String>()
            "toString" -> "DelayedLibraryFilterDao"
            "hashCode" -> System.identityHashCode(this)
            "equals" -> args?.singleOrNull() === this
            else -> error("Unexpected AudioBookDao call: ${method.name}")
        }
    }

    fun delayNextFilteredQuery() {
        delayNextQuery = true
    }

    fun awaitDelayedQuery(): Boolean = delayedQueryStarted.await(5, TimeUnit.SECONDS)

    fun releaseDelayedQuery() {
        val continuation = requireNotNull(delayedContinuation) { "No delayed query to release" }
        delayedContinuation = null
        continuation.resumeWith(Result.success(listOf(alphaResult())))
    }

    private fun filteredBooks(args: Array<Any?>?): Any {
        if (!delayNextQuery) return listOf(betaResult())

        delayNextQuery = false
        @Suppress("UNCHECKED_CAST")
        val continuation = args?.lastOrNull() as? Continuation<List<RecentlyPlayedResult>>
            ?: error("Room suspend continuation missing from getFilteredBooks")
        delayedContinuation = continuation
        delayedQueryStarted.countDown()
        return COROUTINE_SUSPENDED
    }

    private fun betaResult() = RecentlyPlayedResult(
        audioBook = AudioBookEntity(
            id = "beta-book",
            libraryId = FixtureLibraries.beta.id,
            isLocal = 0,
            title = "Beta Book",
            author = "Author",
        ),
        lastPlayedAt = null,
    )

    private fun alphaResult() = RecentlyPlayedResult(
        audioBook = AudioBookEntity(
            id = "alpha-book",
            libraryId = FixtureLibraries.alpha.id,
            isLocal = 0,
            title = "Alpha Book",
            author = "Author",
        ),
        lastPlayedAt = null,
    )
}

private fun localLibraryDao(): LibraryDao = proxy { method, args ->
    when (method.name) {
        "getLocal" -> listOf(
            LibraryEntity(id = FixtureLibraries.alpha.id, name = FixtureLibraries.alpha.name, isLocal = 1),
            LibraryEntity(id = FixtureLibraries.beta.id, name = FixtureLibraries.beta.name, isLocal = 1),
        )
        "toString" -> "FixtureLibraryDao"
        "hashCode" -> System.identityHashCode(method)
        "equals" -> args?.singleOrNull() === method
        else -> error("Unexpected LibraryDao call: ${method.name}")
    }
}

private inline fun <reified T> emptyDao(): T = proxy { method, _ ->
    error("Unexpected ${T::class.simpleName} call: ${method.name}")
}

private inline fun <reified T> proxy(
    crossinline handler: (java.lang.reflect.Method, Array<Any?>?) -> Any?,
): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
    InvocationHandler { _, method, args -> handler(method, args) },
) as T
