package com.ninelivesaudio.app.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.data.local.AppDatabase
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.data.local.entity.DownloadItemEntity
import com.ninelivesaudio.app.data.local.entity.LibraryEntity
import com.ninelivesaudio.app.data.local.entity.PendingProgressEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.data.remote.ActiveRemoteScope
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.remote.AudiobookshelfApi
import com.ninelivesaudio.app.data.remote.AuthInterceptor
import com.ninelivesaudio.app.data.remote.FrozenBearer
import com.ninelivesaudio.app.data.remote.FrozenRemoteRequest
import com.ninelivesaudio.app.data.remote.LegacyRemoteCacheState
import com.ninelivesaudio.app.data.remote.RemoteOwner
import com.ninelivesaudio.app.data.remote.RemoteTarget
import com.ninelivesaudio.app.data.remote.ServerRoute
import com.ninelivesaudio.app.data.remote.StoredAuthRecord
import com.ninelivesaudio.app.data.remote.dto.ApiMeResponse
import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.entitlement.EntitlementCachePrefs
import com.ninelivesaudio.app.service.LegacyRemoteCacheClaimCoordinator
import com.ninelivesaudio.app.service.SettingsManager
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

/**
 * RED canary for the guarded C3c legacy-cache claim. The transport proxy only
 * answers /api/authorize and /api/me. Room, settings, API scope capture,
 * repositories, and the coordinator are real production paths.
 */
@RunWith(AndroidJUnit4::class)
class C3LegacyCacheClaimRuntimeCanaryInstrumentedTest {

    private lateinit var database: AppDatabase
    private lateinit var fixtureContext: ClaimFixtureContext
    private lateinit var settings: SettingsManager
    private lateinit var apiService: ApiService
    private lateinit var fixtureApi: ClaimFixtureApi
    private lateinit var cache: EntitlementCachePrefs
    private lateinit var audioBooks: AudioBookRepository
    private lateinit var libraries: LibraryRepository

