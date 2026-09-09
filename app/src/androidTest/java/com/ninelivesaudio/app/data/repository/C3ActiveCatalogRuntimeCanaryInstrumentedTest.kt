package com.ninelivesaudio.app.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.data.local.AppDatabase
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.data.local.entity.LibraryEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.data.remote.ActiveRemoteScope
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.remote.AudiobookshelfApi
import com.ninelivesaudio.app.data.remote.AuthInterceptor
import com.ninelivesaudio.app.data.remote.FrozenBearer
import com.ninelivesaudio.app.data.remote.FrozenRemoteRequest
import com.ninelivesaudio.app.data.remote.RemoteOwner
import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.data.remote.RemoteTarget
import com.ninelivesaudio.app.data.remote.ServerRoute
import com.ninelivesaudio.app.data.remote.dto.ApiMeResponse
import com.ninelivesaudio.app.data.remote.dto.LibrariesResponse
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.service.MediaBrowseTree
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.service.autoBrowseChildCount
import com.ninelivesaudio.app.service.browseBooksForAuto
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import retrofit2.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime canary for owner-scoped Room catalog reads. The only fake is the
 * transport boundary needed to establish a real fresh B [ActiveRemoteScope].
 */
@RunWith(AndroidJUnit4::class)
class C3ActiveCatalogRuntimeCanaryInstrumentedTest {

    private lateinit var database: AppDatabase
    private lateinit var fixtureContext: CatalogFixtureContext
    private lateinit var settings: SettingsManager
    private lateinit var apiService: ApiService
    private lateinit var audioBooks: AudioBookRepository
    private lateinit var libraries: LibraryRepository

    @Before
    fun setUp() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        fixtureContext = CatalogFixtureContext(targetContext)
        database = Room.inMemoryDatabaseBuilder(
            targetContext,
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        settings = SettingsManager(fixtureContext)
        apiService = ApiService(
            api = fixtureApi(ownerId = OWNER_B),
            authInterceptor = AuthInterceptor(),
            settingsManager = settings,
        )
        audioBooks = AudioBookRepository(
            context = fixtureContext,
            audioBookDao = database.audioBookDao(),
            apiService = apiService,
            localListeningSessionDao = database.localListeningSessionDao(),
            localBookmarkDao = database.localBookmarkDao(),
            playbackProgressDao = database.playbackProgressDao(),
        )
        libraries = LibraryRepository(
            libraryDao = database.libraryDao(),
            audioBookDao = database.audioBookDao(),
            audioBookRepository = audioBooks,
            apiService = apiService,
        )
    }

    @After
    fun tearDown() {
        database.close()
        fixtureContext.clearFixtureStorage()
    }

