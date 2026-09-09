package com.ninelivesaudio.app.service.download

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.NineLivesApp
import com.ninelivesaudio.app.data.local.AppDatabase
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.remote.ActiveRemoteScope
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.remote.AudiobookshelfApi
import com.ninelivesaudio.app.data.remote.AuthInterceptor
import com.ninelivesaudio.app.data.remote.CredentialLoginResult
import com.ninelivesaudio.app.data.remote.dto.ApiUser
import com.ninelivesaudio.app.data.remote.dto.LoginRequest
import com.ninelivesaudio.app.data.remote.dto.LoginResponse
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.AudioFile
import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus
import com.ninelivesaudio.app.entitlement.DurableEntitlementStore
import com.ninelivesaudio.app.entitlement.EntitlementRepository
import com.ninelivesaudio.app.entitlement.EntitlementCachePrefs
import com.ninelivesaudio.app.entitlement.PlayEntitlementCache
import com.ninelivesaudio.app.entitlement.TrialReminderScheduler
import com.ninelivesaudio.app.service.DownloadManager
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.ui.downloads.DownloadsViewModel
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

/**
 * Runtime canary for the owner fence. It uses Room and the production download
 * stack, but its Retrofit boundary suspends a real A stream while the active
 * session changes from A to B and then back to A with a new bearer generation.
 */
@RunWith(AndroidJUnit4::class)
class OwnerScopedDownloadRuntimeCanaryTest {

    @Test
    fun A_to_B_to_A_switch_quarantines_foreign_rows_and_inflight_download() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as NineLivesApp
        val fixture = Fixture(instrumentation.targetContext, app)

