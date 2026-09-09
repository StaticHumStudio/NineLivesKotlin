package com.ninelivesaudio.app.service.download

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.NineLivesApp
import com.ninelivesaudio.app.data.local.AppDatabase
import com.ninelivesaudio.app.data.local.converter.toDomain
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
import com.ninelivesaudio.app.domain.model.Chapter
import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus
import com.ninelivesaudio.app.entitlement.DurableEntitlementStore
import com.ninelivesaudio.app.entitlement.EntitlementCachePrefs
import com.ninelivesaudio.app.entitlement.EntitlementRepository
import com.ninelivesaudio.app.entitlement.PlayEntitlementCache
import com.ninelivesaudio.app.entitlement.TrialReminderScheduler
import com.ninelivesaudio.app.service.DownloadManager
import com.ninelivesaudio.app.service.PlaybackManager
import com.ninelivesaudio.app.service.RemotePlaybackSessionCoordinatorFactory
import com.ninelivesaudio.app.service.StaleSessionProbe
import com.ninelivesaudio.app.service.SyncManager
import com.ninelivesaudio.app.service.ConnectivityMonitor
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.service.local.LocalFolderAccess
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.data.repository.LibraryRepository
import com.ninelivesaudio.app.data.repository.ProgressRepository
import com.ninelivesaudio.app.data.repository.ListeningSessionRepository
import com.ninelivesaudio.app.entitlement.EffectiveSettingsRepository
import dagger.Lazy
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

/**
 * Production-path canary for F03 and F18. It deliberately drives the real
 * engine and manager against isolated Room and files rather than a path helper.
 */
@RunWith(AndroidJUnit4::class)
class DownloadMetadataRuntimeCanaryTest {