    @Test
    fun freshBLoginShowsOnlyBOwnedCatalogAcrossRepositoryHomeAndAutoReads() = runBlocking {
        val rows = seedCrossOwnerCatalog()
        apiService.awaitAuthReady()
        assertTrue(apiService.loginWithToken(BASE_URL, TOKEN_B))
        val scopeB = requireNotNull(apiService.captureActiveRemoteScope())

        assertNull("Fresh B credentials must not claim ownerless rows", settings.getLegacyRemoteCacheState())
        assertEquals(rows.rawLibrary, database.libraryDao().getById(rows.rawLibrary)?.id)
        assertEquals(rows.rawBook, database.audioBookDao().getById(rows.rawBook)?.id)

        assertEquals(listOf(rows.bLibrary), libraries.getAudiobookshelf().map { it.id })
        assertNull(libraries.getById(rows.rawLibrary))
        assertEquals(rows.bLibrary, libraries.getById(rows.bLibrary)?.id)

        assertEquals(
            setOf(rows.bBook, rows.localBook),
            audioBooks.getAll().mapTo(mutableSetOf()) { it.id },
        )
        assertEquals(
            setOf(rows.bBook, rows.localBook),
            audioBooks.observeAll().first().mapTo(mutableSetOf()) { it.id },
        )
        assertEquals(rows.bBook, audioBooks.getById(rows.bBook)?.id)
        assertEquals(rows.bBook, audioBooks.observeById(rows.bBook).first()?.id)
        assertNull(audioBooks.getById(rows.rawBook))
        assertNull(audioBooks.getById(rows.bBookWithRawLibrary))
        assertNull(audioBooks.getById(rows.bBookWithForeignLibrary))
        assertNull(audioBooks.observeById(rows.bBookWithRawLibrary).first())

        assertEquals(listOf(rows.bBook), audioBooks.search("Canary").map { it.id })
        assertEquals(listOf(rows.bBook), audioBooks.getByLibrary(rows.bLibrary).map { it.id })
        assertEquals(
            listOf(rows.bBook),
            audioBooks.getFilteredBooks(rows.bLibrary, searchQuery = "Canary").map { it.id },
        )
        assertEquals(
            listOf(rows.bBook),
            audioBooks.observeRecentlyPlayed().first().map { it.first.id },
        )
        assertEquals(
            listOf(rows.bBook),
            audioBooks.getRecentlyPlayedByLibrary(rows.bLibrary).map { it.first.id },
        )

        val selectedB = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            selectedLibraryId = rows.bLibrary,
        )
        assertEquals(
            1,
            autoBrowseChildCount(
                parentId = MediaBrowseTree.LIBRARY_ID,
                canBrowse = true,
                activeLibraryId = rows.bLibrary,
                activeIsLocal = false,
                countRecent = audioBooks::countRecentlyPlayedForAuto,
                countLibrary = audioBooks::countForAuto,
                countDownloaded = audioBooks::countDownloadedForAuto,
            ),
        )
        assertEquals(
            listOf(rows.bBook),
            browseBooksForAuto(audioBooks.getByLibrary(rows.bLibrary), selectedB).map { it.id },
        )
        assertEquals(scopeB.encodeIncoming(RAW_LIBRARY_ID), rows.bLibrary)
    }

    @Test
    fun completeEmptyBSyncDeletesOnlyValidBOwnedCatalogRows() = runBlocking {
        val rows = seedCrossOwnerCatalog()
        apiService.awaitAuthReady()
        assertTrue(apiService.loginWithToken(BASE_URL, TOKEN_B))

        val result = libraries.syncFromServer()

        assertTrue(result is RemoteResult.Ok)
        assertNull(database.libraryDao().getById(rows.bLibrary))
        assertNull(database.audioBookDao().getById(rows.bBook))

        val remainingLibraryIds = database.libraryDao().getAll().mapTo(mutableSetOf()) { it.id }
        assertEquals(
            setOf(rows.rawLibrary, rows.aLibrary, rows.localLibrary, rows.malformedBLibrary),
            remainingLibraryIds,
        )
        val remainingBookIds = database.audioBookDao().getAll().mapTo(mutableSetOf()) { it.id }
        assertEquals(
            setOf(
                rows.rawBook,
                rows.aBook,
                rows.localBook,
                rows.bBookWithRawLibrary,
                rows.bBookWithForeignLibrary,
                rows.malformedBBook,
            ),
            remainingBookIds,
        )
        assertFalse(remainingLibraryIds.contains(rows.bLibrary))
        assertFalse(remainingBookIds.contains(rows.bBook))
    }

    private suspend fun seedCrossOwnerCatalog(): CatalogRows {
        val a = scope(OWNER_A)
        val b = scope(OWNER_B)
        val rows = CatalogRows(
            rawLibrary = RAW_LIBRARY_ID,
            aLibrary = a.encodeIncoming(RAW_LIBRARY_ID),
            bLibrary = b.encodeIncoming(RAW_LIBRARY_ID),
            localLibrary = LOCAL_LIBRARY_ID,
            malformedBLibrary = b.idPrefix + "not-base64",
            rawBook = RAW_BOOK_ID,
            aBook = a.encodeIncoming(RAW_BOOK_ID),
            bBook = b.encodeIncoming(RAW_BOOK_ID),
            localBook = LOCAL_BOOK_ID,
            bBookWithRawLibrary = b.encodeIncoming("book-with-raw-library"),
            bBookWithForeignLibrary = b.encodeIncoming("book-with-foreign-library"),
            malformedBBook = b.idPrefix + "not-base64",
        )
        database.libraryDao().upsertAll(
            listOf(
                LibraryEntity(rows.rawLibrary, "Raw legacy A"),
                LibraryEntity(rows.aLibrary, "Owner A"),
                LibraryEntity(rows.bLibrary, "Owner B"),
                LibraryEntity(rows.localLibrary, "Local", isLocal = 1),
                LibraryEntity(rows.malformedBLibrary, "Malformed B"),
            ),
        )
        database.audioBookDao().upsertAll(
            listOf(
                AudioBookEntity(rows.rawBook, rows.rawLibrary, isLocal = 0, title = "Raw legacy A"),
                AudioBookEntity(rows.aBook, rows.aLibrary, isLocal = 0, title = "Owner A"),
                AudioBookEntity(rows.bBook, rows.bLibrary, isLocal = 0, title = "Canary B"),
                AudioBookEntity(rows.localBook, rows.localLibrary, isLocal = 1, title = "Local book"),
                AudioBookEntity(rows.bBookWithRawLibrary, rows.rawLibrary, isLocal = 0, title = "Raw library envelope"),
                AudioBookEntity(rows.bBookWithForeignLibrary, rows.aLibrary, isLocal = 0, title = "Foreign library envelope"),
                AudioBookEntity(rows.malformedBBook, rows.bLibrary, isLocal = 0, title = "Canary malformed"),
            ),
        )
        database.playbackProgressDao().upsert(
            PlaybackProgressEntity(
                audioBookId = rows.bBook,
                positionSeconds = 90.0,
                updatedAt = "2026-09-08T00:00:00Z",
            ),
        )
        return rows
    }

    private fun scope(ownerId: String): ActiveRemoteScope {
        val route = requireNotNull(ServerRoute.parse(BASE_URL))
        val target = RemoteTarget(RemoteOwner(route, ownerId), authGeneration = 1)
        return ActiveRemoteScope(
            FrozenRemoteRequest(
                route = route,
                owner = target,
                bearer = FrozenBearer("fixture-$ownerId", route, authGeneration = 1),
                routeRevision = 1,
            ),
        )
    }

    private fun fixtureApi(ownerId: String): AudiobookshelfApi = Proxy.newProxyInstance(
        AudiobookshelfApi::class.java.classLoader,
        arrayOf(AudiobookshelfApi::class.java),
        InvocationHandler { proxy, method, args ->
            when (method.name) {
                "authorize" -> Response.success(Unit)
                "getMe" -> Response.success(ApiMeResponse(id = ownerId))
                "getLibraries" -> Response.success(LibrariesResponse())
                "toString" -> "C3ActiveCatalogFixtureApi"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                else -> error("Unexpected fixture API call: ${method.name}")
            }
        },
    ) as AudiobookshelfApi

    private data class CatalogRows(
        val rawLibrary: String,
        val aLibrary: String,
        val bLibrary: String,
        val localLibrary: String,
        val malformedBLibrary: String,
        val rawBook: String,
        val aBook: String,
        val bBook: String,
        val localBook: String,
        val bBookWithRawLibrary: String,
        val bBookWithForeignLibrary: String,
        val malformedBBook: String,
    )

    private companion object {
        const val BASE_URL = "https://abs.example.test"
        const val OWNER_A = "A"
        const val OWNER_B = "B"
        const val TOKEN_B = "token-b"
        const val RAW_LIBRARY_ID = "library-1"
        const val RAW_BOOK_ID = "book-1"
        const val LOCAL_LIBRARY_ID = "local-library"
        const val LOCAL_BOOK_ID = "local-book"
    }
}

private class CatalogFixtureContext(
    baseContext: Context,
) : ContextWrapper(baseContext) {
    private val sharedPreferenceNames = linkedSetOf<String>()
    private val fixtureFilesDir = File(baseContext.cacheDir, "c3-active-catalog-canary")

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        sharedPreferenceNames += name
        return baseContext.getSharedPreferences("c3-active-catalog-canary-$name", mode)
    }

    override fun deleteSharedPreferences(name: String): Boolean =
        baseContext.deleteSharedPreferences("c3-active-catalog-canary-$name")

    override fun getFilesDir(): File = fixtureFilesDir.apply { mkdirs() }

    override fun getFileStreamPath(name: String): File = File(filesDir, name)

    override fun deleteFile(name: String): Boolean = getFileStreamPath(name).delete()

    fun clearFixtureStorage() {
        sharedPreferenceNames.forEach(::deleteSharedPreferences)
        deleteFile("NineLivesAudio/settings.json")
        deleteFile("NineLivesAudio")
    }
}