        try {
            val scopeA1 = fixture.login(OWNER_A)
            val scopeB = fixture.login(OWNER_B)
            val scopeA2 = fixture.login(OWNER_A)
            assertEquals(scopeA1.idPrefix, scopeA2.idPrefix)

            val rawBook = fixture.book(id = RAW_BOOK_ID, title = "Raw legacy")
            val rawDownload = fixture.download(
                id = RAW_DOWNLOAD_ID,
                audioBookId = rawBook.id,
                status = DownloadStatus.Preparing,
            )
            val bookA = fixture.book(
                id = scopeA2.encodeIncoming(RAW_BOOK_ID),
                title = "A book",
                coverPath = "cover-for-a",
                files = listOf(AudioFile(ino = "a-ino", filename = FILE_NAME, size = 8)),
            )
            val downloadA = fixture.download(
                id = scopeA2.encodeIncoming(RAW_DOWNLOAD_ID),
                audioBookId = bookA.id,
                status = DownloadStatus.Queued,
            )
            val bookB = fixture.book(
                id = scopeB.encodeIncoming(RAW_BOOK_ID),
                title = "B book",
            )
            val downloadB = fixture.download(
                id = scopeB.encodeIncoming(RAW_DOWNLOAD_ID),
                audioBookId = bookB.id,
                status = DownloadStatus.Queued,
            )
            val localBook = fixture.book(id = LOCAL_BOOK_ID, title = "Local book", local = true)
            val localDownload = fixture.download(
                id = LOCAL_DOWNLOAD_ID,
                audioBookId = localBook.id,
                status = DownloadStatus.Queued,
            )
            fixture.seed(rawBook, rawDownload, bookA, downloadA, bookB, downloadB, localBook, localDownload)

            val aDirectory = File(
                fixture.downloadRoot,
                downloadFolderName(bookA.author, bookA.title, bookA.id),
            ).apply { mkdirs() }
            val partFile = File(aDirectory, "$FILE_NAME.part").apply { writeText(RETAINED_PART_BYTES) }
            val finalFile = File(aDirectory, FILE_NAME)
            val coverFile = File(aDirectory, "cover.jpg")

            val viewModelStore = ViewModelStore()
            val viewModel = fixture.createDownloadsViewModel(instrumentation, viewModelStore)
            try {
                val engineResult = async(Dispatchers.Default) {
                    fixture.engine.download(downloadA, bookA, scopeA2) { _, _, _ -> }
                }
                fixture.api.awaitAStream()
                val aAtStreamGate = requireNotNull(fixture.database.downloadItemDao().getById(downloadA.id))
                val rawAtStreamGate = requireNotNull(fixture.database.downloadItemDao().getById(rawDownload.id))
                assertEquals(DownloadStatus.Downloading.ordinal, aAtStreamGate.status)
                assertEquals(RETAINED_PART_BYTES, partFile.readText())
                assertFalse(finalFile.exists())
                assertFalse(coverFile.exists())

                val currentB = fixture.login(OWNER_B)
                assertEquals(scopeB.idPrefix, currentB.idPrefix)
                fixture.awaitActiveIds(viewModel, setOf(downloadB.id, localDownload.id))

                val candidates = fixture.slotStore.buildCandidates()
                assertEquals(setOf(bookB.id, localBook.id), candidates.map { it.audioBookId }.toSet())
                assertFalse(candidates.any { it.audioBookId == rawBook.id || it.audioBookId == bookA.id })

                fixture.database.downloadItemDao().upsert(
                    downloadB.copy(status = DownloadStatus.Preparing).toEntity(),
                )
                fixture.downloadManager.cleanupStrandedClaims()
                assertNull(fixture.database.downloadItemDao().getById(downloadB.id))
                assertEquals(rawAtStreamGate, fixture.database.downloadItemDao().getById(rawDownload.id))
                assertEquals(aAtStreamGate, fixture.database.downloadItemDao().getById(downloadA.id))

                val scopeA3 = fixture.login(OWNER_A)
                assertEquals(scopeA2.idPrefix, scopeA3.idPrefix)
                assertFalse(fixture.apiService.isCurrentActiveRemoteScope(scopeA2))
                assertTrue(fixture.apiService.isCurrentActiveRemoteScope(scopeA3))

                fixture.api.releaseAStream()
                assertEquals(DownloadStatus.Queued, engineResult.await().status)

                assertEquals(aAtStreamGate, fixture.database.downloadItemDao().getById(downloadA.id))
                assertEquals(rawAtStreamGate, fixture.database.downloadItemDao().getById(rawDownload.id))
                assertEquals(localDownload.toEntity(), fixture.database.downloadItemDao().getById(localDownload.id))
                assertEquals(RETAINED_PART_BYTES, partFile.readText())
                assertFalse(finalFile.exists())
                assertFalse(coverFile.exists())
                assertEquals(listOf(MediaCall.Stream(RAW_BOOK_ID)), fixture.api.mediaCalls)
            } finally {
                instrumentation.runOnMainSync { viewModelStore.clear() }
            }
        } finally {
            fixture.close()
        }
    }
}