    @Before
    fun setUp() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        fixtureContext = ClaimFixtureContext(targetContext)
        database = Room.inMemoryDatabaseBuilder(
            targetContext,
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        settings = SettingsManager(fixtureContext)
        fixtureApi = ClaimFixtureApi()
        apiService = ApiService(fixtureApi.proxy, AuthInterceptor(), settings)
        cache = EntitlementCachePrefs(fixtureContext)
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
    fun restoredAClaimMovesCompleteGraphAndActiveReadsUseAWithoutNetwork() = runBlocking {
        val scope = restoreA()
        val rows = seedValidGraph(scope)
        val coordinator = coordinator()

        assertTrue("Missing C3c coordinator claim", coordinator.claimIfEligible())

        val expectedLibrary = scope.encodeIncoming(rows.rawLibrary)
        val expectedBook = scope.encodeIncoming(rows.rawBook)
        val expectedDownload = scope.encodeIncoming(rows.rawDownload)
        val expectedProgress = scope.encodeIncoming(rows.rawBook)
        assertNull(database.libraryDao().getById(rows.rawLibrary))
        assertNull(database.audioBookDao().getById(rows.rawBook))
        assertNull(database.downloadItemDao().getById(rows.rawDownload))
        assertNull(database.playbackProgressDao().getByAudioBookId(rows.rawBook))
        assertEquals(expectedLibrary, database.libraryDao().getById(expectedLibrary)?.id)
        assertEquals(expectedBook, database.audioBookDao().getById(expectedBook)?.id)
        assertEquals(expectedLibrary, database.audioBookDao().getById(expectedBook)?.libraryId)
        assertEquals(expectedDownload, database.downloadItemDao().getById(expectedDownload)?.id)
        assertEquals(expectedBook, database.downloadItemDao().getById(expectedDownload)?.audioBookId)
        assertEquals(expectedProgress, database.playbackProgressDao().getByAudioBookId(expectedProgress)?.audioBookId)
        assertTrue(
            database.libraryDao().getById(expectedLibrary)?.foldersJson
                ?.contains("\"LibraryId\":\"$expectedLibrary\"") == true,
        )
        assertEquals(expectedLibrary, settings.currentSettings.selectedLibraryId)
        assertEquals(expectedBook, cache.slotWinnerAudioBookId)
        assertEquals(LegacyRemoteCacheState.Claimed, settings.getLegacyRemoteCacheState())

        assertEquals(listOf(expectedLibrary), libraries.getAudiobookshelf().map { it.id })
        assertEquals(expectedBook, audioBooks.getById(expectedBook)?.id)
        assertNull(audioBooks.getById(rows.rawBook))
    }

    @Test
    fun foreignMalformedOrphanLocalAndPendingRowsRemainByteForByteQuarantined() = runBlocking {
        val scope = restoreA()
        val rows = seedQuarantineGraph(scope)
        val before = snapshot()

        assertFalse(coordinator().claimIfEligible())

        assertEquals(before, snapshot())
        assertEquals(1, database.pendingProgressDao().getLegacyUnowned().size)
    }

    @Test
    fun explicitFreshBLoginQuarantinesAAndCannotClaimMatchingRawIds() = runBlocking {
        val scope = restoreA()
        val rows = seedValidGraph(scope)
        val selectedBefore = settings.currentSettings.selectedLibraryId
        val slotBefore = cache.slotWinnerAudioBookId
        fixtureApi.accountId = OWNER_B

        assertTrue(apiService.loginWithToken(BASE_URL, TOKEN_B))
        assertEquals(LegacyRemoteCacheState.Quarantined, settings.getLegacyRemoteCacheState())
        assertFalse(coordinator().claimIfEligible())

        assertEquals(selectedBefore, settings.currentSettings.selectedLibraryId)
        assertEquals(slotBefore, cache.slotWinnerAudioBookId)
        assertEquals(rows.rawLibrary, database.libraryDao().getById(rows.rawLibrary)?.id)
        assertEquals(rows.rawBook, database.audioBookDao().getById(rows.rawBook)?.id)
        assertNull(database.libraryDao().getById(scope.encodeIncoming(rows.rawLibrary)))
        assertNull(database.audioBookDao().getById(scope.encodeIncoming(rows.rawBook)))
        assertTrue(libraries.getAudiobookshelf().isEmpty())
    }

    @Test
    fun destinationCollisionRollsBackRoomAndLeavesPreferencesAndMarkerPending() = runBlocking {
        val scope = restoreA()
        val rows = seedValidGraph(scope)
        val targetLibrary = scope.encodeIncoming(rows.rawLibrary)
        database.libraryDao().upsert(LibraryEntity(targetLibrary, "Existing A target"))
        val before = snapshot()

        assertFalse(coordinator().claimIfEligible())

        assertEquals(before, snapshot())
        assertEquals(LegacyRemoteCacheState.PendingRestoredOwner(BASE_URL, OWNER_A), settings.getLegacyRemoteCacheState())
    }

    @Test
    fun roomOnlyRetryRepairsBothPreferencesThenSecondClaimIsNoOp() = runBlocking {
        val scope = restoreA()
        val rows = seedConvertedGraph(scope)
        val beforeRows = roomSnapshot()

        assertTrue(coordinator().claimIfEligible())
        assertEquals(scope.encodeIncoming(rows.rawLibrary), settings.currentSettings.selectedLibraryId)
        assertEquals(scope.encodeIncoming(rows.rawBook), cache.slotWinnerAudioBookId)
        assertEquals(LegacyRemoteCacheState.Claimed, settings.getLegacyRemoteCacheState())
        assertEquals(beforeRows, roomSnapshot())

        assertFalse(coordinator().claimIfEligible())
        assertEquals(beforeRows, roomSnapshot())
        assertEquals(LegacyRemoteCacheState.Claimed, settings.getLegacyRemoteCacheState())
    }

    @Test
    fun checkedSlotCommitFailureLeavesConvertedRoomRowsAndExactPendingMarker() = runBlocking {
        val scope = restoreA()
        val rows = seedConvertedGraph(scope)
        val basePrefs = fixtureContext.getSharedPreferences("c3-slot-failure", Context.MODE_PRIVATE)
        basePrefs.edit().putString(EntitlementCachePrefs.KEY_SLOT_WINNER, rows.rawBook).commit()
        val failingCache = EntitlementCachePrefs(FailingCommitPreferences(basePrefs))
        val beforeRows = roomSnapshot()

        assertFalse(coordinator(failingCache).claimIfEligible())
        assertEquals(beforeRows, roomSnapshot())
        assertEquals(rows.rawBook, basePrefs.getString(EntitlementCachePrefs.KEY_SLOT_WINNER, null))
        assertEquals(LegacyRemoteCacheState.PendingRestoredOwner(BASE_URL, OWNER_A), settings.getLegacyRemoteCacheState())
    }

    @Test
    fun claimAndBWinCommitBoundaryFixturesNeverPublishStaleA() = runBlocking {
        runClaimWinsSelectedLibrary()
        resetForBoundaryFixture()
        runBWinSelectedLibrary()
        resetForBoundaryFixture()
        runClaimWinsSlot()
        resetForBoundaryFixture()
        runBWinSlot()
        resetForBoundaryFixture()
        runClaimWinsMarker()
        resetForBoundaryFixture()
        runBWinMarker()
    }

    private suspend fun runClaimWinsSelectedLibrary() = coroutineScope {
        val scope = restoreA()
        seedConvertedGraph(scope)
        val backing = encryptedPreferences(settings)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        replaceEncryptedPreferences(settings, BlockingCommitPreferences(backing, "app_settings", entered, release))
        val claim = async(Dispatchers.IO) { coordinator().claimIfEligible() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        fixtureApi.accountId = OWNER_B
        val login = async(Dispatchers.IO) { apiService.loginWithToken(BASE_URL, TOKEN_B) }
        release.countDown()
        claim.await()
        assertTrue(login.await())
        assertEquals(scope.encodeIncoming(RAW_LIBRARY_ID), settings.currentSettings.selectedLibraryId)
        assertEquals(LegacyRemoteCacheState.Quarantined, settings.getLegacyRemoteCacheState())
    }

    private suspend fun runBWinSelectedLibrary() = coroutineScope {
        val scope = restoreA()
        seedConvertedGraph(scope)
        val backing = encryptedPreferences(settings)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val monitorEntered = CountDownLatch(1)
        replaceEncryptedPreferences(settings, BlockingReadPreferences(backing, "app_settings", entered, release))
        resetSerializedSettingsLoaded(settings)
        apiService.setCommittedScopeMonitorObserverForTest { monitorEntered.countDown() }
        val claim = async(Dispatchers.IO) { coordinator().claimIfEligible() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        fixtureApi.accountId = OWNER_B
        val login = async(Dispatchers.IO) { apiService.loginWithToken(BASE_URL, TOKEN_B) }
        assertTrue(monitorEntered.await(10, TimeUnit.SECONDS))
        release.countDown()
        assertFalse(claim.await())
        assertTrue(login.await())
        apiService.setCommittedScopeMonitorObserverForTest(null)
        assertEquals(LegacyRemoteCacheState.Quarantined, settings.getLegacyRemoteCacheState())
    }

    private suspend fun runClaimWinsSlot() = coroutineScope {
        val scope = restoreA()
        seedConvertedGraph(scope)
        val basePrefs = fixtureContext.getSharedPreferences("c3-slot-claim-wins", Context.MODE_PRIVATE)
        basePrefs.edit().putString(EntitlementCachePrefs.KEY_SLOT_WINNER, RAW_BOOK_ID).commit()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingCache = EntitlementCachePrefs(BlockingCommitPreferences(basePrefs, EntitlementCachePrefs.KEY_SLOT_WINNER, entered, release))
        val claim = async(Dispatchers.IO) { coordinator(blockingCache).claimIfEligible() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        fixtureApi.accountId = OWNER_B
        val login = async(Dispatchers.IO) { apiService.loginWithToken(BASE_URL, TOKEN_B) }
        release.countDown()
        claim.await()
        assertTrue(login.await())
        assertEquals(scope.encodeIncoming(RAW_BOOK_ID), blockingCache.slotWinnerAudioBookId)
        assertEquals(LegacyRemoteCacheState.Quarantined, settings.getLegacyRemoteCacheState())
    }

    private suspend fun runBWinSlot() = coroutineScope {
        val scope = restoreA()
        seedConvertedGraph(scope)
        val basePrefs = fixtureContext.getSharedPreferences("c3-slot-b-wins", Context.MODE_PRIVATE)
        basePrefs.edit().putString(EntitlementCachePrefs.KEY_SLOT_WINNER, RAW_BOOK_ID).commit()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingCache = EntitlementCachePrefs(BlockingReadPreferences(basePrefs, EntitlementCachePrefs.KEY_SLOT_WINNER, entered, release))
        val claim = async(Dispatchers.IO) { coordinator(blockingCache).claimIfEligible() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        fixtureApi.accountId = OWNER_B
        val login = async(Dispatchers.IO) { apiService.loginWithToken(BASE_URL, TOKEN_B) }
        assertTrue(login.await())
        release.countDown()
        assertFalse(claim.await())
        assertEquals(LegacyRemoteCacheState.Quarantined, settings.getLegacyRemoteCacheState())
    }

    private suspend fun runClaimWinsMarker() = coroutineScope {
        val scope = restoreA()
        seedConvertedGraph(scope)
        val backing = encryptedPreferences(settings)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        replaceEncryptedPreferences(settings, BlockingCommitPreferences(backing, "legacy_remote_cache_state", entered, release))
        val claim = async(Dispatchers.IO) { coordinator().claimIfEligible() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        fixtureApi.accountId = OWNER_B
        val login = async(Dispatchers.IO) { apiService.loginWithToken(BASE_URL, TOKEN_B) }
        release.countDown()
        assertTrue(claim.await())
        assertTrue(login.await())
        assertEquals(LegacyRemoteCacheState.Quarantined, settings.getLegacyRemoteCacheState())
    }

    private suspend fun runBWinMarker() = coroutineScope {
        val scope = restoreA()
        seedConvertedGraph(scope)
        val backing = encryptedPreferences(settings)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val monitorEntered = CountDownLatch(1)
        replaceEncryptedPreferences(settings, BlockingReadPreferences(backing, "legacy_remote_cache_state", entered, release))
        apiService.setCommittedScopeMonitorObserverForTest { monitorEntered.countDown() }
        val claim = async(Dispatchers.IO) { coordinator().claimIfEligible() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        fixtureApi.accountId = OWNER_B
        val login = async(Dispatchers.IO) { apiService.loginWithToken(BASE_URL, TOKEN_B) }
        assertTrue(monitorEntered.await(10, TimeUnit.SECONDS))
        release.countDown()
        assertFalse(claim.await())
        val intermediateMarker = settings.getLegacyRemoteCacheState()
        assertTrue(
            intermediateMarker == LegacyRemoteCacheState.PendingRestoredOwner(BASE_URL, OWNER_A) ||
                intermediateMarker == LegacyRemoteCacheState.Quarantined,
        )
        assertTrue(login.await())
        apiService.setCommittedScopeMonitorObserverForTest(null)
        assertEquals(LegacyRemoteCacheState.Quarantined, settings.getLegacyRemoteCacheState())
    }

    private suspend fun restoreA(): ActiveRemoteScope {
        settings.saveSettings(
            AppSettings(
                appMode = AppMode.AUDIOBOOKSHELF,
                serverUrl = BASE_URL,
                selectedLibraryId = RAW_LIBRARY_ID,
            ),
        )
        settings.saveAuthToken(TOKEN_A, BASE_URL, OWNER_A)
        apiService.initializeFromSettings()
        return requireNotNull(apiService.captureActiveRemoteScope())
    }

    private suspend fun seedValidGraph(scope: ActiveRemoteScope): GraphRows {
        val rows = GraphRows()
        database.libraryDao().upsert(
            LibraryEntity(
                id = rows.rawLibrary,
                name = "Raw legacy library",
                foldersJson = "[{\"Id\":\"folder-1\",\"FullPath\":\"/books\",\"LibraryId\":\"${rows.rawLibrary}\"}]",
            ),
        )
        database.audioBookDao().upsert(AudioBookEntity(rows.rawBook, rows.rawLibrary, title = "Raw legacy book"))
        database.playbackProgressDao().upsert(PlaybackProgressEntity(rows.rawBook, positionSeconds = 90.0, updatedAt = NOW))
        database.downloadItemDao().upsert(DownloadItemEntity(rows.rawDownload, rows.rawBook, "Raw download", startedAt = NOW))
        cache.slotWinnerAudioBookId = rows.rawBook
        return rows
    }

    private suspend fun seedConvertedGraph(scope: ActiveRemoteScope): GraphRows {
        val rows = GraphRows()
        database.libraryDao().upsert(
            LibraryEntity(
                id = scope.encodeIncoming(rows.rawLibrary),
                name = "Converted A library",
                foldersJson = "[{\"Id\":\"folder-1\",\"FullPath\":\"/books\",\"LibraryId\":\"${scope.encodeIncoming(rows.rawLibrary)}\"}]",
            ),
        )
        database.audioBookDao().upsert(
            AudioBookEntity(scope.encodeIncoming(rows.rawBook), scope.encodeIncoming(rows.rawLibrary), title = "Converted A book"),
        )
        database.playbackProgressDao().upsert(
            PlaybackProgressEntity(scope.encodeIncoming(rows.rawBook), positionSeconds = 90.0, updatedAt = NOW),
        )
        database.downloadItemDao().upsert(
            DownloadItemEntity(scope.encodeIncoming(rows.rawDownload), scope.encodeIncoming(rows.rawBook), "Converted A download", startedAt = NOW),
        )
        cache.slotWinnerAudioBookId = rows.rawBook
        return rows
    }

    private suspend fun seedQuarantineGraph(scope: ActiveRemoteScope): GraphRows {
        val rows = seedValidGraph(scope)
        val foreign = manualScope(OWNER_B)
        database.libraryDao().upsert(LibraryEntity(foreign.encodeIncoming(rows.rawLibrary), "Foreign B"))
        database.libraryDao().upsert(LibraryEntity("nlr1:malformed", "Malformed envelope"))
        database.libraryDao().upsert(LibraryEntity("raw-malformed-folders", "Malformed folders", foldersJson = "{broken"))
        database.libraryDao().upsert(
            LibraryEntity(
                "raw-mismatched-folder",
                "Mismatched folders",
                foldersJson = "[{\"Id\":\"folder-2\",\"FullPath\":\"/other\",\"LibraryId\":\"different-library\"}]",
            ),
        )
        database.audioBookDao().upsert(AudioBookEntity("orphan-book", "missing-library", title = "Orphan"))
        database.audioBookDao().upsert(AudioBookEntity("nlr1:book-malformed", "nlr1:malformed", title = "Malformed envelope book"))
        database.playbackProgressDao().upsert(PlaybackProgressEntity("orphan-book", positionSeconds = 12.0, updatedAt = NOW))
        database.downloadItemDao().upsert(DownloadItemEntity("orphan-download", "orphan-book", "Orphan download", startedAt = NOW))
        database.libraryDao().upsert(LibraryEntity("local-library", "Local", isLocal = 1))
        database.audioBookDao().upsert(AudioBookEntity("local-book", "local-library", isLocal = 1, title = "Local book"))
        database.pendingProgressDao().insert(PendingProgressEntity(itemId = rows.rawBook, timestamp = NOW))
        return rows
    }

    private fun coordinator(cache: EntitlementCachePrefs = this.cache): LegacyRemoteCacheClaimCoordinator =
        LegacyRemoteCacheClaimCoordinator(
            database = database,
            settingsManager = settings,
            apiService = apiService,
            entitlementCachePrefs = cache,
        )

    private suspend fun resetForBoundaryFixture() {
        database.clearAllTables()
        encryptedPreferences(settings).edit().remove("legacy_remote_cache_state").commit()
        settings.saveSettings(AppSettings(appMode = AppMode.AUDIOBOOKSHELF, serverUrl = BASE_URL, selectedLibraryId = RAW_LIBRARY_ID))
        settings.saveAuthToken(TOKEN_A, BASE_URL, OWNER_A)
        fixtureApi.accountId = OWNER_A
        apiService = ApiService(fixtureApi.proxy, AuthInterceptor(), settings)
        apiService.initializeFromSettings()
        cache.slotWinnerAudioBookId = null
    }

    private fun manualScope(ownerId: String): ActiveRemoteScope {
        val route = requireNotNull(ServerRoute.parse(BASE_URL))
        return ActiveRemoteScope(
            FrozenRemoteRequest(
                route = route,
                owner = RemoteTarget(RemoteOwner(route, ownerId), authGeneration = 1),
                bearer = FrozenBearer("fixture-$ownerId", route, authGeneration = 1),
                routeRevision = 1,
            ),
        )
    }

    private fun snapshot(): FixtureSnapshot = FixtureSnapshot(
        libraries = runBlocking { database.libraryDao().getAll() },
        books = runBlocking { database.audioBookDao().getAll() },
        progress = runBlocking { database.playbackProgressDao().getAll() },
        downloads = runBlocking { database.downloadItemDao().getAll() },
        pending = runBlocking { database.pendingProgressDao().getAll() },
        settings = settings.currentSettings,
        slot = cache.slotWinnerAudioBookId,
        marker = runBlocking { settings.getLegacyRemoteCacheState() },
    )

    private fun roomSnapshot(): RoomSnapshot = RoomSnapshot(
        libraries = runBlocking { database.libraryDao().getAll() },
        books = runBlocking { database.audioBookDao().getAll() },
        progress = runBlocking { database.playbackProgressDao().getAll() },
        downloads = runBlocking { database.downloadItemDao().getAll() },
    )

    private fun encryptedPreferences(manager: SettingsManager): SharedPreferences {
        val field = SettingsManager::class.java.getDeclaredField("encryptedPrefs" + "$" + "delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (field.get(manager) as Lazy<SharedPreferences>).value
    }

    private fun replaceEncryptedPreferences(manager: SettingsManager, replacement: SharedPreferences) {
        val field = SettingsManager::class.java.getDeclaredField("encryptedPrefs" + "$" + "delegate").apply { isAccessible = true }
        field.set(manager, lazyOf(replacement))
    }

    private fun resetSerializedSettingsLoaded(manager: SettingsManager) {
        val stateField = SettingsManager::class.java.getDeclaredField("serializedState").apply { isAccessible = true }
        val state = stateField.get(manager)
        state.javaClass.getDeclaredField("loaded").apply {
            isAccessible = true
            setBoolean(state, false)
        }
    }

    private data class GraphRows(
        val rawLibrary: String = RAW_LIBRARY_ID,
        val rawBook: String = RAW_BOOK_ID,
        val rawDownload: String = RAW_DOWNLOAD_ID,
    )

    private data class RoomSnapshot(
        val libraries: List<LibraryEntity>,
        val books: List<AudioBookEntity>,
        val progress: List<PlaybackProgressEntity>,
        val downloads: List<DownloadItemEntity>,
    )

    private data class FixtureSnapshot(
        val libraries: List<LibraryEntity>,
        val books: List<AudioBookEntity>,
        val progress: List<PlaybackProgressEntity>,
        val downloads: List<DownloadItemEntity>,
        val pending: List<PendingProgressEntity>,
        val settings: AppSettings,
        val slot: String?,
        val marker: LegacyRemoteCacheState?,
    )

    private companion object {
        const val BASE_URL = "https://abs.example.test"
        const val OWNER_A = "account-a"
        const val OWNER_B = "account-b"
        const val TOKEN_A = "token-a"
        const val TOKEN_B = "token-b"
        const val RAW_LIBRARY_ID = "library-1"
        const val RAW_BOOK_ID = "book-1"
        const val RAW_DOWNLOAD_ID = "download-1"
        const val NOW = "2026-09-09T00:00:00Z"
    }
}

private class ClaimFixtureApi {
    @Volatile var accountId: String = "account-a"

    val proxy: AudiobookshelfApi = Proxy.newProxyInstance(
        AudiobookshelfApi::class.java.classLoader,
        arrayOf(AudiobookshelfApi::class.java),
        InvocationHandler { proxy, method, args ->
            when (method.name) {
                "authorize" -> retrofitSuccess(Unit)
                "getMe" -> retrofitSuccess(ApiMeResponse(id = accountId))
                "toString" -> "C3LegacyClaimFixtureApi"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                else -> error("Unexpected fixture API call: ${method.name}")
            }
        },
    ) as AudiobookshelfApi

    private fun <T> retrofitSuccess(body: T): Response<T> = Response.success(
        body,
        okhttp3.Response.Builder()
            .request(Request.Builder().url("https://abs.example.test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .build(),
    )
}

private class ClaimFixtureContext(baseContext: Context) : ContextWrapper(baseContext) {
    private val sharedPreferenceNames = linkedSetOf<String>()
    private val fixtureFilesDir = File(baseContext.cacheDir, "c3-legacy-claim-canary")

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        sharedPreferenceNames += name
        return baseContext.getSharedPreferences("c3-legacy-claim-canary-$name", mode)
    }

    override fun deleteSharedPreferences(name: String): Boolean =
        baseContext.deleteSharedPreferences("c3-legacy-claim-canary-$name")

    override fun getFilesDir(): File = fixtureFilesDir.apply { mkdirs() }

    override fun getFileStreamPath(name: String): File = File(filesDir, name)

    override fun deleteFile(name: String): Boolean = getFileStreamPath(name).delete()

    fun clearFixtureStorage() {
        sharedPreferenceNames.forEach(::deleteSharedPreferences)
        deleteFile("NineLivesAudio/settings.json")
        deleteFile("NineLivesAudio")
    }
}

private open class DelegatingPreferences(
    protected val backing: SharedPreferences,
) : SharedPreferences by backing

private class FailingCommitPreferences(backing: SharedPreferences) : DelegatingPreferences(backing) {
    override fun edit(): SharedPreferences.Editor {
        val delegate = backing.edit()
        val writes = mutableSetOf<String>()
        return object : SharedPreferences.Editor by delegate {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                key?.let(writes::add)
                delegate.putString(key, value)
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                key?.let(writes::add)
                delegate.remove(key)
                return this
            }

            override fun commit(): Boolean =
                if (EntitlementCachePrefs.KEY_SLOT_WINNER in writes) false else delegate.commit()
        }
    }
}

private class BlockingCommitPreferences(
    backing: SharedPreferences,
    private val targetKey: String,
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
) : DelegatingPreferences(backing) {
    override fun edit(): SharedPreferences.Editor {
        val delegate = backing.edit()
        val writes = mutableSetOf<String>()
        return object : SharedPreferences.Editor by delegate {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                key?.let(writes::add)
                delegate.putString(key, value)
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                key?.let(writes::add)
                delegate.remove(key)
                return this
            }

            override fun commit(): Boolean {
                if (targetKey in writes) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                return delegate.commit()
            }
        }
    }
}

private class BlockingReadPreferences(
    backing: SharedPreferences,
    private val targetKey: String,
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
) : DelegatingPreferences(backing) {
    @Volatile private var armed = true

    override fun getString(key: String?, defValue: String?): String? {
        if (armed && key == targetKey) {
            armed = false
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        return backing.getString(key, defValue)
    }
}
