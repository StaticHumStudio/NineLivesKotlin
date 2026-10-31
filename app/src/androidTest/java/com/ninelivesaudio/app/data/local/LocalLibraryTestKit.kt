package com.ninelivesaudio.app.data.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.NineLivesApp
import com.ninelivesaudio.app.data.local.entity.LibraryEntity
import com.ninelivesaudio.app.data.local.entity.LocalBookmarkEntity
import com.ninelivesaudio.app.data.local.entity.LocalListeningSessionEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.data.repository.LibrarySyncWatermarkStore
import com.ninelivesaudio.app.data.repository.ListeningSessionRepository
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.service.local.ScannedLocalBook
import com.ninelivesaudio.app.service.local.ScannedTrack
import com.ninelivesaudio.app.service.local.toAudioBook
import com.ninelivesaudio.app.ui.dossier.NightwatchDossierViewModel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the local Dossier reports: the overview numbers and one row per book. */
internal data class DossierTotals(
    val total: Duration,
    val sessions: Int,
    val books: Int,
    val perBook: List<Pair<String, Duration>>,
)

/**
 * Shared set up for the local folder tests: a real [AudioBookRepository] over an
 * in-memory database, books built the way a scan builds them, and a Dossier
 * read that goes through the real view model.
 */
internal class LocalLibraryTestKit(
    private val app: NineLivesApp,
    private val database: AppDatabase,
) {
    val repository = AudioBookRepository(
        context = app,
        audioBookDao = database.audioBookDao(),
        apiService = app.apiService,
        localListeningSessionDao = database.localListeningSessionDao(),
        localBookmarkDao = database.localBookmarkDao(),
        playbackProgressDao = database.playbackProgressDao(),
        database = database,
        watermarkStore = LibrarySyncWatermarkStore(app.settingsManager),
    )

    suspend fun seedLibrary() {
        database.libraryDao().upsert(
            LibraryEntity(
                id = LIB,
                name = "Audiobooks",
                isLocal = 1,
                folderUri = "content://com.android.externalstorage.documents/tree/primary%3AAudiobooks",
            )
        )
    }

    /** A scanned book as the mapper hands it over. Tracks are (file name, bytes, title tag). */
    fun scanned(id: String, folder: String, title: String, tracks: List<Triple<String, Long, String?>>, secondsPerTrack: Int = 90, secondsByTrack: List<Int>? = null): AudioBook =
        ScannedLocalBook(
            id = id,
            title = title,
            author = "Author",
            duration = (secondsByTrack?.sum() ?: (tracks.size * secondsPerTrack)).seconds,
            tracks = tracks.mapIndexed { index, (name, size, tag) ->
                ScannedTrack(
                    id = "$id-$index",
                    uri = "content://com.android.externalstorage.documents/tree/primary%3AAudiobooks/document/" +
                        "primary%3AAudiobooks%2F" + folder.split('/').joinToString("%2F") + "%2F$name",
                    filename = name,
                    index = index,
                    duration = (secondsByTrack?.get(index) ?: secondsPerTrack).seconds,
                    mimeType = "audio/mpeg",
                    size = size,
                    title = tag,
                )
            },
        ).toAudioBook(LIB)

    suspend fun bookmark(bookId: String, time: Double, title: String) {
        database.localBookmarkDao().insert(
            LocalBookmarkEntity(audioBookId = bookId, title = title, time = time, createdAt = 1_700_000_000_000L)
        )
    }

    /** A session whose wall clock span covers its listening time, so the Dossier keeps all of it. */
    suspend fun session(bookId: String, seconds: Double, startedAt: Long = System.currentTimeMillis() - 7_200_000L) {
        database.localListeningSessionDao().insert(
            LocalListeningSessionEntity(
                audioBookId = bookId,
                libraryId = LIB,
                startedAt = startedAt,
                updatedAt = startedAt + (seconds * 1000).toLong() + 20_000L,
                timeListening = seconds,
                currentTime = seconds,
                displayTitle = bookId,
            )
        )
    }

    suspend fun progress(bookId: String, seconds: Double, finished: Boolean = false) {
        database.playbackProgressDao().upsert(
            PlaybackProgressEntity(bookId, seconds, if (finished) 1 else 0, "2026-10-01T00:00:00Z")
        )
    }

    suspend fun <T> withLocalMode(includeArchivedInStats: Boolean = true, block: suspend () -> T): T {
        val original = app.settingsManager.currentSettings
        app.settingsManager.saveSettings(
            original.copy(
                appMode = AppMode.LOCAL,
                selectedLocalLibraryId = LIB,
                includeArchivedInStats = includeArchivedInStats,
            )
        )
        try {
            return block()
        } finally {
            app.settingsManager.saveSettings(original)
        }
    }

    /** Open the real Dossier view model (call inside [withLocalMode]) and read its settled numbers. */
    suspend fun dossier(): DossierTotals {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = NightwatchDossierViewModel(
                apiService = app.apiService,
                audioBookRepository = repository,
                sessionRepository = ListeningSessionRepository(
                    sessionDao = database.localListeningSessionDao(),
                    apiService = app.apiService,
                    settingsManager = app.settingsManager,
                ),
                settingsManager = app.settingsManager,
                entitlements = app.entitlementRepository,
            ) as T
        }
        lateinit var viewModel: NightwatchDossierViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel = ViewModelProvider(store, factory)[NightwatchDossierViewModel::class.java]
        }
        try {
            val state = withTimeout(10_000) { viewModel.uiState.filter { !it.isLoading }.first() }
            return DossierTotals(
                total = state.totalListeningTime,
                sessions = state.totalSessions,
                books = state.uniqueBooks,
                perBook = state.bookStats.map { it.bookId to it.listeningTime }.sortedBy { it.first },
            )
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        }
    }

    companion object {
        const val LIB = "audiobooks"
    }
}