private class Fixture(
    baseContext: Context,
    private val app: NineLivesApp,
) {
    private val context = DownloadFixtureContext(baseContext)
    val database: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val settings = SettingsManager(context)
    val api = GatedDownloadApi()
    val apiService = ApiService(api.service, AuthInterceptor(settings), settings)
    private val entitlements = EntitlementRepository(
        prefs = FreeEntitlementStore(),
        cache = FreeEntitlementCache(),
        reminderScheduler = TrialReminderScheduler { },
        nowEpochMs = { 0L },
    )
    val downloadRoot = File(context.filesDir, "downloads")
    private val cache = EntitlementCachePrefs(context)
    val slotStore = DownloadSlotStore(
        downloadItemDao = database.downloadItemDao(),
        audioBookDao = database.audioBookDao(),
        playbackProgressDao = database.playbackProgressDao(),
        entitlements = entitlements,
        cache = cache,
        apiService = apiService,
    )
    val engine = DownloadEngine(
        context = context,
        downloadItemDao = database.downloadItemDao(),
        audioBookDao = database.audioBookDao(),
        api = api.service,
        apiService = apiService,
        settingsManager = settings,
    )
    val downloadManager = DownloadManager(
        context = context,
        downloadItemDao = database.downloadItemDao(),
        audioBookDao = database.audioBookDao(),
        engine = engine,
        slotStore = slotStore,
        settingsManager = settings,
        connectivityMonitor = app.connectivityMonitor,
        apiService = apiService,
    )

    init {
        runBlocking {
            settings.saveSettings(
                AppSettings(
                    appMode = AppMode.AUDIOBOOKSHELF,
                    serverUrl = SERVER_URL,
                    downloadPath = downloadRoot.absolutePath,
                ),
            )
        }
    }

    suspend fun login(owner: String): ActiveRemoteScope {
        assertEquals(CredentialLoginResult.SUCCESS, apiService.login(SERVER_URL, owner, "fixture-password"))
        return requireNotNull(apiService.captureActiveRemoteScope())
    }

    suspend fun seed(vararg values: Any) {
        values.filterIsInstance<AudioBook>().let { database.audioBookDao().upsertAll(it.map(AudioBook::toEntity)) }
        values.filterIsInstance<DownloadItem>().forEach { database.downloadItemDao().upsert(it.toEntity()) }
    }

    fun book(
        id: String,
        title: String,
        local: Boolean = false,
        coverPath: String? = null,
        files: List<AudioFile> = emptyList(),
    ): AudioBook = AudioBook(
        id = id,
        libraryId = if (local) "local-library" else "remote-library",
        isLocal = local,
        title = title,
        author = "Fixture author",
        coverPath = coverPath,
        audioFiles = files,
    )

    fun download(id: String, audioBookId: String, status: DownloadStatus): DownloadItem = DownloadItem(
        id = id,
        audioBookId = audioBookId,
        title = "Fixture download",
        status = status,
        startedAt = 1_700_000_000_000L,
    )

    fun createDownloadsViewModel(
        instrumentation: Instrumentation,
        store: ViewModelStore,
    ): DownloadsViewModel {
        var viewModel: DownloadsViewModel? = null
        instrumentation.runOnMainSync {
            viewModel = DownloadsViewModel(
                downloadManager = downloadManager,
                downloadItemDao = database.downloadItemDao(),
                audioBookDao = database.audioBookDao(),
                connectivityMonitor = app.connectivityMonitor,
                settingsManager = settings,
                apiService = apiService,
            )
            store.put("owner-scoped-download-runtime-canary", requireNotNull(viewModel))
        }
        return requireNotNull(viewModel)
    }

    suspend fun awaitActiveIds(viewModel: DownloadsViewModel, expected: Set<String>) {
        withTimeout(5_000) {
            viewModel.uiState.filter {
                it.activeDownloads.map { row -> row.download.id }.toSet() == expected
            }.first()
        }
    }

    fun close() {
        api.releaseAStream()
        api.close()
        database.close()
        context.clearFixtureStorage()
    }
}

private sealed interface MediaCall {
    data class Stream(val rawBookId: String) : MediaCall
    data class Detail(val rawBookId: String) : MediaCall
    data class Cover(val rawBookId: String) : MediaCall
}

/**
 * A coroutine-aware Retrofit proxy. The stream continuation is deliberately
 * retained so the real ApiService response fence runs only after scope change.
 */
private class GatedDownloadApi {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val streamEntered = CompletableDeferred<Unit>()
    private val streamReleased = CompletableDeferred<Unit>()
    private var loginSequence = 0
    val mediaCalls = mutableListOf<MediaCall>()

    val service: AudiobookshelfApi = Proxy.newProxyInstance(
        AudiobookshelfApi::class.java.classLoader,
        arrayOf(AudiobookshelfApi::class.java),
        InvocationHandler { proxy, method, args -> handle(proxy, method.name, args.orEmpty()) },
    ) as AudiobookshelfApi