    @Test
    fun `same title scoped items retain independent bytes canonical detail and deletion`() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NineLivesApp
        val fixture = MetadataFixture(InstrumentationRegistry.getInstrumentation().targetContext, app)
        try {
            val scope = fixture.login("owner-a")
            val first = fixture.sparseBook(scope.encodeIncoming("raw-one"), "Same title")
            val second = fixture.sparseBook(scope.encodeIncoming("raw-two"), "Same title")
            val firstItem = fixture.item(scope.encodeIncoming("download-one"), first.id)
            val secondItem = fixture.item(scope.encodeIncoming("download-two"), second.id)
            fixture.seed(first, second, firstItem, secondItem)

            assertEquals(DownloadStatus.Completed, fixture.engine.download(firstItem, first, scope) { _, _, _ -> }.status)
            assertEquals(DownloadStatus.Completed, fixture.engine.download(secondItem, second, scope) { _, _, _ -> }.status)

            val firstAfter = requireNotNull(fixture.database.audioBookDao().getById(first.id)).toDomain()
            val secondAfter = requireNotNull(fixture.database.audioBookDao().getById(second.id)).toDomain()
            assertNotEquals(firstAfter.localPath, secondAfter.localPath)
            assertEquals(listOf(1, 2, 3), firstAfter.audioFiles.map { it.index })
            assertEquals(fixture.expandedChapters, firstAfter.chapters)
            assertTrue(firstAfter.audioFiles.all { !it.localPath.isNullOrBlank() && File(requireNotNull(it.localPath)).isFile })
            assertEquals(firstAfter.audioFiles.size, firstAfter.audioFiles.map { it.localPath }.toSet().size)
            assertTrue(secondAfter.audioFiles.all { !it.localPath.isNullOrBlank() && File(requireNotNull(it.localPath)).isFile })

            fixture.manager.deleteDownload(first.id)

            assertFalse(File(requireNotNull(firstAfter.localPath)).exists())
            assertFalse(requireNotNull(fixture.database.audioBookDao().getById(first.id)).toDomain().isDownloaded)
            assertTrue(requireNotNull(fixture.database.audioBookDao().getById(first.id)).toDomain().localPath.isNullOrBlank())
            assertTrue(fixture.database.downloadItemDao().getById(firstItem.id) == null)
            assertTrue(File(requireNotNull(secondAfter.localPath)).isDirectory)
            val survivor = requireNotNull(fixture.database.audioBookDao().getById(second.id)).toDomain()
            assertTrue(survivor.isDownloaded)
            assertEquals(secondAfter.audioFiles, survivor.audioFiles)
            assertTrue(secondAfter.audioFiles.all { File(requireNotNull(it.localPath)).readText().isNotBlank() })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `different owners sharing a raw id retain independent bytes and deletion`() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NineLivesApp
        val fixture = MetadataFixture(InstrumentationRegistry.getInstrumentation().targetContext, app)
        try {
            val ownerA = fixture.login("owner-a")
            val first = fixture.sparseBook(ownerA.encodeIncoming("shared-raw"), "Same title")
            val firstItem = fixture.item(ownerA.encodeIncoming("download-a"), first.id)
            fixture.seed(first, firstItem)
            fixture.engine.download(firstItem, first, ownerA) { _, _, _ -> }

            val ownerB = fixture.login("owner-b")
            val second = fixture.sparseBook(ownerB.encodeIncoming("shared-raw"), "Same title")
            val secondItem = fixture.item(ownerB.encodeIncoming("download-b"), second.id)
            fixture.seed(second, secondItem)
            fixture.engine.download(secondItem, second, ownerB) { _, _, _ -> }

            val firstPath = requireNotNull(requireNotNull(fixture.database.audioBookDao().getById(first.id)).localPath)
            val secondPath = requireNotNull(requireNotNull(fixture.database.audioBookDao().getById(second.id)).localPath)
            val secondAfter = requireNotNull(fixture.database.audioBookDao().getById(second.id)).toDomain()
            assertNotEquals(firstPath, secondPath)

            fixture.login("owner-a")
            fixture.manager.deleteDownload(first.id)

            assertFalse(File(firstPath).exists())
            assertFalse(requireNotNull(fixture.database.audioBookDao().getById(first.id)).toDomain().isDownloaded)
            assertTrue(requireNotNull(fixture.database.audioBookDao().getById(first.id)).toDomain().localPath.isNullOrBlank())
            assertTrue(fixture.database.downloadItemDao().getById(firstItem.id) == null)
            assertTrue(File(secondPath).isDirectory)
            val survivor = requireNotNull(fixture.database.audioBookDao().getById(second.id)).toDomain()
            assertTrue(survivor.isDownloaded)
            assertEquals(secondAfter.audioFiles, survivor.audioFiles)
            assertTrue(survivor.audioFiles.all { File(requireNotNull(it.localPath)).readText().isNotBlank() })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `legacy shared directory is retained while another completed owner still references it`() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NineLivesApp
        val fixture = MetadataFixture(InstrumentationRegistry.getInstrumentation().targetContext, app)
        try {
            val scope = fixture.login("owner-a")
            val sharedDirectory = File(fixture.root, "legacy-shared").apply { mkdirs() }
            File(sharedDirectory, "legacy.m4b").writeText("survivor")
            val first = fixture.book(scope.encodeIncoming("legacy-one"), "Same title").copy(isDownloaded = true, localPath = sharedDirectory.absolutePath)
            val second = fixture.book(scope.encodeIncoming("legacy-two"), "Same title").copy(isDownloaded = true, localPath = sharedDirectory.absolutePath)
            fixture.seed(first, second, fixture.item(scope.encodeIncoming("legacy-download-one"), first.id).copy(status = DownloadStatus.Completed), fixture.item(scope.encodeIncoming("legacy-download-two"), second.id).copy(status = DownloadStatus.Completed))

            fixture.manager.deleteDownload(first.id)

            assertTrue(File(sharedDirectory, "legacy.m4b").isFile)
            assertFalse(requireNotNull(fixture.database.audioBookDao().getById(first.id)).toDomain().isDownloaded)
            assertTrue(fixture.database.downloadItemDao().getById(scope.encodeIncoming("legacy-download-one")) == null)
            assertTrue(requireNotNull(fixture.database.audioBookDao().getById(second.id)).toDomain().isDownloaded)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `detail failure never publishes synthetic canonical snapshot`() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NineLivesApp
        val fixture = MetadataFixture(InstrumentationRegistry.getInstrumentation().targetContext, app)
        try {
            val scope = fixture.login("owner-a")
            val cached = fixture.sparseBook(scope.encodeIncoming("detail-failure"), "Detail failure").copy(
                audioFiles = listOf(AudioFile(id = "cached", ino = "cached", index = 1, filename = "cached.m4b")),
                chapters = listOf(Chapter(99, 0.0, 10.0, "Cached chapter")),
            )
            val item = fixture.item(scope.encodeIncoming("detail-failure-download"), cached.id)
            fixture.seed(cached, item)
            fixture.api.failDetails = true

            val result = fixture.engine.download(item, cached, scope) { _, _, _ -> }

            val after = requireNotNull(fixture.database.audioBookDao().getById(cached.id)).toDomain()
            assertEquals(DownloadStatus.Completed, result.status)
            assertEquals(1, fixture.api.detailRequests)
            assertEquals(
                cached.audioFiles.map { it.copy(localPath = null) },
                after.audioFiles.map { it.copy(localPath = null) },
            )
            assertEquals(cached.chapters, after.chapters)
            assertTrue(after.isDownloaded)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `cover failure still publishes completed canonical files and chapters`() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NineLivesApp
        val fixture = MetadataFixture(InstrumentationRegistry.getInstrumentation().targetContext, app)
        try {
            val scope = fixture.login("owner-a")
            val book = fixture.sparseBook(scope.encodeIncoming("cover-failure"), "Cover failure").copy(coverPath = "/cover")
            val item = fixture.item(scope.encodeIncoming("cover-failure-download"), book.id)
            fixture.seed(book, item)
            fixture.api.failCover = true

            assertEquals(DownloadStatus.Completed, fixture.engine.download(item, book, scope) { _, _, _ -> }.status)
            val after = requireNotNull(fixture.database.audioBookDao().getById(book.id)).toDomain()
            assertEquals(1, fixture.api.detailRequests)
            assertEquals(fixture.expandedChapters, after.chapters)
            assertEquals(listOf(1, 2, 3), after.audioFiles.map { it.index })
            assertTrue(after.audioFiles.all { !it.localPath.isNullOrBlank() })
            assertTrue(after.localCoverPath.isNullOrBlank())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `backfill is active scope only idempotent and cannot resurrect a deleted row`() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NineLivesApp
        val fixture = MetadataFixture(InstrumentationRegistry.getInstrumentation().targetContext, app)
        try {
            val scope = fixture.login("owner-a")
            val legacy = fixture.book(scope.encodeIncoming("legacy-backfill"), "Legacy").copy(isDownloaded = true, localPath = fixture.legacyDirectory("legacy-backfill").absolutePath, audioFiles = emptyList(), chapters = emptyList())
            val tracked = fixture.item(scope.encodeIncoming("legacy-backfill-download"), legacy.id).copy(status = DownloadStatus.Completed)
            val raw = fixture.book("raw-legacy", "Raw").copy(isDownloaded = true, localPath = fixture.legacyDirectory("raw").absolutePath, audioFiles = emptyList())
            fixture.seed(legacy, tracked, raw)

            fixture.manager.backfillDownloadedMetadata()
            assertEquals(1, fixture.api.detailRequests)
            assertTrue(requireNotNull(fixture.database.audioBookDao().getById(legacy.id)).toDomain().audioFiles.all { !it.localPath.isNullOrBlank() })
            assertTrue(requireNotNull(fixture.database.audioBookDao().getById(raw.id)).toDomain().audioFiles.isEmpty())
            fixture.manager.backfillDownloadedMetadata()
            assertEquals(1, fixture.api.detailRequests)

            val stale = fixture.book(scope.encodeIncoming("legacy-backfill-stale"), "Legacy stale").copy(
                isDownloaded = true,
                localPath = fixture.legacyDirectory("legacy-backfill-stale").absolutePath,
                audioFiles = emptyList(),
                chapters = emptyList(),
            )
            val staleItem = fixture.item(scope.encodeIncoming("legacy-backfill-stale-download"), stale.id)
                .copy(status = DownloadStatus.Completed)
            fixture.seed(stale, staleItem)
            fixture.armBoundaryPause()
            val rerun = async(Dispatchers.Default) { fixture.manager.backfillDownloadedMetadata() }
            fixture.awaitBoundary()
            fixture.database.audioBookDao().deleteById(stale.id)
            fixture.releaseMetadataBoundary()
            rerun.await()
            assertTrue(fixture.database.audioBookDao().getById(stale.id) == null)
        } finally {
            fixture.clearBoundaryPause()
            fixture.close()
        }
    }

    @Test
    fun `completion and same scope delete serialize at the real metadata boundary`() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NineLivesApp
        val fixture = MetadataFixture(InstrumentationRegistry.getInstrumentation().targetContext, app)
        try {
            val scope = fixture.login("owner-a")
            val book = fixture.book(scope.encodeIncoming("held-completion"), "Held completion").copy(coverPath = "/cover")
            val item = fixture.item(scope.encodeIncoming("held-completion-download"), book.id)
            fixture.seed(book, item)
            fixture.armBoundaryPause()
            val completion = async(Dispatchers.Default) { fixture.engine.download(item, book, scope) { _, _, _ -> } }
            fixture.awaitBoundary()
            val delete = async(Dispatchers.Default) { fixture.manager.deleteDownload(book.id) }
            assertFalse(delete.isCompleted)
            fixture.releaseMetadataBoundary()
            completion.await()
            delete.await()
            val clearedBook = requireNotNull(fixture.database.audioBookDao().getById(book.id)).toDomain()
            assertFalse(clearedBook.isDownloaded)
            assertTrue(clearedBook.localPath.isNullOrBlank())
            assertTrue(clearedBook.localCoverPath.isNullOrBlank())
            assertTrue(fixture.database.downloadItemDao().getById(item.id) == null)
            assertFalse(fixture.downloadDirectory(book).exists())
        } finally {
            fixture.clearBoundaryPause()
            fixture.close()
        }
    }

    @Test
    fun `stale completion leaves the completed row and files unchanged after scope loss`() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NineLivesApp
        val fixture = MetadataFixture(InstrumentationRegistry.getInstrumentation().targetContext, app)
        try {
            val scope = fixture.login("owner-a")
            val book = fixture.book(scope.encodeIncoming("stale-completion"), "Stale completion").copy(coverPath = "/cover")
            val item = fixture.item(scope.encodeIncoming("stale-completion-download"), book.id)
            fixture.seed(book, item)
            fixture.armBoundaryPause()
            val completion = async(Dispatchers.Default) { fixture.engine.download(item, book, scope) { _, _, _ -> } }
            fixture.awaitBoundary()
            val itemBefore = requireNotNull(fixture.database.downloadItemDao().getById(item.id))
            val bookBefore = requireNotNull(fixture.database.audioBookDao().getById(book.id))
            val filesBefore = fixture.directorySnapshot(book)

            fixture.login("owner-b")
            fixture.releaseMetadataBoundary()
            completion.await()

            assertEquals(itemBefore, fixture.database.downloadItemDao().getById(item.id))
            assertEquals(bookBefore, fixture.database.audioBookDao().getById(book.id))
            assertEquals(filesBefore, fixture.directorySnapshot(book))
        } finally {
            fixture.clearBoundaryPause()
            fixture.close()
        }
    }

    @Test
    fun `force stopped resume reopens room then loads canonical tracks offline in server index order`() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NineLivesApp
        val fixture = MetadataFixture(InstrumentationRegistry.getInstrumentation().targetContext, app)
        try {
            val scope = fixture.login("owner-a")
            val catalog = fixture.sparseBook(scope.encodeIncoming("resume-book"), "Resume")
            val item = fixture.item(scope.encodeIncoming("resume-download"), catalog.id)
            fixture.seed(catalog, item)
            fixture.api.holdAfterFirstStream = true

            val interrupted = async(Dispatchers.Default) { fixture.engine.download(item, catalog, scope) { _, _, _ -> } }
            fixture.api.awaitSecondStream()
            try {
                assertTrue(File(fixture.downloadDirectory(catalog), "z-last.m4b").isFile)
                interrupted.cancel()
            } finally {
                fixture.api.releaseStream()
            }
            val beforeResume = requireNotNull(fixture.database.audioBookDao().getById(catalog.id)).toDomain()
            assertTrue(beforeResume.audioFiles.isEmpty())
            assertTrue(beforeResume.chapters.isEmpty())

            interrupted.cancelAndJoin()
            val persisted = requireNotNull(fixture.database.downloadItemDao().getById(item.id)).toDomain()
            fixture.reopenRoomAndEngine()
            fixture.engine.download(persisted, catalog, scope) { _, _, _ -> }
            val reopened = requireNotNull(fixture.database.audioBookDao().getById(catalog.id)).toDomain()
            fixture.api.unreachable = true
            val callsBeforeOffline = fixture.api.remoteCallCount

            val playbackManager = fixture.newPlaybackManager()
            try {
                val loaded = withContext(Dispatchers.Main) { playbackManager.loadAudioBook(reopened, autoPlay = false) }
                val uris = withContext(Dispatchers.Main) {
                    requireNotNull(playbackManager.getPlayer()).let { player ->
                        (0 until player.mediaItemCount).map { index ->
                            requireNotNull(player.getMediaItemAt(index).localConfiguration).uri.toString()
                        }
                    }
                }

                assertTrue(loaded)
                assertEquals(reopened.audioFiles.sortedBy { it.index }.map { "file://${it.localPath}" }, uris)
                assertEquals(callsBeforeOffline, fixture.api.remoteCallCount)
            } finally {
                withContext(Dispatchers.Main) { playbackManager.release() }
            }
        } finally {
            fixture.close()
        }
    }
}

private class MetadataFixture(baseContext: Context, app: NineLivesApp) {
    private val context = MetadataContext(baseContext)
    private val databaseFile = File(context.filesDir, "metadata-canary.db")
    var database: AppDatabase = openDatabase()
    private val settings = SettingsManager(context)
    val api = MetadataApi()
    private val apiService = ApiService(api.service, AuthInterceptor(settings), settings)
    val root = File(context.filesDir, "downloads")
    private var boundaryEntered = CompletableDeferred<Unit>()
    private var releaseBoundary = CompletableDeferred<Unit>()
    private val entitlements = EntitlementRepository(
        prefs = object : DurableEntitlementStore {
            override val legacyPaid = false
            override val trialStartedAtEpochMs: Long? = null
            override val trialConsumed = false
            override fun consumeTrial(startedAtEpochMs: Long) = false
            override fun advanceTrialWatermark(nowEpochMs: Long): Long? = null
        },
        cache = object : PlayEntitlementCache {
            override var playUnlockCached = false
            override var forceFree = false
        },
        reminderScheduler = TrialReminderScheduler { },
        nowEpochMs = { 0L },
    )
    var engine = newEngine()
    val manager = DownloadManager(
        context, database.downloadItemDao(), database.audioBookDao(), engine,
        DownloadSlotStore(database.downloadItemDao(), database.audioBookDao(), database.playbackProgressDao(), entitlements,
            EntitlementCachePrefs(context), apiService), settings, app.connectivityMonitor, apiService,
    )

