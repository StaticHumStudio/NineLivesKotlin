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
import com.ninelivesaudio.app.service.SettingsManager
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
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
            val first = fixture.book(scope.encodeIncoming("raw-one"), "Same title")
            val second = fixture.book(scope.encodeIncoming("raw-two"), "Same title")
            val firstItem = fixture.item(scope.encodeIncoming("download-one"), first.id)
            val secondItem = fixture.item(scope.encodeIncoming("download-two"), second.id)
            fixture.seed(first, second, firstItem, secondItem)

            assertEquals(DownloadStatus.Completed, fixture.engine.download(firstItem, first, scope) { _, _, _ -> }.status)
            assertEquals(DownloadStatus.Completed, fixture.engine.download(secondItem, second, scope) { _, _, _ -> }.status)

            val firstAfter = requireNotNull(fixture.database.audioBookDao().getById(first.id)).toDomain()
            val secondAfter = requireNotNull(fixture.database.audioBookDao().getById(second.id)).toDomain()
            assertNotEquals(firstAfter.localPath, secondAfter.localPath)
            assertEquals(listOf(1, 2, 3), firstAfter.audioFiles.map { it.index })
            assertEquals(first.chapters, firstAfter.chapters)
            assertTrue(firstAfter.audioFiles.all { !it.localPath.isNullOrBlank() && File(requireNotNull(it.localPath)).isFile })
            assertEquals(firstAfter.audioFiles.size, firstAfter.audioFiles.map { it.localPath }.toSet().size)
            assertTrue(secondAfter.audioFiles.all { !it.localPath.isNullOrBlank() && File(requireNotNull(it.localPath)).isFile })

            fixture.manager.deleteDownload(first.id)

            assertFalse(File(requireNotNull(firstAfter.localPath)).exists())
            assertTrue(File(requireNotNull(secondAfter.localPath)).isDirectory)
            assertTrue(requireNotNull(fixture.database.audioBookDao().getById(second.id)).toDomain().isDownloaded)
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
            val first = fixture.book(ownerA.encodeIncoming("shared-raw"), "Same title")
            val firstItem = fixture.item(ownerA.encodeIncoming("download-a"), first.id)
            fixture.seed(first, firstItem)
            fixture.engine.download(firstItem, first, ownerA) { _, _, _ -> }

            val ownerB = fixture.login("owner-b")
            val second = fixture.book(ownerB.encodeIncoming("shared-raw"), "Same title")
            val secondItem = fixture.item(ownerB.encodeIncoming("download-b"), second.id)
            fixture.seed(second, secondItem)
            fixture.engine.download(secondItem, second, ownerB) { _, _, _ -> }

            val firstPath = requireNotNull(requireNotNull(fixture.database.audioBookDao().getById(first.id)).localPath)
            val secondPath = requireNotNull(requireNotNull(fixture.database.audioBookDao().getById(second.id)).localPath)
            assertNotEquals(firstPath, secondPath)

            fixture.login("owner-a")
            fixture.manager.deleteDownload(first.id)

            assertFalse(File(firstPath).exists())
            assertTrue(File(secondPath).isDirectory)
        } finally {
            fixture.close()
        }
    }
}

private class MetadataFixture(baseContext: Context, app: NineLivesApp) {
    private val context = MetadataContext(baseContext)
    val database: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val settings = SettingsManager(context)
    private val api = MetadataApi()
    private val apiService = ApiService(api.service, AuthInterceptor(settings), settings)
    private val root = File(context.filesDir, "downloads")
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
    val engine = DownloadEngine(context, database.downloadItemDao(), database.audioBookDao(), api.service, apiService, settings)
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

    fun book(id: String, title: String) = AudioBook(
        id = id, libraryId = "remote-library", title = title, author = "Same author",
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

    fun close() {
        database.close()
        context.clear()
    }
}

private class MetadataApi {
    private var loginSequence = 0
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
                "getAudioFileStream" -> response("${args.orEmpty()[1]}-bytes".toResponseBody("audio/mpeg".toMediaType()))
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