    suspend fun awaitAStream() = withTimeout(5_000) { streamEntered.await() }

    fun releaseAStream() {
        streamReleased.complete(Unit)
    }

    fun close() {
        scope.cancel()
    }

    private fun handle(proxy: Any, name: String, args: Array<out Any?>): Any? = when (name) {
        "login" -> {
            val request = args[0] as LoginRequest
            loginSequence += 1
            retrofitSuccess(
                LoginResponse(
                    user = ApiUser(
                        id = "account-${request.username}",
                        token = "token-${request.username}-$loginSequence",
                    ),
                ),
            )
        }
        "getAudioFileStream" -> {
            val rawBookId = args[0] as String
            mediaCalls += MediaCall.Stream(rawBookId)
            val continuation = args.last() as Continuation<Response<ResponseBody>>
            scope.async {
                streamEntered.complete(Unit)
                streamReleased.await()
                continuation.resumeWith(
                    Result.success(
                        retrofitSuccess("audio".toResponseBody("audio/mpeg".toMediaType())),
                    ),
                )
            }
            COROUTINE_SUSPENDED
        }
        "getItem" -> {
            mediaCalls += MediaCall.Detail(args[0] as String)
            error("DownloadEngine requested details after the fixture supplied audio metadata")
        }
        "getCoverImage" -> {
            mediaCalls += MediaCall.Cover(args[0] as String)
            error("DownloadEngine requested a cover after the stale stream response")
        }
        "toString" -> "GatedDownloadApi"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args.singleOrNull()
        else -> error("Unexpected AudiobookshelfApi call: $name")
    }

    private fun <T> retrofitSuccess(body: T): Response<T> = Response.success(
        body,
        okhttp3.Response.Builder()
            .request(Request.Builder().url(SERVER_URL).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .build(),
    )
}

private class DownloadFixtureContext(baseContext: Context) : ContextWrapper(baseContext) {
    private val fixtureId = UUID.randomUUID().toString()
    private val sharedPreferenceNames = linkedSetOf<String>()
    private val fixtureFilesDir = File(baseContext.cacheDir, "owner-scoped-download-$fixtureId")

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        sharedPreferenceNames += name
        return baseContext.getSharedPreferences("owner-scoped-download-$fixtureId-$name", mode)
    }

    override fun deleteSharedPreferences(name: String): Boolean =
        baseContext.deleteSharedPreferences("owner-scoped-download-$fixtureId-$name")

    override fun getFilesDir(): File = fixtureFilesDir.apply { mkdirs() }

    override fun getFileStreamPath(name: String): File = File(filesDir, name)

    override fun deleteFile(name: String): Boolean = getFileStreamPath(name).delete()

    fun clearFixtureStorage() {
        sharedPreferenceNames.forEach(::deleteSharedPreferences)
        fixtureFilesDir.deleteRecursively()
    }
}

private class FreeEntitlementStore : DurableEntitlementStore {
    override val legacyPaid: Boolean = false
    override val trialStartedAtEpochMs: Long? = null
    override val trialConsumed: Boolean = false
    override fun consumeTrial(startedAtEpochMs: Long): Boolean = false
    override fun advanceTrialWatermark(nowEpochMs: Long): Long? = null
}

private class FreeEntitlementCache : PlayEntitlementCache {
    override var playUnlockCached: Boolean = false
    override var forceFree: Boolean = false
}

private const val SERVER_URL = "https://owner-scope.fixture.invalid"
private const val OWNER_A = "owner-a"
private const val OWNER_B = "owner-b"
private const val RAW_BOOK_ID = "book-shared"
private const val RAW_DOWNLOAD_ID = "download-shared"
private const val LOCAL_BOOK_ID = "local-book"
private const val LOCAL_DOWNLOAD_ID = "local-download"
private const val FILE_NAME = "track.mp3"
private const val RETAINED_PART_BYTES = "retained-a-part"