    init {
        runBlocking {
            settings.saveSettings(AppSettings(appMode = AppMode.AUDIOBOOKSHELF, serverUrl = SERVER_URL, downloadPath = root.absolutePath))
        }
    }

    suspend fun login(owner: String): ActiveRemoteScope {
        assertEquals(CredentialLoginResult.SUCCESS, apiService.login(SERVER_URL, owner, "fixture-password"))
        return requireNotNull(apiService.captureActiveRemoteScope())
    }

    val expandedChapters = listOf(Chapter(1, 0.0, 1.0, "One"), Chapter(2, 1.0, 2.0, "Two"))

    fun sparseBook(id: String, title: String) = AudioBook(
        id = id, libraryId = "remote-library", title = title, author = "Same author",
    )

    private fun canonicalFiles() = listOf(
        AudioFile(id = "two", ino = "two", index = 2, filename = "z-last.m4b"),
        AudioFile(id = "one", ino = "one", index = 1, filename = "a/first.m4b"),
        AudioFile(id = "collision", ino = "collision", index = 3, filename = "a:first.m4b"),
    )

    fun book(id: String, title: String) = sparseBook(id, title).copy(
        audioFiles = listOf(
            AudioFile(id = "two", ino = "two", index = 2, filename = "z-last.m4b"),
            AudioFile(id = "one", ino = "one", index = 1, filename = "a/first.m4b"),
            AudioFile(id = "collision", ino = "collision", index = 3, filename = "a:first.m4b"),
        ),
        chapters = listOf(Chapter(1, 0.0, 1.0, "One"), Chapter(2, 1.0, 2.0, "Two")),
    )

    fun item(id: String, bookId: String) = DownloadItem(id = id, audioBookId = bookId, title = "Same title", status = DownloadStatus.Queued)

    suspend fun seed(vararg values: Any) {
        database.audioBookDao().upsertAll(values.filterIsInstance<AudioBook>().map { it.toEntity() })
        values.filterIsInstance<DownloadItem>().forEach { database.downloadItemDao().upsert(it.toEntity()) }
    }

    fun legacyDirectory(name: String) = File(root, name).apply {
        mkdirs()
        canonicalFiles().forEach { File(this, it.filename.substringAfterLast('/').replace(':', '_')).writeText(it.ino) }
    }

    fun downloadDirectory(book: AudioBook): File = File(root, downloadFolderName(book.author, book.title, book.id))

    fun directorySnapshot(book: AudioBook): Map<String, ByteArray> {
        val directory = downloadDirectory(book)
        return directory.listFiles().orEmpty().associate { it.name to it.readBytes() }
    }

    fun armBoundaryPause() {
        boundaryEntered = CompletableDeferred()
        releaseBoundary = CompletableDeferred()
        engine.metadataBoundaryObserver = ::pauseMetadataBoundary
    }

    suspend fun awaitBoundary() {
        withTimeout(5_000) { boundaryEntered.await() }
    }

    fun releaseMetadataBoundary() {
        releaseBoundary.complete(Unit)
    }

    suspend fun pauseMetadataBoundary() {
        boundaryEntered.complete(Unit)
        releaseBoundary.await()
    }

    fun clearBoundaryPause() {
        engine.metadataBoundaryObserver = null
        releaseBoundary.complete(Unit)
    }

    fun close() {
        database.close()
        context.clear()
    }

    fun reopenRoomAndEngine() {
        database.close()
        database = openDatabase()
        engine = newEngine()
    }

    private fun openDatabase(): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        databaseFile.absolutePath,
    ).allowMainThreadQueries().build()

    private fun newEngine() = DownloadEngine(
        context,
        database.downloadItemDao(),
        database.audioBookDao(),
        api.service,
        apiService,
        settings,
    )

    fun newPlaybackManager(): PlaybackManager {
        val progress = ProgressRepository(database, database.playbackProgressDao(), database.pendingProgressDao(), apiService)
        val books = AudioBookRepository(context, database.audioBookDao(), apiService, database.localListeningSessionDao(), database.localBookmarkDao(), database.playbackProgressDao())
        val libraries = LibraryRepository(database.libraryDao(), database.audioBookDao(), books, apiService)
        val sessions = ListeningSessionRepository(database.localListeningSessionDao(), apiService, settings)
        val connectivity = ConnectivityMonitor(context, OkHttpClient(), settings)
        val effective = EffectiveSettingsRepository(settings, entitlements)
        val sync = object : Lazy<SyncManager> {
            private val value by lazy { SyncManager(apiService, libraries, books, progress, database.audioBookDao(), connectivity, settings) }
            override fun get(): SyncManager = value
        }
        val factory = object : RemotePlaybackSessionCoordinatorFactory {
            override suspend fun openServerListeningSession(book: AudioBook, requestedGeneration: Long, loadRequest: Long?, remoteScope: ActiveRemoteScope) = false
            override suspend fun recoverStaleSession(probe: StaleSessionProbe) = false
        }
        val constructor = requireNotNull(PlaybackManager::class.java.declaredConstructors.firstOrNull { it.parameterTypes.any { type -> type == RemotePlaybackSessionCoordinatorFactory::class.java } })
        constructor.isAccessible = true
        return constructor.newInstance(*constructor.parameterTypes.map { type -> when (type) {
            Context::class.java -> context; ApiService::class.java -> apiService; SettingsManager::class.java -> settings
            EffectiveSettingsRepository::class.java -> effective; ProgressRepository::class.java -> progress; AudioBookDao::class.java -> database.audioBookDao()
            AudioBookRepository::class.java -> books; LibraryRepository::class.java -> libraries; ListeningSessionRepository::class.java -> sessions
            Lazy::class.java -> sync; ConnectivityMonitor::class.java -> connectivity; OkHttpClient::class.java -> OkHttpClient()
            LocalFolderAccess::class.java -> LocalFolderAccess(context); RemotePlaybackSessionCoordinatorFactory::class.java -> factory
            else -> error("Unmapped PlaybackManager parameter: ${type.name}")
        } }.toTypedArray()) as PlaybackManager
    }
}

private class MetadataApi {
    private var loginSequence = 0
    var failDetails = false
    var failCover = false
    var detailRequests = 0
    var holdAfterFirstStream = false
    var unreachable = false
    var remoteCallCount = 0
    private var streamRequestCount = 0
    private val secondStreamEntered = CompletableDeferred<Unit>()
    private val releaseStream = CompletableDeferred<Unit>()
    suspend fun awaitSecondStream() = withTimeout(5_000) { secondStreamEntered.await() }
    fun releaseStream() { releaseStream.complete(Unit) }
    val service: AudiobookshelfApi = Proxy.newProxyInstance(
        AudiobookshelfApi::class.java.classLoader,
        arrayOf(AudiobookshelfApi::class.java),
        InvocationHandler { proxy, method, args ->
            when (method.name) {
                "login" -> {
                    val request = args.orEmpty().first() as LoginRequest
                    loginSequence += 1
                    response(LoginResponse(ApiUser(id = "account-${request.username}", token = "token-$loginSequence")))
                }
                "getAudioFileStream" -> {
                    remoteCallCount += 1
                    streamRequestCount += 1
                    if (holdAfterFirstStream && streamRequestCount == 2) {
                        secondStreamEntered.complete(Unit)
                        runBlocking { releaseStream.await() }
                    }
                    if (unreachable) failure() else response("${args.orEmpty()[1]}-bytes".toResponseBody("audio/mpeg".toMediaType()))
                }
                "getItem" -> {
                    remoteCallCount += 1
                    detailRequests += 1
                    if (failDetails) failure() else response(expanded(args.orEmpty()[0] as String))
                }
                "getCoverImage" -> {
                    remoteCallCount += 1
                    if (failCover || unreachable) failure<ResponseBody>() else response("cover".toResponseBody("image/jpeg".toMediaType()))
                }
                "toString" -> "MetadataApi"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args.orEmpty().singleOrNull()
                else -> error("Unexpected API call: ${method.name}")
            }
        },
    ) as AudiobookshelfApi

    private fun <T> response(body: T): Response<T> = Response.success(
        body,
        okhttp3.Response.Builder().request(Request.Builder().url(SERVER_URL).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").build(),
    )

    private fun <T> failure(): Response<T> = Response.error(500, "fixture failure".toResponseBody("text/plain".toMediaType()))

    private fun expanded(rawId: String) = com.ninelivesaudio.app.data.remote.dto.ApiLibraryItem(
        id = rawId,
        libraryId = "remote-library",
        media = com.ninelivesaudio.app.data.remote.dto.ApiMedia(
            metadata = com.ninelivesaudio.app.data.remote.dto.ApiMetadata(title = "Expanded", authorName = "Same author"),
            audioFiles = listOf(
                com.ninelivesaudio.app.data.remote.dto.ApiAudioFile(ino = "two", index = 2, metadata = com.ninelivesaudio.app.data.remote.dto.ApiFileMetadata(filename = "z-last.m4b")),
                com.ninelivesaudio.app.data.remote.dto.ApiAudioFile(ino = "one", index = 1, metadata = com.ninelivesaudio.app.data.remote.dto.ApiFileMetadata(filename = "a/first.m4b")),
                com.ninelivesaudio.app.data.remote.dto.ApiAudioFile(ino = "collision", index = 3, metadata = com.ninelivesaudio.app.data.remote.dto.ApiFileMetadata(filename = "a:first.m4b")),
            ),
            chapters = listOf(
                com.ninelivesaudio.app.data.remote.dto.ApiChapter(1, 0.0, 1.0, "One"),
                com.ninelivesaudio.app.data.remote.dto.ApiChapter(2, 1.0, 2.0, "Two"),
            ),
        ),
    )
}

private class MetadataContext(base: Context) : ContextWrapper(base) {
    private val id = UUID.randomUUID().toString()
    private val preferences = linkedSetOf<String>()
    private val storage = File(base.cacheDir, "download-metadata-$id")
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        preferences += name
        return baseContext.getSharedPreferences("download-metadata-$id-$name", mode)
    }
    override fun deleteSharedPreferences(name: String) = baseContext.deleteSharedPreferences("download-metadata-$id-$name")
    override fun getFilesDir(): File = storage.apply { mkdirs() }
    fun clear() {
        preferences.forEach(::deleteSharedPreferences)
        storage.deleteRecursively()
    }
}

private const val SERVER_URL = "https://download-metadata.fixture.invalid"
