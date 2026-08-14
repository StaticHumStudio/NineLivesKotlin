package com.ninelivesaudio.app.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Looper
import android.util.Log
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.*
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import android.media.MediaMetadataRetriever
import androidx.media3.exoplayer.ExoPlayer
import com.ninelivesaudio.app.entitlement.EffectiveSettings
import com.ninelivesaudio.app.entitlement.EffectiveSettingsRepository
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.data.repository.ListeningSessionRepository
import com.ninelivesaudio.app.data.repository.LibraryRepository
import com.ninelivesaudio.app.data.repository.PendingProgressQueueOwner
import com.ninelivesaudio.app.data.repository.ProgressRepository
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Chapter
import com.ninelivesaudio.app.domain.model.PlaybackSessionInfo
import com.ninelivesaudio.app.domain.model.isInActiveLibrary
import com.ninelivesaudio.app.service.local.LocalFolderAccess
import com.ninelivesaudio.app.service.local.reconcileLocalBookAccess
import com.ninelivesaudio.app.MainActivity
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class PlaybackState {
    STOPPED, LOADING, PLAYING, PAUSED, BUFFERING
}

internal data class PlaybackIntentTransition(
    val state: PlaybackState,
    val startPlaybackWork: Boolean,
    val stopPlaybackWork: Boolean,
    val syncPause: Boolean,
)

internal fun playbackIntentTransition(
    playerReady: Boolean,
    playWhenReady: Boolean,
    playbackWorkActive: Boolean,
): PlaybackIntentTransition {
    val state = when {
        !playWhenReady -> PlaybackState.PAUSED
        playerReady -> PlaybackState.PLAYING
        else -> PlaybackState.BUFFERING
    }
    val startPlaybackWork = state == PlaybackState.PLAYING && !playbackWorkActive
    val stopPlaybackWork = state == PlaybackState.PAUSED && playbackWorkActive
    return PlaybackIntentTransition(
        state = state,
        startPlaybackWork = startPlaybackWork,
        stopPlaybackWork = stopPlaybackWork,
        syncPause = stopPlaybackWork,
    )
}

internal fun playbackWorkActive(
    positionPollingJob: Job?,
    sessionSyncJob: Job?,
): Boolean = positionPollingJob?.isActive == true || sessionSyncJob?.isActive == true

internal fun shouldReportPolledPosition(hasBook: Boolean, @Suppress("UNUSED_PARAMETER") duration: Duration): Boolean =
    hasBook

internal class PlaybackLoadOwner {
    private val ownerLock = Any()
    private var nextRequest = 0L
    private var activeRequest: Long? = null
    private val pendingRequests = sortedSetOf<Long>()
    private val requestChanges = MutableStateFlow(0L)

    fun newRequest(): Long = synchronized(ownerLock) {
        (++nextRequest).also(pendingRequests::add)
    }

    suspend fun claim(request: Long): Boolean {
        while (true) {
            val observedChange = requestChanges.value
            synchronized(ownerLock) {
                if (request !in pendingRequests) return false
                if (pendingRequests.lastOrNull() == request) {
                    pendingRequests.removeAll { it <= request }
                    activeRequest = request
                    requestChanges.value += 1L
                    return true
                }
            }
            requestChanges.first { it != observedChange }
        }
    }

    fun abandon(request: Long): Boolean = synchronized(ownerLock) {
        val wasNewest = pendingRequests.lastOrNull() == request
        if (pendingRequests.remove(request)) {
            requestChanges.value += 1L
        }
        wasNewest
    }

    fun isCurrent(request: Long): Boolean = synchronized(ownerLock) {
        request == activeRequest
    }
}

internal data class PolledProgressReport(
    val bookId: String,
    val currentTime: Double,
    val duration: Double,
)

private class StaleProgressWriteException : Exception()

internal fun newPolledProgressReportChannel(): Channel<PolledProgressReport> =
    Channel(Channel.CONFLATED)

internal class PlaybackProgressOwner {
    private val ownerLock = Any()
    private val mutexes = mutableMapOf<String, Mutex>()
    private val snapshotGenerations = mutableMapOf<String, Long>()

    data class SnapshotToken(val bookId: String, val generation: Long)

    private fun mutexFor(bookId: String): Mutex = synchronized(ownerLock) {
        mutexes.getOrPut(bookId, ::Mutex)
    }

    fun snapshotToken(bookId: String): SnapshotToken = synchronized(ownerLock) {
        val generation = (snapshotGenerations[bookId] ?: 0L) + 1L
        snapshotGenerations[bookId] = generation
        SnapshotToken(bookId, generation)
    }

    fun invalidateSnapshots(bookId: String) {
        synchronized(ownerLock) {
            snapshotGenerations[bookId] = (snapshotGenerations[bookId] ?: 0L) + 1L
        }
    }

    suspend fun report(bookId: String, block: suspend () -> Unit) {
        mutexFor(bookId).withLock { block() }
    }

    suspend fun <T> sync(bookId: String, block: suspend () -> T): T =
        mutexFor(bookId).withLock { block() }

    suspend fun syncSnapshot(token: SnapshotToken, block: suspend () -> Unit) {
        mutexFor(token.bookId).withLock {
            if (synchronized(ownerLock) { token.generation == snapshotGenerations[token.bookId] }) {
                block()
            }
        }
    }

    suspend fun finalFlushSnapshot(
        token: SnapshotToken,
        syncTerminal: suspend () -> Unit,
        flushProgress: suspend () -> Unit,
    ) {
        mutexFor(token.bookId).withLock {
            if (synchronized(ownerLock) { token.generation == snapshotGenerations[token.bookId] }) {
                flushProgress()
                syncTerminal()
            }
        }
    }
}

internal suspend fun PlaybackProgressOwner.resolveAndSavePlaybackPosition(
    bookId: String,
    candidate: Duration,
    readDurable: suspend () -> Duration?,
    save: suspend (Duration) -> Unit,
): Duration = sync(bookId) {
    val durable = readDurable()
    val resolved = if (durable != null && durable > candidate) durable else candidate
    save(resolved)
    resolved
}

internal class PendingTerminalOwner {
    private val lock = Any()
    private val jobs = mutableMapOf<String, MutableSet<Job>>()

    fun launch(
        scope: CoroutineScope,
        bookId: String,
        block: suspend () -> Unit,
    ): Job {
        lateinit var job: Job
        job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                synchronized(lock) {
                    jobs[bookId]?.let { pending ->
                        pending.remove(job)
                        if (pending.isEmpty()) jobs.remove(bookId)
                    }
                }
            }
        }
        synchronized(lock) { jobs.getOrPut(bookId, ::mutableSetOf).add(job) }
        job.start()
        return job
    }

    suspend fun await(bookId: String) {
        while (true) {
            val pending = synchronized(lock) { jobs[bookId]?.toList().orEmpty() }
            if (pending.isEmpty()) return
            pending.joinAll()
        }
    }

    suspend fun runAfterPending(
        bookId: String,
        isCurrent: () -> Boolean,
        block: () -> Unit,
    ): Boolean {
        await(bookId)
        if (!isCurrent()) return false
        block()
        return true
    }
}

internal data class TerminalPlaybackSnapshot(
    val bookId: String,
    val position: Duration,
    val duration: Duration,
    val isFinished: Boolean,
    val serverSessionId: String?,
    val timeListened: Double,
)

internal fun terminalPlaybackSnapshot(
    bookId: String,
    position: Duration,
    duration: Duration,
    isFinished: Boolean,
    serverSessionId: String?,
    timeListened: Double,
): TerminalPlaybackSnapshot = TerminalPlaybackSnapshot(
    bookId = bookId,
    position = position,
    duration = duration,
    isFinished = isFinished,
    serverSessionId = serverSessionId,
    timeListened = timeListened,
)

internal data class PlaybackProgressSnapshot(
    val bookId: String,
    val isLocal: Boolean,
    val position: Duration,
    val duration: Duration,
    val serverSessionId: String?,
    val serverTimeListened: Double,
    val localSessionId: Long?,
    val localTimeListened: Double,
)

internal fun playbackProgressSnapshot(
    bookId: String,
    isLocal: Boolean,
    position: Duration,
    duration: Duration,
    serverSessionId: String?,
    serverTimeListened: Double,
    localSessionId: Long?,
    localTimeListened: Double,
): PlaybackProgressSnapshot = PlaybackProgressSnapshot(
    bookId = bookId,
    isLocal = isLocal,
    position = position,
    duration = duration,
    serverSessionId = serverSessionId,
    serverTimeListened = serverTimeListened,
    localSessionId = localSessionId,
    localTimeListened = localTimeListened,
)

internal fun foldListeningTime(
    accumulatedSeconds: Double,
    lastTimestampMs: Long,
    nowTimestampMs: Long,
    maxElapsedSeconds: Double? = null,
): Double {
    if (lastTimestampMs == 0L) return accumulatedSeconds
    val rawElapsed = (nowTimestampMs - lastTimestampMs).coerceAtLeast(0L) / 1000.0
    val elapsed = maxElapsedSeconds?.let(rawElapsed::coerceAtMost) ?: rawElapsed
    return accumulatedSeconds + elapsed
}

internal fun sessionResultIsCurrent(
    requestedGeneration: Long,
    currentGeneration: Long,
    requestedBookId: String,
    currentBookId: String?,
): Boolean = requestedGeneration == currentGeneration && requestedBookId == currentBookId

internal fun loadSessionResultIsCurrent(
    loadRequestIsCurrent: Boolean,
    requestedGeneration: Long,
    currentGeneration: Long,
    requestedBookId: String,
    currentBookId: String?,
): Boolean =
    loadRequestIsCurrent &&
        sessionResultIsCurrent(requestedGeneration, currentGeneration, requestedBookId, currentBookId)

internal fun heartbeatSessionIsCurrent(
    requestedGeneration: Long,
    currentGeneration: Long,
    requestedBookId: String,
    currentBookId: String?,
    requestedSessionId: String?,
    currentSessionId: String?,
): Boolean =
    sessionResultIsCurrent(requestedGeneration, currentGeneration, requestedBookId, currentBookId) &&
        requestedSessionId != null &&
        requestedSessionId == currentSessionId

internal fun playbackSyncLifetimeIsCurrent(
    requestedGeneration: Long,
    currentGeneration: Long,
    requestedBookId: String,
    currentBookId: String?,
    requestedServerSessionId: String?,
    currentServerSessionId: String?,
    requestedLocalSessionId: Long?,
    currentLocalSessionId: Long?,
): Boolean =
    sessionResultIsCurrent(requestedGeneration, currentGeneration, requestedBookId, currentBookId) &&
        requestedServerSessionId == currentServerSessionId &&
        requestedLocalSessionId == currentLocalSessionId

private data class PlaybackSyncLifetime(
    val bookId: String,
    val generation: Long,
    val serverSessionId: String?,
    val localSessionId: Long?,
    val pendingProgressToken: PendingProgressQueueOwner.Token,
)

internal data class StaleSessionProbe(
    val requestedGeneration: Long,
    val bookId: String,
    val sessionId: String,
    val position: Duration,
    val duration: Duration,
)

private data class DetachedLocalSession(
    val sessionId: Long?,
    val timeListened: Double,
    val updatedAt: Long,
)

internal fun staleSessionProbe(
    requestedGeneration: Long,
    bookId: String,
    sessionId: String,
    position: Duration,
    duration: Duration,
): StaleSessionProbe = StaleSessionProbe(
    requestedGeneration = requestedGeneration,
    bookId = bookId,
    sessionId = sessionId,
    position = position,
    duration = duration,
)

internal class PlaybackIntentOwner(
    private val currentState: () -> PlaybackState,
    private val playbackWorkActive: () -> Boolean,
    private val publishState: (PlaybackState) -> Unit,
    private val startPlaybackWork: () -> Unit,
    private val stopPlaybackWork: () -> Unit,
    private val markPaused: () -> Unit,
    private val syncPause: () -> Unit,
    private val pausePlaybackLifetime: (playbackWorkWasActive: Boolean) -> Unit = {},
) {
    fun update(playerReady: Boolean, playWhenReady: Boolean) {
        val transition = playbackIntentTransition(
            playerReady = playerReady,
            playWhenReady = playWhenReady,
            playbackWorkActive = playbackWorkActive(),
        )
        val stateChanged = currentState() != transition.state
        if (stateChanged) {
            publishState(transition.state)
        }
        if (stateChanged && transition.state == PlaybackState.PAUSED) {
            pausePlaybackLifetime(transition.stopPlaybackWork)
        }
        if (transition.startPlaybackWork) {
            startPlaybackWork()
        }
        if (transition.stopPlaybackWork) {
            stopPlaybackWork()
            if (transition.syncPause) {
                markPaused()
                syncPause()
            }
        }
    }
}

internal enum class ListeningSessionKind { NONE, LOCAL, SERVER }

internal fun listeningSessionToOpen(
    playbackStarting: Boolean,
    isScannedLocalBook: Boolean,
    hasServerSession: Boolean,
    hasLocalSession: Boolean,
): ListeningSessionKind = when {
    !playbackStarting -> ListeningSessionKind.NONE
    isScannedLocalBook && !hasLocalSession -> ListeningSessionKind.LOCAL
    !isScannedLocalBook && !hasServerSession -> ListeningSessionKind.SERVER
    else -> ListeningSessionKind.NONE
}

internal enum class PlaybackItemPersistence { KEEP, SAVE, CLEAR }

internal fun playbackItemPersistenceAction(
    naturalCompletion: Boolean,
    playbackStarting: Boolean,
): PlaybackItemPersistence = when {
    naturalCompletion -> PlaybackItemPersistence.CLEAR
    playbackStarting -> PlaybackItemPersistence.SAVE
    else -> PlaybackItemPersistence.KEEP
}

/**
 * Core playback engine wrapping Media3 ExoPlayer.
 * Ports AndroidAudioPlaybackService logic: multi-track, streaming with auth,
 * chapter tracking, session sync, speed/volume control.
 *
 * Key advantage over C# MediaPlayer: ExoPlayer handles multi-track playlists
 * natively via ConcatenatingMediaSource — greatly simplifying track management.
 */
@Singleton
class PlaybackManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val apiService: ApiService,
    private val settingsManager: SettingsManager,
    // Anything that ACTS on a setting reads the entitlement-clamped copy.
    // settingsManager stays for the values entitlement does not gate.
    private val effectiveSettings: EffectiveSettingsRepository,
    private val progressRepository: ProgressRepository,
    private val audioBookDao: AudioBookDao,
    private val audioBookRepository: AudioBookRepository,
    private val libraryRepository: LibraryRepository,
    private val sessionRepository: ListeningSessionRepository,
    private val syncManagerLazy: Lazy<SyncManager>,
    private val connectivityMonitor: ConnectivityMonitor,
    private val okHttpClient: OkHttpClient,
    private val localFolderAccess: LocalFolderAccess,
) {
    companion object {
        private const val TAG = "PlaybackManager"
        private const val ARTWORK_MAX_DOWNLOAD_BYTES = 3L * 1024 * 1024 // 3MB
        private const val ARTWORK_MAX_DIMENSION = 768
        private const val ARTWORK_MAX_EMBED_BYTES = 300 * 1024
        private const val ARTWORK_MIN_JPEG_QUALITY = 50

        /**
         * Cover URI for the session metadata of the actively-playing item.
         * Out-of-process controllers (Android Auto/Automotive, Wear OS)
         * resolve this URI themselves and can't read a downloaded book's
         * app-private file:// cover under scoped storage — same constraint
         * as MediaBrowseTree's browse-item covers, so this is always the
         * remote URL, never AudioBook.effectiveCoverPath. The embedded
         * artworkData bytes (set alongside this) carry the actual image for
         * those clients instead.
         */
        @VisibleForTesting
        internal fun sessionArtworkUri(book: AudioBook): String? = book.coverPath
    }

    private val syncManager: SyncManager get() = syncManagerLazy.get()
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var mediaController: MediaController? = null
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var playbackService: PlaybackService? = null
    private var chapterPlayer: ChapterAwareForwardingPlayer? = null
    private var sessionInitialized = false

    /** Expose the current ExoPlayer instance. */
    fun getPlayer(): ExoPlayer? = exoPlayer

    /** Expose the current MediaSession for PlaybackService.onGetSession(). */
    fun getMediaSession(): MediaSession? = mediaSession

    /** Called by PlaybackService when it's created/destroyed. */
    fun setPlaybackService(service: PlaybackService?) {
        Log.d(TAG, "setPlaybackService: ${if (service != null) "attached" else "detached"}")
        playbackService = service
    }

    /**
     * Initialize the persistent ExoPlayer, ForwardingPlayer, and MediaLibrarySession.
     * Called from PlaybackService.onCreate() so Android Auto always has a session
     * to browse, even before any book is loaded.
     * Idempotent — safe to call multiple times.
     */
    @OptIn(UnstableApi::class)
    fun initSession() {
        // Check if we need to upgrade from plain MediaSession to MediaLibrarySession.
        // This happens when initSession() was first called from loadAudioBook() before
        // PlaybackService existed (creating a plain MediaSession), and now the service
        // is available in its onCreate(). Without this upgrade, onGetSession() casts to
        // MediaLibrarySession, gets null, and rejects the MediaController connection —
        // which triggers a full teardown that kills the player.
        val needsLibraryUpgrade = sessionInitialized
                && playbackService != null
                && mediaSession != null
                && mediaSession !is MediaLibrarySession

        if (sessionInitialized && !needsLibraryUpgrade) {
            Log.d(TAG, "initSession: already initialized, skipping")
            return
        }

        if (needsLibraryUpgrade) {
            Log.d(TAG, "initSession: upgrading plain MediaSession → MediaLibrarySession")
            mediaSession?.release()
            mediaSession = null
            // Player and chapterPlayer remain intact — only the session wrapper changes
        } else {
            Log.d(TAG, "initSession: creating persistent player + session (service=${playbackService != null})")

            val audioAttributes = AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .setUsage(C.USAGE_MEDIA)
                .build()

            val player = ExoPlayer.Builder(context)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .build()
            exoPlayer = player
            player.addListener(createPlayerListener())

            val wrapper = ChapterAwareForwardingPlayer(player)
            wrapper.seekHandler = { absoluteMs -> seekTo(absoluteMs.milliseconds) }
            // Every media-session controller goes through the wrapper, so the
            // entitlement clamp has to live there too, not only in setSpeed().
            wrapper.speedClamp = { requested ->
                val allowed = clampSpeed(requested)
                // Publish it. A session controller never calls setSpeed(), so
                // without this _speed drifts from the actual player and any
                // later comparison against it is comparing to a lie.
                _speed.value = allowed
                allowed
            }
            chapterPlayer = wrapper
        }

        val wrapper = chapterPlayer!!
        val libraryCallback = playbackService?.createLibraryCallback()
        val isLibrarySession = libraryCallback != null
        mediaSession = if (isLibrarySession) {
            MediaLibrarySession.Builder(context, wrapper, libraryCallback!!)
                .setSessionActivity(createSessionPendingIntent())
                .build()
        } else {
            MediaSession.Builder(context, wrapper)
                .setSessionActivity(createSessionPendingIntent())
                .build()
        }

        // Register with the service so onGetSession() returns it
        mediaSession?.let { playbackService?.refreshSession(it) }

        sessionInitialized = true
        Log.d(TAG, "initSession: OK isLibrarySession=$isLibrarySession sessionId=${mediaSession?.id}")
    }

    /**
     * Load and play a book by its ID. Used by the phone UI.
     */
    suspend fun loadBookById(bookId: String): Boolean {
        return withNewLoadRequest { loadRequest ->
            val book = withContext(Dispatchers.IO) {
                audioBookRepository.getById(bookId)
                    ?: audioBookRepository.fetchFromServer(bookId)
            } ?: return@withNewLoadRequest false

            // Archived books have no source file; refuse to load one (mirrors
            // loadBookByIdForAuto) so this stays safe if wired to a UI entry point.
            if (book.isArchived) return@withNewLoadRequest false

            withContext(Dispatchers.Main) {
                loadAudioBookOwned(loadRequest, book)
            }
        }
    }

    /**
     * Load and play a book by its ID for Android Auto.
     * Skips startPlaybackService() since we are already inside the service's
     * onSetMediaItems callback — creating a new MediaController back to the
     * same service would deadlock.
     */
    suspend fun loadBookByIdForAuto(bookId: String): Boolean {
        return withNewLoadRequest { loadRequest ->
            val book = withContext(Dispatchers.IO) {
                audioBookRepository.getById(bookId)
                    ?: audioBookRepository.fetchFromServer(bookId)
            } ?: return@withNewLoadRequest false

            // Refuse archived books: the source file is gone, so loading would
            // attach a dead SAF URI. Browse/search already hide them, but a stale
            // Auto queue entry could still request one by id.
            if (book.isArchived) return@withNewLoadRequest false

            withContext(Dispatchers.Main) {
                loadAudioBookOwned(loadRequest, book, skipServiceStart = true)
            }
        }
    }

    /** Restore the last current item after ordinary process death, always paused. */
    suspend fun restoreCurrentItem(): Boolean {
        return withNewLoadRequest { loadRequest ->
            val persistedBookId = settingsManager.getCurrentPlaybackBookId()
                ?: return@withNewLoadRequest false
            val storedBook = withContext(Dispatchers.IO) {
                audioBookRepository.getById(persistedBookId)
            }
            val savedPosition = withContext(Dispatchers.IO) {
                progressRepository.getPlaybackProgress(persistedBookId)?.first
            }
            val plan = resolvePlaybackRestore(
                persistedBookId = persistedBookId,
                storedBook = storedBook,
                settings = settingsManager.currentSettings,
                savedPosition = savedPosition,
            )
            if (plan == null) {
                settingsManager.clearCurrentPlaybackBookId()
                return@withNewLoadRequest false
            }

            if (
                shouldProbeServerBeforeRestore(
                    book = plan.book,
                    connectionStatus = connectivityMonitor.connectionStatus.value,
                )
            ) {
                connectivityMonitor.checkServerReachable()
            }

            withContext(Dispatchers.Main) {
                loadAudioBookOwned(loadRequest, plan.book, autoPlay = plan.playWhenReady)
            }
        }
    }

    private suspend fun withNewLoadRequest(block: suspend (Long) -> Boolean): Boolean {
        val loadRequest = playbackLoadOwner.newRequest()
        return try {
            block(loadRequest)
        } finally {
            playbackLoadOwner.abandon(loadRequest)
        }
    }

    // ─── Coroutine Scope ──────────────────────────────────────────────────
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionPollingJob: Job? = null
    private var progressReportingJob: Job? = null
    private var progressReportingChannel: Channel<PolledProgressReport>? = null
    private var sessionSyncJob: Job? = null
    private val playbackProgressOwner = PlaybackProgressOwner()
    private val pendingTerminalOwner = PendingTerminalOwner()
    private val playbackLoadOwner = PlaybackLoadOwner()

    // Auto-rewind: timestamp of last pause
    private var pausedAtTimestamp: Long? = null

    // ─── State ────────────────────────────────────────────────────────────

    private val _playbackState = MutableStateFlow(PlaybackState.STOPPED)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val playbackIntentOwner by lazy(LazyThreadSafetyMode.NONE) {
        PlaybackIntentOwner(
            currentState = { _playbackState.value },
            playbackWorkActive = { playbackWorkActive(positionPollingJob, sessionSyncJob) },
            publishState = { _playbackState.value = it },
            startPlaybackWork = {
                _currentBook.value?.id?.let(playbackProgressOwner::invalidateSnapshots)
                val requestedGeneration = nextPlaybackGeneration()
                applyResumeBookkeeping()
                ensureListeningSessionStarted(requestedGeneration)
                startPositionPolling()
                startSessionSync()
            },
            stopPlaybackWork = {
                stopPositionPolling()
                stopSessionSync()
            },
            pausePlaybackLifetime = { playbackWorkWasActive ->
                invalidatePlaybackGeneration()
                if (!playbackWorkWasActive) {
                    synchronized(sessionLock) {
                        lastSyncTimestamp = 0L
                    }
                }
            },
            markPaused = {
                pausedAtTimestamp = System.currentTimeMillis()
                pendingPauseSnapshot = capturePlaybackProgressSnapshot()
            },
            syncPause = {
                val snapshot = pendingPauseSnapshot.also { pendingPauseSnapshot = null }
                if (snapshot != null) {
                    val snapshotToken = playbackProgressOwner.snapshotToken(snapshot.bookId)
                    scope.launch(Dispatchers.IO) {
                        playbackProgressOwner.syncSnapshot(snapshotToken) {
                            syncPlaybackProgress(snapshot)
                        }
                    }
                }
            },
        )
    }

    private val _currentBook = MutableStateFlow<AudioBook?>(null)
    val currentBook: StateFlow<AudioBook?> = _currentBook.asStateFlow()

    private val _position = MutableStateFlow(Duration.ZERO)
    val position: StateFlow<Duration> = _position.asStateFlow()

    private val _duration = MutableStateFlow(Duration.ZERO)
    val duration: StateFlow<Duration> = _duration.asStateFlow()

    private val _currentChapter = MutableStateFlow<Chapter?>(null)
    val currentChapter: StateFlow<Chapter?> = _currentChapter.asStateFlow()

    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()

    private val _currentChapterIndex = MutableStateFlow(-1)
    val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _volume = MutableStateFlow(0.8f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _bookCompleted = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)

    /**
     * A book was finished end to end.
     *
     * The success moment the In-App Review prompt hangs off. Emitted rather than
     * acted on here, because the Play review flow has to launch from an Activity
     * and this is a singleton that outlives every one of them.
     */
    val bookCompleted: SharedFlow<String> = _bookCompleted.asSharedFlow()

    private val _eqEnabled = MutableStateFlow(false)
    val eqEnabled: StateFlow<Boolean> = _eqEnabled.asStateFlow()

    private val _eqBandGains = MutableStateFlow(List(5) { 0 })
    val eqBandGains: StateFlow<List<Int>> = _eqBandGains.asStateFlow()

    private var equalizer: Equalizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private val _volumeBoost = MutableStateFlow(0) // millibels, 0–1000
    val volumeBoost: StateFlow<Int> = _volumeBoost.asStateFlow()

    private val _isLocalFile = MutableStateFlow(false)
    val isLocalFile: StateFlow<Boolean> = _isLocalFile.asStateFlow()

    // Events
    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    // ─── Internal State ───────────────────────────────────────────────────

    private var cachedChapters: List<Chapter> = emptyList()
    private val sessionLock = Any()
    private val listeningSessionStartMutex = Mutex()
    private var currentSession: PlaybackSessionInfo? = null
    @Volatile private var playbackGeneration: Long = 0L
    private var accumulatedListenTime: Double = 0.0
    private var lastSyncTimestamp: Long = 0L

    // Local listening session (LOCAL mode) — mirrors the server-session bookkeeping
    // above so the Nightwatch Dossier can read local sessions through the same model.
    private var currentLocalSessionId: Long? = null
    private var localSessionAccumSec: Double = 0.0
    private var pendingPauseSnapshot: PlaybackProgressSnapshot? = null
    // Cap to ignore long gaps (background, doze) between heartbeats. 60s ≫ the 12s normal interval.
    private val localSessionMaxTickSec: Double = 60.0

    private fun finalServerSessionTickSec(): Double {
        if (lastSyncTimestamp == 0L) return 0.0
        return (System.currentTimeMillis() - lastSyncTimestamp).coerceAtLeast(0L) / 1000.0
    }

    private fun nextPlaybackGeneration(nextBookId: String? = _currentBook.value?.id): Long {
        listOfNotNull(_currentBook.value?.id, nextBookId)
            .distinct()
            .forEach(progressRepository::invalidatePendingProgressLifetime)
        return synchronized(sessionLock) { ++playbackGeneration }
    }

    private fun invalidatePlaybackGeneration() {
        _currentBook.value?.id?.let(progressRepository::invalidatePendingProgressLifetime)
        synchronized(sessionLock) { playbackGeneration++ }
    }

    private fun capturePlaybackProgressSnapshot(): PlaybackProgressSnapshot? {
        val book = _currentBook.value ?: return null
        val now = System.currentTimeMillis()
        data class PauseTiming(
            val serverSessionId: String?,
            val serverTimeListened: Double,
            val localSessionId: Long?,
            val localTimeListened: Double,
        )
        val timing = synchronized(sessionLock) {
            if (book.isLocal && currentLocalSessionId != null) {
                localSessionAccumSec = foldListeningTime(
                    accumulatedSeconds = localSessionAccumSec,
                    lastTimestampMs = lastSyncTimestamp,
                    nowTimestampMs = now,
                    maxElapsedSeconds = localSessionMaxTickSec,
                )
                lastSyncTimestamp = 0L
            } else if (!book.isLocal && currentSession != null) {
                accumulatedListenTime = foldListeningTime(
                    accumulatedSeconds = accumulatedListenTime,
                    lastTimestampMs = lastSyncTimestamp,
                    nowTimestampMs = now,
                )
                lastSyncTimestamp = 0L
            }
            PauseTiming(
                serverSessionId = currentSession?.id,
                serverTimeListened = accumulatedListenTime,
                localSessionId = currentLocalSessionId,
                localTimeListened = localSessionAccumSec,
            )
        }
        return playbackProgressSnapshot(
            bookId = book.id,
            isLocal = book.isLocal,
            position = _position.value,
            duration = _duration.value,
            serverSessionId = timing.serverSessionId,
            serverTimeListened = timing.serverTimeListened,
            localSessionId = timing.localSessionId,
            localTimeListened = timing.localTimeListened,
        )
    }

    private fun detachLocalSession(): DetachedLocalSession {
        val now = System.currentTimeMillis()
        return synchronized(sessionLock) {
            val sessionId = currentLocalSessionId
            val timeListened = if (sessionId == null) {
                localSessionAccumSec
            } else {
                foldListeningTime(
                    accumulatedSeconds = localSessionAccumSec,
                    lastTimestampMs = lastSyncTimestamp,
                    nowTimestampMs = now,
                    maxElapsedSeconds = localSessionMaxTickSec,
                )
            }
            currentLocalSessionId = null
            localSessionAccumSec = 0.0
            DetachedLocalSession(sessionId, timeListened, now)
        }
    }

    // Track durations for position calculation
    private var trackDurations: List<Double> = emptyList() // cumulative seconds

    // ─── Load ─────────────────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    suspend fun loadAudioBook(
        book: AudioBook,
        skipServiceStart: Boolean = false,
        autoPlay: Boolean = true,
    ): Boolean {
        // ExoPlayer must be created and accessed from the Main thread
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "loadAudioBook must be called from the Main thread"
        }
        return withNewLoadRequest { loadRequest ->
            loadAudioBookOwned(loadRequest, book, skipServiceStart, autoPlay)
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun loadAudioBookOwned(
        loadRequest: Long,
        book: AudioBook,
        skipServiceStart: Boolean = false,
        autoPlay: Boolean = true,
    ): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "loadAudioBookOwned must be called from the Main thread"
        }
        if (!book.isInActiveLibrary(settingsManager.currentSettings)) {
            if (playbackLoadOwner.abandon(loadRequest)) {
                _events.tryEmit(PlaybackEvent.Error("That book is not in the active library."))
            }
            return false
        }
        if (book.isLocal) {
            val library = book.libraryId?.let { libraryRepository.getById(it) }
            val access = reconcileLocalBookAccess(
                book,
                localFolderAccess.accessibleLibraryIds(listOfNotNull(library)),
            )
            if (!access.isPlayable) {
                if (playbackLoadOwner.abandon(loadRequest)) {
                    _events.tryEmit(PlaybackEvent.Error("Folder access was lost. Reconnect it in Settings."))
                }
                return false
            }
        }
        val remoteAccess = remoteMediaAccessDecision(
            book = book,
            serverUrl = settingsManager.currentSettings.serverUrl,
            connectionStatus = connectivityMonitor.connectionStatus.value,
        )
        if (remoteAccess is RemoteMediaAccessDecision.Blocked) {
            if (playbackLoadOwner.abandon(loadRequest)) {
                _events.tryEmit(PlaybackEvent.Error(remoteAccess.message))
            }
            return false
        }
        pendingTerminalOwner.await(book.id)
        if (!playbackLoadOwner.claim(loadRequest)) return false
        var effectiveBook = book
        try {
            playbackProgressOwner.invalidateSnapshots(book.id)
            val requestedGeneration = nextPlaybackGeneration(book.id)
            val priorServerSession = synchronized(sessionLock) {
                currentSession.also { currentSession = null }
            }
            if (priorServerSession != null) {
                scope.launch(Dispatchers.IO) {
                    closeSession(priorServerSession.id)
                }
            }
            Log.d(TAG, "loadAudioBook: '${book.title}' isDownloaded=${book.isDownloaded}")
            _playbackState.value = PlaybackState.LOADING
            _currentBook.value = book
            cachedChapters = book.chapters.sortedBy { it.start }
            _chapters.value = cachedChapters
            _currentChapter.value = null
            _currentChapterIndex.value = -1
            accumulatedListenTime = 0.0
            pausedAtTimestamp = null

            // Ensure persistent player and session exist
            initSession()

            // Stop and clear previous content from the player
            stopPositionPolling()
            stopSessionSync()
            exoPlayer!!.stop()
            exoPlayer!!.clearMediaItems()

            // Persist a final state for any prior local listening session, then drop the id.
            // Add the tail elapsed-since-last-heartbeat to the accumulator so book-switches
            // don't undercount the partial interval between the last heartbeat and now.
            val priorLocalSession = detachLocalSession()
            if (priorLocalSession.sessionId != null) {
                val priorPosSec = _position.value.toDouble(kotlin.time.DurationUnit.SECONDS)
                scope.launch(Dispatchers.IO) {
                    try {
                        sessionRepository.updateLocalSession(
                            id = priorLocalSession.sessionId,
                            timeListeningSec = priorLocalSession.timeListened,
                            currentTimeSec = priorPosSec,
                            updatedAt = priorLocalSession.updatedAt,
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {}
                }
                // Do not reset lastSyncTimestamp here. The next session's open path
                // (local) or startSessionSync (server) will seed it correctly.
            }

            // Reset chapter state on the wrapper
            chapterPlayer?.currentChapter = null
            chapterPlayer?.chapters = emptyList()
            chapterPlayer?.currentChapterIndex = -1
            chapterPlayer?.absolutePositionMs = 0L

            // Release old audio effects (will re-attach after loading new items)
            releaseEqualizer()
            releaseLoudnessEnhancer()

            val isScannedLocalBook = book.isLocal

            // A paused process restore prepares media without creating listening
            // history. Normal autoplay loads still open the server session here,
            // while restored playback opens it when Play actually begins.
            if (
                listeningSessionToOpen(
                    playbackStarting = autoPlay,
                    isScannedLocalBook = isScannedLocalBook,
                    hasServerSession = false,
                    hasLocalSession = synchronized(sessionLock) { currentLocalSessionId != null },
                ) == ListeningSessionKind.SERVER
            ) {
                openServerListeningSession(book, requestedGeneration, loadRequest)
                if (!playbackLoadOwner.isCurrent(loadRequest)) return false
            }

            // If no session audio tracks AND no local audio files, fetch full book details
            val sessionHasTracks = synchronized(sessionLock) { currentSession?.audioTracks?.isNotEmpty() == true }
            if (!isScannedLocalBook && !sessionHasTracks && effectiveBook.audioFiles.isEmpty() && !(effectiveBook.isDownloaded && !effectiveBook.localPath.isNullOrEmpty())) {
                val fullBook = withContext(Dispatchers.IO) {
                    try {
                        apiService.getAudioBook(book.id)
                    } catch (e: Exception) {
                        Log.e(TAG, "loadAudioBook: failed to fetch full book: ${e.message}", e)
                        null
                    }
                }
                if (!playbackLoadOwner.isCurrent(loadRequest)) return false
                if (fullBook != null && fullBook.audioFiles.isNotEmpty()) {
                    effectiveBook = fullBook.copy(currentTime = book.currentTime, progress = book.progress)
                    _currentBook.value = effectiveBook
                    if (cachedChapters.isEmpty() && fullBook.chapters.isNotEmpty()) {
                        cachedChapters = fullBook.chapters.sortedBy { it.start }
                        _chapters.value = cachedChapters
                    }
                }
            }

            // Claim sync ownership before resolving or saving position. This
            // keeps a concurrent server import from replacing the new
            // playback lifetime during load.
            val activeItemClaimed = syncManager.setActivePlaybackItem(effectiveBook.id) {
                playbackLoadOwner.isCurrent(loadRequest)
            }
            if (!activeItemClaimed) return false

            // Resolve the best known position from all sources
            var startPosition = effectiveBook.currentTime
            withContext(Dispatchers.IO) {
                // Check server session for a more recent position
                val sessionTime = synchronized(sessionLock) { (currentSession?.currentTime ?: 0.0).seconds }
                if (sessionTime > startPosition) {
                    startPosition = sessionTime
                }

                // Re-read durable progress and save the resolved position under
                // one per-book gate. Polling cannot advance between the read and
                // write and then get overwritten by this load snapshot.
                try {
                    startPosition = playbackProgressOwner.resolveAndSavePlaybackPosition(
                        bookId = effectiveBook.id,
                        candidate = startPosition,
                        readDurable = {
                            progressRepository.getPlaybackProgress(effectiveBook.id)?.first
                        },
                        save = { resolvedPosition ->
                            progressRepository.savePlaybackProgress(
                                audioBookId = effectiveBook.id,
                                position = resolvedPosition,
                                isFinished = false,
                                onPersisted = {
                                    updateAudioBookProgress(
                                        bookId = effectiveBook.id,
                                        positionSeconds = resolvedPosition.toDouble(kotlin.time.DurationUnit.SECONDS),
                                        durationSeconds = effectiveBook.duration.toDouble(kotlin.time.DurationUnit.SECONDS),
                                        isFinished = false,
                                    )
                                },
                            )
                        },
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {}
            }
            if (!playbackLoadOwner.isCurrent(loadRequest)) return false

            // If the resolved position is at or beyond the book's duration (finished book),
            // reset to the beginning so the player doesn't immediately trigger STATE_ENDED.
            val bookDuration = effectiveBook.duration
            if (bookDuration > Duration.ZERO && startPosition >= bookDuration) {
                Log.d(TAG, "loadAudioBook: position at/past end ($startPosition >= $bookDuration), resetting to start")
                startPosition = Duration.ZERO
            }

            // Determine source and load
            val isLocal = remoteAccess.usesLocalTracks()
            _isLocalFile.value = isLocal

            // Reuse the persistent ExoPlayer — load new media items into it
            val player = exoPlayer!!

            // Build media items with metadata baked in (avoids replaceMediaItem resets)
            val metadata = buildMediaMetadata(effectiveBook)
            if (!playbackLoadOwner.isCurrent(loadRequest)) return false
            val tracksLoaded = if (isLocal) {
                loadLocalTracks(player, effectiveBook, metadata) {
                    playbackLoadOwner.isCurrent(loadRequest)
                }
            } else {
                loadStreamTracks(player, effectiveBook, metadata) {
                    playbackLoadOwner.isCurrent(loadRequest)
                }
            }
            if (!tracksLoaded || !playbackLoadOwner.isCurrent(loadRequest)) return false

            // Set playback parameters
            player.playbackParameters = PlaybackParameters(_speed.value)
            player.volume = _volume.value

            // Load EQ and volume boost settings
            val appSettings = effectiveSettings.current
            _eqEnabled.value = appSettings.eqEnabled
            _eqBandGains.value = appSettings.eqBandGains
            _volumeBoost.value = appSettings.volumeBoostGain

            player.skipSilenceEnabled = appSettings.skipSilenceEnabled

            // Attach audio effects
            attachEqualizer()
            attachLoudnessEnhancer()

            // Seek to saved position before prepare
            if (startPosition > Duration.ZERO) {
                seekToPosition(startPosition)
            }
            // A paused process-death restore has no polling loop to publish the
            // ExoPlayer position, so seed the observable playhead immediately.
            _position.value = startPosition

            // Eagerly set the initial chapter so ChapterAwareForwardingPlayer
            // returns chapter-relative duration/position from the very first
            // getDuration() call. Without this, currentChapter is null during
            // prepare() and Android Auto sees the full book duration until the
            // 500ms polling loop catches up.
            if (cachedChapters.isNotEmpty()) {
                val posSeconds = startPosition.toDouble(kotlin.time.DurationUnit.SECONDS)
                val initialChapterIndex = cachedChapters.indexOfFirst { ch ->
                    posSeconds >= ch.start && posSeconds < ch.end
                }.takeIf { it >= 0 }
                    ?: if (posSeconds >= cachedChapters.last().end) cachedChapters.lastIndex else 0

                val initialChapter = cachedChapters[initialChapterIndex]
                _currentChapter.value = initialChapter
                _currentChapterIndex.value = initialChapterIndex
                chapterPlayer?.chapters = cachedChapters
                chapterPlayer?.currentChapter = initialChapter
                chapterPlayer?.currentChapterIndex = initialChapterIndex
                chapterPlayer?.absolutePositionMs = startPosition.inWholeMilliseconds
            }

            // Start the foreground service (MediaController connect triggers startForeground).
            // Skipped when called from Android Auto's onSetMediaItems callback to avoid
            // re-entrant MediaController connection back to the same service.
            if (!skipServiceStart) {
                startPlaybackService()
            }

            // Prepare and play — the persistent MediaSession already wraps this player.
            // prepare() first so state listeners don't fire before media is loaded.
            player.prepare()
            player.playWhenReady = autoPlay

            // Update duration
            _duration.value = calculateTotalDuration(effectiveBook)

            _events.tryEmit(PlaybackEvent.BookLoaded(effectiveBook))

            settingsManager.saveCurrentPlaybackBookId(effectiveBook.id)

            Log.d(TAG, "loadAudioBook: OK local=$isLocal pos=$startPosition dur=${_duration.value} tracks=${player.mediaItemCount}")
            return true
        } catch (e: Exception) {
            if (!playbackLoadOwner.isCurrent(loadRequest)) return false
            Log.e(TAG, "loadAudioBook: FAILED: ${e.message}", e)
            val cleanupOwned = syncManager.setActivePlaybackItem(null) {
                playbackLoadOwner.isCurrent(loadRequest)
            }
            if (!cleanupOwned) return false
            _playbackState.value = PlaybackState.STOPPED
            _events.tryEmit(PlaybackEvent.Error("Failed to load: ${e.message}"))
            return false
        }
    }

    private fun ensureListeningSessionStarted(requestedGeneration: Long) {
        val requestedBook = _currentBook.value ?: return
        if (
            playbackItemPersistenceAction(
                naturalCompletion = false,
                playbackStarting = true,
            ) == PlaybackItemPersistence.SAVE
        ) {
            settingsManager.saveCurrentPlaybackBookId(requestedBook.id)
        }
        scope.launch {
            listeningSessionStartMutex.withLock {
                val book = _currentBook.value ?: return@withLock
                if (book.id != requestedBook.id) return@withLock
                val kind = listeningSessionToOpen(
                    playbackStarting = true,
                    isScannedLocalBook = book.isLocal,
                    hasServerSession = synchronized(sessionLock) { currentSession != null },
                    hasLocalSession = synchronized(sessionLock) { currentLocalSessionId != null },
                )
                when (kind) {
                    ListeningSessionKind.LOCAL -> openLocalListeningSession(book, _position.value, requestedGeneration)
                    ListeningSessionKind.SERVER -> openServerListeningSession(book, requestedGeneration)
                    ListeningSessionKind.NONE -> Unit
                }
            }
        }
    }

    private suspend fun openServerListeningSession(
        book: AudioBook,
        requestedGeneration: Long,
        loadRequest: Long? = null,
    ) {
        withContext(Dispatchers.IO) {
            try {
                val session = apiService.startPlaybackSession(book.id)
                if (session != null) {
                    val accepted = synchronized(sessionLock) {
                        if (
                            loadSessionResultIsCurrent(
                                loadRequestIsCurrent = loadRequest?.let(playbackLoadOwner::isCurrent) != false,
                                requestedGeneration = requestedGeneration,
                                currentGeneration = playbackGeneration,
                                requestedBookId = book.id,
                                currentBookId = _currentBook.value?.id,
                            )
                        ) {
                            currentSession = session
                            progressRepository.invalidatePendingProgressLifetime(book.id)
                            lastSyncTimestamp = System.currentTimeMillis()
                            if (cachedChapters.isEmpty() && session.chapters.isNotEmpty()) {
                                cachedChapters = session.chapters.sortedBy { it.start }
                                _chapters.value = cachedChapters
                            }
                            true
                        } else {
                            false
                        }
                    }
                    if (!accepted) {
                        try {
                            apiService.closeSession(session.id)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {}
                        return@withContext
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                Log.e(TAG, "openServerListeningSession: ${e.message}", e)
            }
        }
    }

    private suspend fun openLocalListeningSession(
        book: AudioBook,
        startPosition: Duration,
        requestedGeneration: Long,
    ) {
        val startSec = startPosition.toDouble(kotlin.time.DurationUnit.SECONDS)
        val newSessionId = withContext(Dispatchers.IO) {
            try {
                sessionRepository.startLocalSession(
                    audioBookId = book.id,
                    libraryId = book.libraryId.orEmpty(),
                    displayTitle = book.title,
                    startPositionSec = startSec,
                ).takeIf { it > 0L }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                Log.w(TAG, "openLocalListeningSession: ${e.message}")
                null
            }
        }
        if (newSessionId != null) {
            val accepted = synchronized(sessionLock) {
                if (
                    sessionResultIsCurrent(requestedGeneration, playbackGeneration, book.id, _currentBook.value?.id) &&
                    currentLocalSessionId == null
                ) {
                    currentLocalSessionId = newSessionId
                    localSessionAccumSec = 0.0
                    lastSyncTimestamp = System.currentTimeMillis()
                    true
                } else {
                    false
                }
            }
            if (!accepted) {
                withContext(Dispatchers.IO) { sessionRepository.discardLocalSession(newSessionId) }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun loadLocalTracks(
        player: ExoPlayer,
        book: AudioBook,
        metadata: MediaMetadata,
        isCurrent: () -> Boolean,
    ): Boolean {
        // Scanned local-library books store SAF content:// URIs, not filesystem paths.
        // They must be parsed as URIs; File()/Uri.fromFile() would produce invalid file:///content:/... URIs.
        if (book.isLocal) {
            if (!isCurrent()) return false
            loadScannedLocalTracks(player, book, metadata)
            return true
        }

        val localPath = book.localPath ?: return true
        val localFile = fileFromLocalPath(localPath)

        val mediaItems = mutableListOf<MediaItem>()
        val durations = mutableListOf<Double>()
        var cumulative = 0.0

        // Determine the download directory
        val localDir = localFile?.let { if (it.isDirectory) it else it.parentFile }

        if (book.audioFiles.isNotEmpty()) {
            // We have audio file metadata — use it for ordered multi-track loading
            if (book.audioFiles.size == 1 && localFile?.isFile == true) {
                // Single file pointed to directly
                mediaItems.add(
                    MediaItem.Builder()
                        .setUri(Uri.fromFile(localFile))
                        .setMediaMetadata(metadata)
                        .build()
                )
            } else {
                // Same all-or-nothing rule as the scan branch below. The server
                // does not always populate per-file durations, and a ladder of
                // zeroes is the dangerous case: non-empty enough to defeat the
                // missing-durations guard, wrong enough to seek the absolute
                // position into the last track.
                val perTrack = mutableListOf<Double>()
                for (af in book.audioFiles.sortedBy { it.index }) {
                    val path = af.localPath
                        ?: localDir?.let { File(it, File(af.filename).name).takeIf { f -> f.exists() }?.absolutePath }
                        ?: continue

                    mediaItems.add(
                        MediaItem.Builder()
                            .setUri(uriFromLocalPath(path))
                            .setMediaMetadata(metadata)
                            .build()
                    )
                    perTrack.add(af.duration.toDouble(kotlin.time.DurationUnit.SECONDS))
                }

                if (perTrack.isNotEmpty() && perTrack.all { it > 0.0 }) {
                    for (seconds in perTrack) {
                        cumulative += seconds
                        durations.add(cumulative)
                    }
                } else {
                    Log.w(TAG, "server metadata has ${perTrack.count { it <= 0.0 }} of ${perTrack.size} zero durations; seek will start from the beginning")
                }
            }
        } else if (localDir != null && localDir.isDirectory) {
            // No audio file metadata — scan the directory for audio files
            val audioExtensions = setOf("mp3", "m4a", "m4b", "opus", "ogg", "flac", "aac", "wma", "wav")
            val files = localDir.listFiles()
                ?.filter { f -> f.isFile && f.extension.lowercase() in audioExtensions }
                ?.sortedBy { it.name }
                ?: emptyList()

            // Durations MUST be built here, even though ExoPlayer will work them
            // out for playback. seekToPosition needs them BEFORE prepare to pick
            // the right track, and leaving trackDurations empty sends it down the
            // single-track branch, where it seeks an absolute book position into
            // track 1. On a resumed book that reads past the end of the file and
            // ExoPlayer fails the whole source with
            // ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE.
            //
            // That bug was invisible for months because downloads never ran in a
            // release build at all (issue #64), so this branch was unreachable.
            //
            // Only this fallback branch needs the retriever. The branch above
            // already has per-file durations from the server metadata.
            // Read every duration OFF the main thread before touching the
            // player. loadAudioBook runs on Main because ExoPlayer requires it,
            // and forty-odd synchronous MediaMetadataRetriever opens there is an
            // ANR waiting to happen on a cold cache.
            val fileDurations = withContext(Dispatchers.IO) {
                files.map { readDurationSeconds(it) }
            }
            if (!isCurrent()) return false

            for (file in files) {
                mediaItems.add(
                    MediaItem.Builder()
                        .setUri(Uri.fromFile(file))
                        .setMediaMetadata(metadata)
                        .build()
                )
            }

            // All or nothing. A partial ladder is WORSE than none: any file we
            // could not read contributes zero, which leaves trackDurations
            // non-empty so the missing-durations guard never fires, while the
            // cumulative values are wrong enough to seek the absolute position
            // into the last track and reproduce the exact out-of-range crash
            // this is here to prevent.
            if (fileDurations.isNotEmpty() && fileDurations.all { it > 0.0 }) {
                for (seconds in fileDurations) {
                    cumulative += seconds
                    durations.add(cumulative)
                }
            } else {
                Log.w(TAG, "incomplete local durations (${fileDurations.count { it <= 0.0 }} of ${fileDurations.size} unreadable); seek will start from the beginning")
            }
        } else if (localFile?.isFile == true) {
            // localPath is a single file
            mediaItems.add(
                MediaItem.Builder()
                    .setUri(Uri.fromFile(localFile))
                    .setMediaMetadata(metadata)
                    .build()
            )
        }

        if (!isCurrent()) return false
        trackDurations = durations
        player.setMediaItems(mediaItems)
        return true
    }

    private fun uriFromLocalPath(path: String): Uri {
        val parsed = Uri.parse(path)
        return if (parsed.scheme.isNullOrBlank()) {
            Uri.fromFile(File(path))
        } else {
            parsed
        }
    }

    private fun fileFromLocalPath(path: String): File? {
        val parsed = Uri.parse(path)
        return when (parsed.scheme?.lowercase()) {
            null, "" -> File(path)
            "file" -> parsed.path?.let(::File)
            else -> null
        }
    }

    @OptIn(UnstableApi::class)
    private fun loadScannedLocalTracks(player: ExoPlayer, book: AudioBook, metadata: MediaMetadata) {
        val mediaItems = mutableListOf<MediaItem>()
        val durations = mutableListOf<Double>()
        var cumulative = 0.0

        for (af in book.audioFiles.sortedBy { it.index }) {
            val path = af.localPath ?: continue
            mediaItems.add(
                MediaItem.Builder()
                    .setUri(Uri.parse(path))
                    .setMediaMetadata(metadata)
                    .build()
            )
            cumulative += af.duration.toDouble(kotlin.time.DurationUnit.SECONDS)
            durations.add(cumulative)
        }

        if (mediaItems.isEmpty()) {
            val fallback = book.localPath
            if (!fallback.isNullOrEmpty()) {
                mediaItems.add(
                    MediaItem.Builder()
                        .setUri(Uri.parse(fallback))
                        .setMediaMetadata(metadata)
                        .build()
                )
            }
        }

        trackDurations = durations
        player.setMediaItems(mediaItems)
    }

    @OptIn(UnstableApi::class)
    private suspend fun loadStreamTracks(
        player: ExoPlayer,
        book: AudioBook,
        metadata: MediaMetadata,
        isCurrent: () -> Boolean,
    ): Boolean {
        val session = synchronized(sessionLock) { currentSession }
        val serverUrl = settingsManager.currentSettings.serverUrl.trimEnd('/')
        val token = settingsManager.getAuthToken() ?: ""
        if (!isCurrent()) return false

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(
                if (token.isNotEmpty()) mapOf("Authorization" to "Bearer $token")
                else emptyMap()
            )
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

        val mediaItems = mutableListOf<MediaItem>()
        val durations = mutableListOf<Double>()
        var cumulative = 0.0

        if (session != null && session.audioTracks.isNotEmpty()) {
            for (track in session.audioTracks.sortedBy { it.index }) {
                val url = if (track.contentUrl.startsWith("http")) track.contentUrl
                else "$serverUrl${track.contentUrl}"

                mediaItems.add(
                    MediaItem.Builder()
                        .setUri(url)
                        .setMediaMetadata(metadata)
                        .build()
                )
                cumulative += track.duration
                durations.add(cumulative)
            }
        } else {
            // Fallback: stream individual audio files
            for (af in book.audioFiles.sortedBy { it.index }) {
                val url = "$serverUrl/api/items/${Uri.encode(book.id)}/file/${Uri.encode(af.ino)}"
                mediaItems.add(
                    MediaItem.Builder()
                        .setUri(url)
                        .setMediaMetadata(metadata)
                        .build()
                )
                cumulative += af.duration.toDouble(kotlin.time.DurationUnit.SECONDS)
                durations.add(cumulative)
            }
        }

        val mediaSources: List<MediaSource> = mediaItems.map { item ->
            mediaSourceFactory.createMediaSource(item)
        }

        if (!isCurrent()) return false
        trackDurations = durations
        player.setMediaSources(mediaSources)
        return true
    }

    // ─── Playback Controls ────────────────────────────────────────────────

    fun play() {
        exoPlayer?.let { player ->
            // ── Resume ────────────────────────────────────────────────
            if (player.playbackState == Player.STATE_ENDED) {
                val bookId = _currentBook.value?.id
                if (bookId != null) {
                    scope.launch {
                        pendingTerminalOwner.runAfterPending(
                            bookId = bookId,
                            isCurrent = {
                                _currentBook.value?.id == bookId &&
                                    exoPlayer === player &&
                                    player.playbackState == Player.STATE_ENDED
                            },
                        ) {
                            player.seekTo(player.currentMediaItemIndex, player.currentPosition)
                            player.prepare()
                            player.playWhenReady = true
                        }
                    }
                    return
                }
                player.seekTo(player.currentMediaItemIndex, player.currentPosition)
                player.prepare()
            }
            player.playWhenReady = true
            // Player callbacks own app state and transition side effects.
        }
    }

    private fun applyResumeBookkeeping() {
        // Effective, not stored. A free install resumes exactly where playback
        // stopped, and the user's chosen rewind mode survives on disk for when
        // they unlock.
        val settings = effectiveSettings.current
        if (settings.autoRewindEnabled) {
            val pausedAt = pausedAtTimestamp
            if (pausedAt != null) {
                val pausedMs = System.currentTimeMillis() - pausedAt
                val rewindSeconds = when (settings.autoRewindMode) {
                    "flat" -> settings.autoRewindSeconds
                    else -> smartRewindSeconds(pausedMs)
                }
                if (rewindSeconds > 0) {
                    val current = _position.value
                    val target = (current - rewindSeconds.seconds).coerceAtLeast(Duration.ZERO)
                    seekToPosition(target)
                    _position.value = target
                    updateCurrentChapter(target)
                }
            }
            pausedAtTimestamp = null
        }
    }

    private fun smartRewindSeconds(pausedMs: Long): Int {
        return when {
            pausedMs < 30_000     -> 0
            pausedMs < 120_000    -> 5
            pausedMs < 600_000    -> 15
            pausedMs < 3_600_000  -> 30
            else                  -> 60
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun stop() {
        Log.d(TAG, "stop: book=${_currentBook.value?.title} pos=${_position.value}")
        pausedAtTimestamp = null
        invalidatePlaybackGeneration()
        val book = _currentBook.value
        val terminalToken = book?.id?.let {
            playbackProgressOwner.invalidateSnapshots(it)
            playbackProgressOwner.snapshotToken(it)
        }
        stopPositionPolling()
        stopSessionSync()

        val pos = _position.value
        val dur = _duration.value
        val capturedServerTerminal = synchronized(sessionLock) {
            Pair(
                currentSession.also { currentSession = null },
                accumulatedListenTime + finalServerSessionTickSec(),
            )
        }
        val terminal = book?.let {
            terminalPlaybackSnapshot(
                bookId = it.id,
                position = pos,
                duration = dur,
                isFinished = dur > Duration.ZERO && pos >= (dur - 1.seconds).coerceAtLeast(Duration.ZERO),
                serverSessionId = capturedServerTerminal.first?.id,
                timeListened = capturedServerTerminal.second,
            )
        }
        settingsManager.clearCurrentPlaybackBookId()

        // Release player and update state immediately so the UI reflects stopped state.
        releasePlayer()
        stopPlaybackService()
        _playbackState.value = PlaybackState.STOPPED

        // Capture local-session state synchronously and clear before launching the
        // background flush. A fast stop -> start sequence opens a new session in
        // loadAudioBook; if we cleared inside the coroutine, that stale coroutine
        // would wipe the NEW session's id and silently break its heartbeat.
        // Do NOT touch lastSyncTimestamp here — the launched syncProgressNow
        // below still uses it to compute the server session's final elapsed tick.
        val capturedLocalSession = detachLocalSession()

        // Flush progress in the background AFTER releasing the player.
        // All values were captured above so no player access is needed.
        if (terminal != null && terminalToken != null) {
            pendingTerminalOwner.launch(scope, terminal.bookId) {
                progressRepository.withTerminalProgressOwnership(terminal.bookId) {
                    playbackProgressOwner.finalFlushSnapshot(
                        token = terminalToken,
                        syncTerminal = { syncTerminalSession(terminal) },
                        flushProgress = {
                            syncManager.flushPlaybackProgress(
                                itemId = terminal.bookId,
                                currentTime = terminal.position.toDouble(kotlin.time.DurationUnit.SECONDS),
                                isFinished = terminal.isFinished,
                                duration = terminal.duration.toDouble(kotlin.time.DurationUnit.SECONDS),
                                onPersisted = { updateAudioBookProgress(terminal) },
                            )
                        },
                    )
                }
                closeSession(terminal.serverSessionId)
                // Explicit final write for the captured local session (syncProgressNow
                // above no longer touches it because currentLocalSessionId is null).
                if (capturedLocalSession.sessionId != null) {
                    try {
                        sessionRepository.updateLocalSession(
                            id = capturedLocalSession.sessionId,
                            timeListeningSec = capturedLocalSession.timeListened,
                            currentTimeSec = pos.toDouble(kotlin.time.DurationUnit.SECONDS),
                            updatedAt = capturedLocalSession.updatedAt,
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun seekTo(position: Duration) {
        val player = exoPlayer
        val bookId = _currentBook.value?.id
        if (player?.playbackState == Player.STATE_ENDED && bookId != null) {
            scope.launch {
                pendingTerminalOwner.runAfterPending(
                    bookId = bookId,
                    isCurrent = {
                        _currentBook.value?.id == bookId &&
                            exoPlayer === player &&
                            player.playbackState == Player.STATE_ENDED
                    },
                ) {
                    seekToPosition(position)
                    _position.value = position
                    updateCurrentChapter(position)
                }
            }
            return
        }
        seekToPosition(position)
        _position.value = position
        updateCurrentChapter(position)
    }

    fun skipForward(seconds: Int = 30) {
        val current = _position.value
        val total = _duration.value
        val target = (current + seconds.seconds).coerceAtMost(total)
        seekTo(target)
    }

    fun skipBackward(seconds: Int = 10) {
        val current = _position.value
        val target = (current - seconds.seconds).coerceAtLeast(Duration.ZERO)
        seekTo(target)
    }

    fun setSpeed(speed: Float) {
        val allowed = clampSpeed(speed)
        _speed.value = allowed
        exoPlayer?.playbackParameters = PlaybackParameters(allowed)
    }

    /**
     * The single definition of what speed this install may play at.
     *
     * Used by [setSpeed] and by the ForwardingPlayer the MediaSession is built
     * on, because those are two genuinely separate entry points: Android Auto
     * and the notification never call setSpeed at all.
     */
    private fun clampSpeed(speed: Float): Float =
        if (effectiveSettings.isUnlockedNow) {
            speed.coerceIn(0.5f, 3.0f)
        } else {
            EffectiveSettings.FREE_SPEED.toFloat()
        }

    /**
     * Media3's built-in silence trimming.
     *
     * Applied from effective settings, so a downgrade turns it off in the engine
     * without touching the stored preference. Never moves position: skipping
     * silence changes what is rendered, not where the playhead is.
     */
    fun applySkipSilence() {
        exoPlayer?.skipSilenceEnabled = effectiveSettings.current.skipSilenceEnabled
    }

    fun setVolume(vol: Float) {
        _volume.value = vol.coerceIn(0f, 1f)
        exoPlayer?.volume = _volume.value
    }

    fun setEqEnabled(enabled: Boolean) {
        // Enforced here, not only in the UI. This is public API on an injected
        // singleton, so a gated control is not the last line of defence.
        val allowed = enabled && effectiveSettings.isUnlockedNow
        _eqEnabled.value = allowed
        equalizer?.enabled = allowed
    }

    fun setEqBandGain(band: Int, gainMillibels: Int) {
        val gains = _eqBandGains.value.toMutableList()
        if (band in gains.indices) {
            gains[band] = gainMillibels
            _eqBandGains.value = gains
        }
        val eq = equalizer ?: return
        if (band < eq.numberOfBands) {
            eq.setBandLevel(band.toShort(), gainMillibels.toShort())
        }
    }

    fun getEqBandCount(): Int = equalizer?.numberOfBands?.toInt() ?: 5

    fun getEqBandFrequencies(): List<Int> {
        val eq = equalizer ?: return listOf(60, 230, 910, 3600, 14000)
        return (0 until eq.numberOfBands).map { band ->
            eq.getCenterFreq(band.toShort()) / 1000 // milliHz → Hz
        }
    }

    fun getEqBandRange(): Pair<Int, Int> {
        val eq = equalizer ?: return Pair(-1500, 1500)
        val range = eq.bandLevelRange
        return Pair(range[0].toInt(), range[1].toInt())
    }

    fun setVolumeBoost(gainMb: Int) {
        val allowed = if (effectiveSettings.isUnlockedNow) gainMb.coerceIn(0, 1000) else 0
        _volumeBoost.value = allowed
        loudnessEnhancer?.let {
            it.setTargetGain(allowed)
            it.enabled = allowed > 0
        }
    }

    /**
     * Push effective settings onto the LIVE player after an entitlement change.
     *
     * Without this a revocation only takes effect at the next load, so someone
     * who lost entitlement mid-book keeps premium speed, EQ, boost and silence
     * skipping for the rest of that book.
     *
     * Touches only rendering parameters. Speed, EQ, boost and silence trimming
     * all change what comes out of the speaker, never where the playhead is, so
     * this satisfies the invariant that no entitlement transition may move
     * playback position. Do not add a seek here.
     */
    fun applyEntitlementToActivePlayback() {
        val effective = effectiveSettings.current

        // Clamped against the PLAYER, not the cached value. A controller that
        // set 2x through the media session may have left _speed at 1.0, and
        // clamping a stale 1.0 would compute "already fine" and leave the real
        // player running at 2x for a user who just lost entitlement.
        //
        // Written unconditionally. Assigning identical playback parameters is a
        // no-op in ExoPlayer and never seeks.
        val allowedSpeed = clampSpeed(exoPlayer?.playbackParameters?.speed ?: _speed.value)
        _speed.value = allowedSpeed
        exoPlayer?.playbackParameters = PlaybackParameters(allowedSpeed)

        _eqEnabled.value = effective.eqEnabled
        equalizer?.enabled = effective.eqEnabled

        _volumeBoost.value = effective.volumeBoostGain
        loudnessEnhancer?.let {
            it.setTargetGain(effective.volumeBoostGain)
            it.enabled = effective.volumeBoostGain > 0
        }

        exoPlayer?.skipSilenceEnabled = effective.skipSilenceEnabled
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun attachLoudnessEnhancer() {
        val player = exoPlayer ?: return
        // The audio session id is UNSET until the audio sink initializes during
        // prepare()/render. Constructing a LoudnessEnhancer against session 0 is
        // deprecated and throws on modern Android. Skip now; onAudioSessionIdChanged
        // re-attaches once a real id is available.
        if (player.audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        try {
            loudnessEnhancer?.release()
            val enhancer = LoudnessEnhancer(player.audioSessionId)
            loudnessEnhancer = enhancer
            enhancer.setTargetGain(_volumeBoost.value)
            enhancer.enabled = _volumeBoost.value > 0
        } catch (e: Exception) {
            Log.e(TAG, "attachLoudnessEnhancer: failed: ${e.message}", e)
        }
    }

    private fun releaseLoudnessEnhancer() {
        try {
            loudnessEnhancer?.release()
        } catch (_: Exception) {}
        loudnessEnhancer = null
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun attachEqualizer() {
        val player = exoPlayer ?: return
        // See attachLoudnessEnhancer: skip while the session id is UNSET (before
        // the audio sink is ready). onAudioSessionIdChanged re-attaches later.
        if (player.audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        try {
            equalizer?.release()
            val eq = Equalizer(0, player.audioSessionId)
            equalizer = eq
            eq.enabled = _eqEnabled.value
            val bandCount = eq.numberOfBands.toInt()
            // Resize gains list to match actual hardware band count
            val gains = _eqBandGains.value
            val resizedGains = List(bandCount) { i -> gains.getOrElse(i) { 0 } }
            _eqBandGains.value = resizedGains
            for (band in 0 until bandCount) {
                eq.setBandLevel(band.toShort(), resizedGains[band].toShort())
            }
        } catch (e: Exception) {
            Log.e(TAG, "attachEqualizer: failed: ${e.message}", e)
        }
    }

    private fun releaseEqualizer() {
        try {
            equalizer?.release()
        } catch (_: Exception) {}
        equalizer = null
    }

    fun seekToChapter(chapterIndex: Int) {
        if (chapterIndex < 0 || chapterIndex >= cachedChapters.size) return
        val chapter = cachedChapters[chapterIndex]

        // If the player is in STATE_ENDED, finish the terminal handoff before
        // READY can invalidate its snapshot and begin a new playback lifetime.
        val wasEnded = exoPlayer?.playbackState == Player.STATE_ENDED
        if (wasEnded) {
            val bookId = _currentBook.value?.id ?: return
            val player = exoPlayer ?: return
            scope.launch {
                pendingTerminalOwner.runAfterPending(
                    bookId = bookId,
                    isCurrent = {
                        _currentBook.value?.id == bookId &&
                            exoPlayer === player &&
                            player.playbackState == Player.STATE_ENDED
                    },
                ) {
                    seekToPosition(chapter.startTime)
                    _position.value = chapter.startTime
                    updateCurrentChapter(chapter.startTime)
                    _currentChapter.value = chapter
                    _currentChapterIndex.value = chapterIndex
                    chapterPlayer?.currentChapter = chapter
                    chapterPlayer?.currentChapterIndex = chapterIndex
                    player.prepare()
                    player.playWhenReady = true
                }
            }
            return
        }
        seekTo(chapter.startTime)
        _currentChapter.value = chapter
        _currentChapterIndex.value = chapterIndex
        chapterPlayer?.currentChapter = chapter
        chapterPlayer?.currentChapterIndex = chapterIndex

    }

    // ─── Position Calculation (multi-track aware) ─────────────────────────

    /**
     * Best-effort duration of one local audio file, in seconds.
     *
     * Returns 0 rather than throwing. A file we cannot read the duration of
     * contributes nothing to the cumulative ladder, which makes the seek land
     * early rather than past the end of a file, and landing early is recoverable
     * while overshooting kills the whole source.
     */
    private fun readDurationSeconds(file: File): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.let { it / 1000.0 }
                ?: 0.0
        } catch (e: Exception) {
            Log.w(TAG, "could not read duration for ${file.name}: ${e.message}")
            0.0
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun seekToPosition(position: Duration) {
        val player = exoPlayer ?: return
        val posSeconds = position.toDouble(kotlin.time.DurationUnit.SECONDS)

        if (trackDurations.isEmpty() && player.mediaItemCount > 1) {
            // Defence in depth. An absolute seek into track 1 of a multi-file
            // book is the exact move that reads past EOF and fails the source,
            // so refuse it. Starting the book from the beginning loses the place,
            // which is bad, but it is recoverable and a dead player is not.
            Log.w(TAG, "no track durations for ${player.mediaItemCount} items; seeking to start")
            player.seekTo(0, 0)
            return
        }

        if (trackDurations.size <= 1) {
            // Single track
            player.seekTo(position.inWholeMilliseconds)
            return
        }

        // Find target track
        var targetTrack = 0
        for (i in trackDurations.indices) {
            if (posSeconds < trackDurations[i]) {
                targetTrack = i
                break
            }
            if (i == trackDurations.lastIndex) {
                targetTrack = i
            }
        }

        val previousCumulative = if (targetTrack > 0) trackDurations[targetTrack - 1] else 0.0
        val withinTrackMs = ((posSeconds - previousCumulative) * 1000).toLong().coerceAtLeast(0)

        player.seekTo(targetTrack, withinTrackMs)
    }

    private fun getCurrentPosition(): Duration {
        val player = exoPlayer ?: return Duration.ZERO
        val trackPosition = player.currentPosition.milliseconds

        val windowIndex = player.currentMediaItemIndex
        if (windowIndex > 0 && trackDurations.size > 1 && windowIndex - 1 < trackDurations.size) {
            val previousCumulative = trackDurations[windowIndex - 1]
            return trackPosition + previousCumulative.seconds
        }
        return trackPosition
    }

    private fun calculateTotalDuration(book: AudioBook): Duration {
        if (trackDurations.isNotEmpty()) {
            return trackDurations.last().seconds
        }
        return book.duration
    }

    // ─── Player Listener ──────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    private fun createPlayerListener() = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            // The audio sink just produced a usable session id (it is UNSET
            // before prepare()). Attach the EQ and loudness enhancer now — the
            // attach during loadAudioBook is a no-op while the id is unset.
            if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                attachEqualizer()
                attachLoudnessEnhancer()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playbackState)"
            }
            Log.d(TAG, "onPlaybackStateChanged: $stateName playWhenReady=${exoPlayer?.playWhenReady} items=${exoPlayer?.mediaItemCount}")
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    playbackIntentOwner.update(
                        playerReady = false,
                        playWhenReady = exoPlayer?.playWhenReady == true,
                    )
                }
                Player.STATE_READY -> {
                    playbackIntentOwner.update(
                        playerReady = true,
                        playWhenReady = exoPlayer?.playWhenReady == true,
                    )
                }
                Player.STATE_ENDED -> {
                    // End of all tracks
                    invalidatePlaybackGeneration()
                    val book = _currentBook.value
                    val terminalToken = book?.id?.let {
                        playbackProgressOwner.invalidateSnapshots(it)
                        playbackProgressOwner.snapshotToken(it)
                    }
                    stopPositionPolling()
                    stopSessionSync()
                    _playbackState.value = PlaybackState.STOPPED
                    stopPlaybackService()
                    if (
                        playbackItemPersistenceAction(
                            naturalCompletion = true,
                            playbackStarting = false,
                        ) == PlaybackItemPersistence.CLEAR
                    ) {
                        settingsManager.clearCurrentPlaybackBookId()
                    }

                    val capturedServerTerminal = synchronized(sessionLock) {
                        Pair(
                            currentSession.also { currentSession = null },
                            accumulatedListenTime + finalServerSessionTickSec(),
                        )
                    }
                    val finalLocalSession = detachLocalSession()
                    if (book != null && terminalToken != null) {
                        val durSecs = _duration.value.toDouble(kotlin.time.DurationUnit.SECONDS)
                        val terminal = terminalPlaybackSnapshot(
                            bookId = book.id,
                            position = _duration.value,
                            duration = _duration.value,
                            isFinished = true,
                            serverSessionId = capturedServerTerminal.first?.id,
                            timeListened = capturedServerTerminal.second,
                        )
                        pendingTerminalOwner.launch(scope, terminal.bookId) {
                            // Flush through SyncManager (handles both local save + server/offline queue)
                            progressRepository.withTerminalProgressOwnership(terminal.bookId) {
                                playbackProgressOwner.finalFlushSnapshot(
                                    token = terminalToken,
                                    syncTerminal = { syncTerminalSession(terminal) },
                                    flushProgress = {
                                        syncManager.flushPlaybackProgress(
                                            itemId = terminal.bookId,
                                            currentTime = durSecs,
                                            isFinished = true,
                                            duration = durSecs,
                                            onPersisted = { updateAudioBookProgress(terminal) },
                                        )
                                    },
                                )
                            }
                            _bookCompleted.tryEmit(terminal.bookId)
                            closeSession(terminal.serverSessionId)
                            // Persist a final state for the local session, if any.
                            if (finalLocalSession.sessionId != null) {
                                try {
                                    sessionRepository.updateLocalSession(
                                        id = finalLocalSession.sessionId,
                                        timeListeningSec = finalLocalSession.timeListened,
                                        currentTimeSec = durSecs,
                                        updatedAt = finalLocalSession.updatedAt,
                                    )
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    _events.tryEmit(PlaybackEvent.BookFinished)
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Controller intent can change without a playback-state callback.
            // Apply it in both READY and BUFFERING so a pause during a network
            // stall stops active playback work exactly once.
            when (exoPlayer?.playbackState) {
                Player.STATE_READY -> playbackIntentOwner.update(
                    playerReady = true,
                    playWhenReady = playWhenReady,
                )
                Player.STATE_BUFFERING -> playbackIntentOwner.update(
                    playerReady = false,
                    playWhenReady = playWhenReady,
                )
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val idx = exoPlayer?.currentMediaItemIndex ?: 0
            val reasonStr = when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
                else -> "UNKNOWN($reason)"
            }
            Log.d(TAG, "onMediaItemTransition: idx=$idx reason=$reasonStr title=${mediaItem?.mediaMetadata?.title}")
            _events.tryEmit(PlaybackEvent.TrackChanged(idx))
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "onPlayerError: ${error.errorCodeName} — ${error.message}", error)
            invalidatePlaybackGeneration()
            stopPositionPolling()
            stopSessionSync()
            _playbackState.value = PlaybackState.STOPPED
            stopPlaybackService()
            _events.tryEmit(PlaybackEvent.Error("Playback error: ${error.message}"))
        }
    }

    // ─── Position Polling ─────────────────────────────────────────────────

    private fun startPositionPolling() {
        stopPositionPolling()

        val reportChannel = newPolledProgressReportChannel()
        progressReportingChannel = reportChannel
        progressReportingJob = scope.launch {
            for (report in reportChannel) {
                try {
                    playbackProgressOwner.report(report.bookId) {
                        syncManager.reportPlaybackPosition(
                            itemId = report.bookId,
                            currentTime = report.currentTime,
                            duration = report.duration,
                            isFinished = false,
                        )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {}
            }
        }

        positionPollingJob = scope.launch {
            while (isActive) {
                delay(500)
                val pos = getCurrentPosition()
                _position.value = pos
                chapterPlayer?.absolutePositionMs = pos.inWholeMilliseconds
                updateCurrentChapter(pos)

                // Keep the 500 ms playback clock independent from slow server I/O.
                // The single reporting worker stays sequential while this conflated
                // channel retains only the newest unsent sample.
                val book = _currentBook.value
                val dur = _duration.value
                if (shouldReportPolledPosition(hasBook = book != null, duration = dur)) {
                    val reportBook = book ?: continue
                    reportChannel.trySend(
                        PolledProgressReport(
                            bookId = reportBook.id,
                            currentTime = pos.toDouble(kotlin.time.DurationUnit.SECONDS),
                            duration = dur.toDouble(kotlin.time.DurationUnit.SECONDS),
                        ),
                    )
                }
            }
        }
    }

    private fun stopPositionPolling() {
        positionPollingJob?.cancel()
        positionPollingJob = null
        progressReportingChannel?.close()
        progressReportingChannel = null
        progressReportingJob?.cancel()
        progressReportingJob = null
    }

    // ─── Chapter Tracking ─────────────────────────────────────────────────

    private fun updateCurrentChapter(position: Duration) {
        if (cachedChapters.isEmpty()) return

        val posSeconds = position.toDouble(kotlin.time.DurationUnit.SECONDS)
        var newIndex = -1
        for (i in cachedChapters.indices) {
            if (posSeconds >= cachedChapters[i].start && posSeconds < cachedChapters[i].end) {
                newIndex = i
                break
            }
        }

        if (newIndex == -1 && posSeconds >= cachedChapters.last().end) {
            newIndex = cachedChapters.lastIndex
        }

        if (newIndex != _currentChapterIndex.value) {
            _currentChapterIndex.value = newIndex
            val chapter = if (newIndex >= 0) cachedChapters[newIndex] else null
            _currentChapter.value = chapter
            chapterPlayer?.currentChapter = chapter
            chapterPlayer?.currentChapterIndex = newIndex

            // Force MediaSession to re-read duration/position from the ForwardingPlayer.
            // A no-op seekTo triggers onPositionDiscontinuity, which causes MediaSession
            // to push updated chapter duration and position to Android Auto.
            if (chapter != null) {
                val player = exoPlayer ?: return
                player.seekTo(player.currentMediaItemIndex, player.currentPosition)
            }
        }
    }

    // ─── Session Sync (12s interval) ──────────────────────────────────────

    private fun startSessionSync() {
        stopSessionSync()
        synchronized(sessionLock) {
            lastSyncTimestamp = System.currentTimeMillis()
        }
        sessionSyncJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(12_000)
                syncProgressNow()
            }
        }
    }

    private fun stopSessionSync() {
        sessionSyncJob?.cancel()
        sessionSyncJob = null
    }

    private suspend fun syncProgressNow() {
        val bookId = _currentBook.value?.id ?: return
        val lifetime = synchronized(sessionLock) {
            PlaybackSyncLifetime(
                bookId = bookId,
                generation = playbackGeneration,
                serverSessionId = currentSession?.id,
                localSessionId = currentLocalSessionId,
                pendingProgressToken = progressRepository.pendingProgressToken(bookId),
            )
        }
        playbackProgressOwner.sync(bookId) {
            if (sessionResultIsCurrent(lifetime.generation, playbackGeneration, bookId, _currentBook.value?.id)) {
                syncProgressNowLocked(lifetime)
            }
        }
    }

    private suspend fun syncProgressNowLocked(lifetime: PlaybackSyncLifetime) {
        val book = _currentBook.value?.takeIf { it.id == lifetime.bookId } ?: return
        val pos = _position.value
        val dur = _duration.value
        val posSec = pos.toDouble(kotlin.time.DurationUnit.SECONDS)
        val durSec = dur.toDouble(kotlin.time.DurationUnit.SECONDS)

        fun lifetimeIsCurrent(): Boolean = synchronized(sessionLock) {
            playbackSyncLifetimeIsCurrent(
                requestedGeneration = lifetime.generation,
                currentGeneration = playbackGeneration,
                requestedBookId = lifetime.bookId,
                currentBookId = _currentBook.value?.id,
                requestedServerSessionId = lifetime.serverSessionId,
                currentServerSessionId = currentSession?.id,
                requestedLocalSessionId = lifetime.localSessionId,
                currentLocalSessionId = currentLocalSessionId,
            )
        }

        // Save to PlaybackProgress table
        if (!lifetimeIsCurrent()) return
        try {
            progressRepository.savePlaybackProgress(
                audioBookId = book.id,
                position = pos,
                isFinished = false,
                onPersisted = {
                    if (
                        !updateAudioBookProgress(
                            bookId = book.id,
                            positionSeconds = posSec,
                            durationSeconds = durSec,
                            isFinished = false,
                            isCurrent = ::lifetimeIsCurrent,
                        )
                    ) throw StaleProgressWriteException()
                },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {}

        if (!lifetimeIsCurrent()) return

        // Local-mode session heartbeat: accumulate listen time on the open local session row.
        // Same elapsed-since-last-tick math as the server path below; uses the shared
        // lastSyncTimestamp so we never double-count when switching between server and local.
        data class LocalSyncTick(
            val sessionId: Long,
            val timeListened: Double,
            val updatedAt: Long,
        )
        val localTick = synchronized(sessionLock) {
            val sessionId = currentLocalSessionId
            if (
                !book.isLocal ||
                playbackGeneration != lifetime.generation ||
                _currentBook.value?.id != lifetime.bookId ||
                sessionId != lifetime.localSessionId ||
                sessionId == null ||
                _playbackState.value !in setOf(PlaybackState.PLAYING, PlaybackState.BUFFERING)
            ) {
                null
            } else {
                val now = System.currentTimeMillis()
                localSessionAccumSec = foldListeningTime(
                    accumulatedSeconds = localSessionAccumSec,
                    lastTimestampMs = lastSyncTimestamp,
                    nowTimestampMs = now,
                    maxElapsedSeconds = localSessionMaxTickSec,
                )
                lastSyncTimestamp = now
                LocalSyncTick(sessionId, localSessionAccumSec, now)
            }
        }
        if (localTick != null) {
            try {
                sessionRepository.updateLocalSession(
                    id = localTick.sessionId,
                    timeListeningSec = localTick.timeListened,
                    currentTimeSec = posSec,
                    updatedAt = localTick.updatedAt,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {}
        }

        if (book.isLocal) return

        // Sync to server session. Read currentSession AND advance the
        // listen-time accumulator under the same lock, because recoverStaleSession
        // resets accumulatedListenTime/lastSyncTimestamp under this lock. Doing
        // the read-modify-write outside it let a concurrent recovery reset get
        // clobbered (phantom/duplicated listen time on the wrong session). The
        // network call stays outside the lock so it is held only briefly.
        data class ServerSyncTick(val sessionId: String, val timeListened: Double)
        val tick: ServerSyncTick? = synchronized(sessionLock) {
            val sessionId = currentSession?.id
            if (
                sessionId == null ||
                !heartbeatSessionIsCurrent(
                    requestedGeneration = lifetime.generation,
                    currentGeneration = playbackGeneration,
                    requestedBookId = lifetime.bookId,
                    currentBookId = _currentBook.value?.id,
                    requestedSessionId = lifetime.serverSessionId,
                    currentSessionId = sessionId,
                ) ||
                _playbackState.value !in setOf(PlaybackState.PLAYING, PlaybackState.BUFFERING)
            ) {
                null
            } else {
                val now = System.currentTimeMillis()
                val elapsed = (now - lastSyncTimestamp).coerceAtLeast(0) / 1000.0
                lastSyncTimestamp = now
                accumulatedListenTime += elapsed
                ServerSyncTick(sessionId, accumulatedListenTime)
            }
        }
        if (tick != null) {
            try {
                progressRepository.syncSessionProgressIfCurrent(
                    itemId = book.id,
                    sessionId = tick.sessionId,
                    currentTime = pos.toDouble(kotlin.time.DurationUnit.SECONDS),
                    duration = dur.toDouble(kotlin.time.DurationUnit.SECONDS),
                    timeListened = tick.timeListened,
                    isCurrent = {
                        lifetimeIsCurrent() &&
                            _playbackState.value in setOf(PlaybackState.PLAYING, PlaybackState.BUFFERING)
                    },
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {}
        }
    }

    private suspend fun syncTerminalProgress(snapshot: TerminalPlaybackSnapshot) {
        try {
            val sessionId = snapshot.serverSessionId
            if (sessionId == null) {
                progressRepository.savePlaybackProgress(
                    audioBookId = snapshot.bookId,
                    position = snapshot.position,
                    isFinished = snapshot.isFinished,
                    onPersisted = { updateAudioBookProgress(snapshot) },
                )
            } else {
                progressRepository.saveSessionProgressOrEnqueue(
                    itemId = snapshot.bookId,
                    sessionId = sessionId,
                    currentTime = snapshot.position.toDouble(kotlin.time.DurationUnit.SECONDS),
                    isFinished = snapshot.isFinished,
                    duration = snapshot.duration.toDouble(kotlin.time.DurationUnit.SECONDS),
                    timeListened = snapshot.timeListened,
                    onPersisted = { updateAudioBookProgress(snapshot) },
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {}
    }

    private suspend fun syncTerminalSession(snapshot: TerminalPlaybackSnapshot) {
        val posSec = snapshot.position.toDouble(kotlin.time.DurationUnit.SECONDS)
        val durSec = snapshot.duration.toDouble(kotlin.time.DurationUnit.SECONDS)
        if (snapshot.serverSessionId != null) {
            try {
                progressRepository.syncSessionProgress(
                    itemId = snapshot.bookId,
                    sessionId = snapshot.serverSessionId,
                    currentTime = posSec,
                    duration = durSec,
                    timeListened = snapshot.timeListened,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {}
        }
    }

    private suspend fun updateAudioBookProgress(snapshot: TerminalPlaybackSnapshot): Boolean =
        updateAudioBookProgress(
            bookId = snapshot.bookId,
            positionSeconds = snapshot.position.toDouble(kotlin.time.DurationUnit.SECONDS),
            durationSeconds = snapshot.duration.toDouble(kotlin.time.DurationUnit.SECONDS),
            isFinished = snapshot.isFinished,
        )

    private suspend fun updateAudioBookProgress(
        bookId: String,
        positionSeconds: Double,
        durationSeconds: Double,
        isFinished: Boolean,
        isCurrent: () -> Boolean = { true },
    ): Boolean =
        readAndWriteShelfProgressIfCurrent(
            isCurrent = isCurrent,
            read = { audioBookDao.getById(bookId)?.progress ?: 0.0 },
            write = { existingProgress ->
                audioBookDao.updateProgress(
                    id = bookId,
                    currentTimeSeconds = positionSeconds,
                    progress = shelfProgress(
                        currentTime = positionSeconds,
                        duration = durationSeconds,
                        isFinished = isFinished,
                        existingProgress = existingProgress,
                    ),
                    isFinished = if (isFinished) 1 else 0,
                )
            },
        )

    private suspend fun syncPlaybackProgress(snapshot: PlaybackProgressSnapshot) {
        val terminal = terminalPlaybackSnapshot(
            bookId = snapshot.bookId,
            position = snapshot.position,
            duration = snapshot.duration,
            isFinished = false,
            serverSessionId = snapshot.serverSessionId,
            timeListened = snapshot.serverTimeListened,
        )
        val posSec = snapshot.position.toDouble(kotlin.time.DurationUnit.SECONDS)
        val writePlan = playbackProgressWritePlan(
            isLocal = snapshot.isLocal,
            hasServerSession = snapshot.serverSessionId != null,
        )
        if (writePlan.useAtomicDelivery) {
            try {
                progressRepository.savePushOrEnqueueProgress(
                    itemId = snapshot.bookId,
                    currentTime = posSec,
                    isFinished = false,
                    duration = snapshot.duration.toDouble(kotlin.time.DurationUnit.SECONDS),
                    pushToServer = false,
                    onPersisted = {
                        if (writePlan.updateAudioBook) updateAudioBookProgress(terminal)
                    },
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {}
        } else if (writePlan.useTerminalPath) {
            progressRepository.withTerminalProgressOwnership(terminal.bookId) {
                syncTerminalProgress(terminal)
            }
        }

        if (snapshot.localSessionId != null) {
            try {
                sessionRepository.updateLocalSession(
                    id = snapshot.localSessionId,
                    timeListeningSec = snapshot.localTimeListened,
                    currentTimeSec = posSec,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {}
        }
    }

    private suspend fun closeSession(sessionId: String?) {
        if (sessionId == null) return
        try {
            apiService.closeSession(sessionId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {}
        synchronized(sessionLock) {
            if (currentSession?.id == sessionId) {
                currentSession = null
                _currentBook.value?.id?.let(progressRepository::invalidatePendingProgressLifetime)
            }
        }
    }

    // ─── Foreground Recovery (stale session after sleep) ──────────────────

    init {
        // When the app returns from background, check if the playback session is stale
        scope.launch {
            connectivityMonitor.appResumedFromBackground.collect {
                recoverIfSessionStale()
            }
        }
    }

    /**
     * Called when the app returns to the foreground after sleep/background.
     * Tests whether the server-side playback session is still alive.
     * If stale (server returns error), transparently creates a new session
     * so the 12s heartbeat and progress sync keep working.
     */
    private fun recoverIfSessionStale() {
        val book = _currentBook.value ?: return
        if (_playbackState.value !in setOf(PlaybackState.PLAYING, PlaybackState.BUFFERING)) return
        val requestedGeneration = playbackGeneration
        val probe = synchronized(sessionLock) {
            val session = currentSession ?: return
            staleSessionProbe(
                requestedGeneration = requestedGeneration,
                bookId = book.id,
                sessionId = session.id,
                position = _position.value,
                duration = _duration.value,
            )
        }

        scope.launch(Dispatchers.IO) {
            val probeIsCurrent = synchronized(sessionLock) {
                sessionResultIsCurrent(
                    probe.requestedGeneration,
                    playbackGeneration,
                    probe.bookId,
                    _currentBook.value?.id,
                ) &&
                    currentSession?.id == probe.sessionId &&
                    _playbackState.value in setOf(PlaybackState.PLAYING, PlaybackState.BUFFERING)
            }
            if (!probeIsCurrent) return@launch

            Log.d(TAG, "recoverIfSessionStale: testing session ${probe.sessionId} for '${book.title}'")

            try {
                val success = progressRepository.syncSessionProgressIfCurrent(
                    itemId = probe.bookId,
                    sessionId = probe.sessionId,
                    currentTime = probe.position.toDouble(kotlin.time.DurationUnit.SECONDS),
                    duration = probe.duration.toDouble(kotlin.time.DurationUnit.SECONDS),
                    isCurrent = {
                        synchronized(sessionLock) {
                            sessionResultIsCurrent(
                                probe.requestedGeneration,
                                playbackGeneration,
                                probe.bookId,
                                _currentBook.value?.id,
                            ) &&
                                currentSession?.id == probe.sessionId &&
                                _playbackState.value in setOf(PlaybackState.PLAYING, PlaybackState.BUFFERING)
                        }
                    },
                ) ?: return@launch
                if (success) {
                    Log.d(TAG, "recoverIfSessionStale: session still valid")
                } else {
                    Log.w(TAG, "recoverIfSessionStale: session stale, recovering...")
                    recoverStaleSession(book, probe.sessionId, probe.requestedGeneration)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                Log.w(TAG, "recoverIfSessionStale: sync failed (${e.message}), recovering...")
                recoverStaleSession(book, probe.sessionId, probe.requestedGeneration)
            }
        }
    }

    /**
     * Replace a dead server session with a fresh one.
     * If the server is still unreachable, clear the session so progress
     * falls back to the offline queue (SyncManager / PendingProgress).
     */
    private suspend fun recoverStaleSession(
        book: AudioBook,
        staleSessionId: String,
        requestedGeneration: Long,
    ) {
        try {
            val newSession = apiService.startPlaybackSession(book.id)
            if (newSession != null) {
                val accepted = synchronized(sessionLock) {
                    if (
                        sessionResultIsCurrent(requestedGeneration, playbackGeneration, book.id, _currentBook.value?.id) &&
                        currentSession?.id == staleSessionId &&
                        _playbackState.value in setOf(PlaybackState.PLAYING, PlaybackState.BUFFERING)
                    ) {
                        currentSession = newSession
                        progressRepository.invalidatePendingProgressLifetime(book.id)
                        accumulatedListenTime = 0.0
                        lastSyncTimestamp = System.currentTimeMillis()
                        true
                    } else {
                        false
                    }
                }
                if (!accepted) {
                    try {
                        apiService.closeSession(newSession.id)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {}
                    return
                }
                Log.d(TAG, "recoverStaleSession: OK newSessionId=${newSession.id}")
            } else {
                Log.w(TAG, "recoverStaleSession: server returned null — falling back to offline queue")
                synchronized(sessionLock) {
                    if (
                        sessionResultIsCurrent(requestedGeneration, playbackGeneration, book.id, _currentBook.value?.id) &&
                        currentSession?.id == staleSessionId
                    ) {
                        currentSession = null
                        progressRepository.invalidatePendingProgressLifetime(book.id)
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.e(TAG, "recoverStaleSession: failed (${e.message}) — falling back to offline queue")
            synchronized(sessionLock) {
                if (
                    sessionResultIsCurrent(requestedGeneration, playbackGeneration, book.id, _currentBook.value?.id) &&
                    currentSession?.id == staleSessionId
                ) {
                    currentSession = null
                    progressRepository.invalidatePendingProgressLifetime(book.id)
                }
            }
        }
    }

    // ─── Cleanup ──────────────────────────────────────────────────────────

    /** Soft-stop: clear content but keep player and session alive for Android Auto browsing. */
    private fun releasePlayer() {
        Log.d(TAG, "releasePlayer: soft-stop (keeping session alive)")
        chapterPlayer?.currentChapter = null
        chapterPlayer?.absolutePositionMs = 0L
        releaseEqualizer()
        releaseLoudnessEnhancer()
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
    }

    /** Full teardown: release player, session, and all resources. Called on service destroy. */
    fun releaseAll() {
        Log.d(TAG, "releaseAll: full teardown starting (sessionInit=$sessionInitialized)")
        stopPositionPolling()
        stopSessionSync()
        val session = mediaSession
        if (session != null) {
            // Guard removeSession — it throws if the session was already removed
            // (e.g. during service onDestroy when Media3 cleans up internally)
            try {
                playbackService?.removeSession(session)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "releaseAll: session already removed: ${e.message}")
            }
            session.release()
            mediaSession = null
        }
        chapterPlayer = null
        releaseEqualizer()
        releaseLoudnessEnhancer()
        exoPlayer?.let { player ->
            player.stop()
            player.release()
        }
        exoPlayer = null
        sessionInitialized = false
        stopPlaybackService()
        Log.d(TAG, "releaseAll: complete")
    }

    fun release() {
        releaseAll()
        scope.cancel()
    }

    // ─── Media Metadata ───────────────────────────────────────────────────────

    private suspend fun buildMediaMetadata(book: AudioBook): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(book.author)
            .setAlbumTitle(book.title)
            .setDisplayTitle(book.title)
            .setSubtitle(book.author)

        // Set cover art URI so the notification and lock screen show album art.
        // Prefer the locally persisted cover (downloaded books) so artwork shows
        // offline.
        if (!book.effectiveCoverPath.isNullOrEmpty()) {
            sessionArtworkUri(book)?.let { builder.setArtworkUri(Uri.parse(it)) }

            // Also embed cover bytes for Android Auto, which can't fetch
            // authenticated URLs or self-signed cert servers.
            val artworkBytes = withContext(Dispatchers.IO) {
                loadArtworkBytes(book.localCoverPath, book.coverPath)
            }
            if (artworkBytes != null) {
                builder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }
        }

        return builder.build()
    }

    /**
     * Cover bytes for embedding (Android Auto / lock screen). Reads the locally
     * persisted cover file first so it works offline, falling back to the remote
     * authenticated URL. Returns null on any failure — artwork is optional.
     */
    private fun loadArtworkBytes(localCoverPath: String?, remoteCoverUrl: String?): ByteArray? {
        val localFile = localCoverPath
            ?.let { runCatching { Uri.parse(it).path }.getOrNull() }
            ?.let { File(it) }
        if (localFile != null && localFile.exists()) {
            return try {
                BufferedInputStream(localFile.inputStream()).use { stream ->
                    decodeAndCompressArtwork(stream, localFile.length())
                }
            } catch (e: Exception) {
                Log.w(TAG, "buildMediaMetadata: local artwork read failed: ${e.message}")
                null
            }
        }

        if (remoteCoverUrl.isNullOrEmpty()) return null
        return try {
            val request = Request.Builder().url(remoteCoverUrl).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "buildMediaMetadata: artwork skipped reason=http_${response.code}")
                    return null
                }
                val body = response.body ?: run {
                    Log.w(TAG, "buildMediaMetadata: artwork skipped reason=empty_body")
                    return null
                }
                val contentLength = body.contentLength()
                if (contentLength > ARTWORK_MAX_DOWNLOAD_BYTES) {
                    Log.w(
                        TAG,
                        "buildMediaMetadata: artwork skipped reason=content_length contentLength=$contentLength maxBytes=$ARTWORK_MAX_DOWNLOAD_BYTES",
                    )
                    return null
                }
                // Wrap in BoundedInputStream to enforce the byte cap even when
                // Content-Length is unknown (-1) e.g. chunked transfer encoding.
                val boundedStream = BoundedInputStream(body.byteStream(), ARTWORK_MAX_DOWNLOAD_BYTES)
                decodeAndCompressArtwork(boundedStream, contentLength)
            }
        } catch (e: Exception) {
            Log.w(TAG, "buildMediaMetadata: cover download failed: ${e.message}")
            null
        }
    }

    private fun decodeAndCompressArtwork(inputStream: java.io.InputStream, contentLength: Long): ByteArray? {
        val bufferedStream = BufferedInputStream(inputStream)
        bufferedStream.mark((ARTWORK_MAX_DOWNLOAD_BYTES + 1).toInt())

        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(bufferedStream, null, boundsOptions)

        try {
            bufferedStream.reset()
        } catch (e: Exception) {
            Log.w(TAG, "buildMediaMetadata: artwork skipped reason=stream_reset contentLength=$contentLength")
            return null
        }

        val width = boundsOptions.outWidth
        val height = boundsOptions.outHeight
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "buildMediaMetadata: artwork skipped reason=decode_bounds width=$width height=$height")
            return null
        }

        val sampleSize = calculateInSampleSize(width, height, ARTWORK_MAX_DIMENSION)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        val bitmap = BitmapFactory.decodeStream(bufferedStream, null, decodeOptions)
        if (bitmap == null) {
            Log.w(
                TAG,
                "buildMediaMetadata: artwork skipped reason=decode_bitmap width=$width height=$height sampleSize=$sampleSize",
            )
            return null
        }

        val compressed = ByteArrayOutputStream()
        var quality = 85
        do {
            compressed.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, compressed)
            quality -= 10
        } while (compressed.size() > ARTWORK_MAX_EMBED_BYTES && quality >= ARTWORK_MIN_JPEG_QUALITY)
        bitmap.recycle()

        if (compressed.size() > ARTWORK_MAX_EMBED_BYTES) {
            Log.w(
                TAG,
                "buildMediaMetadata: artwork skipped reason=compressed_too_large width=$width height=$height sampleSize=$sampleSize compressedBytes=${compressed.size()} maxBytes=$ARTWORK_MAX_EMBED_BYTES",
            )
            return null
        }

        Log.d(
            TAG,
            "buildMediaMetadata: artwork embedded contentLength=$contentLength width=$width height=$height sampleSize=$sampleSize compressedBytes=${compressed.size()}",
        )
        return compressed.toByteArray()
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        var sampledWidth = width
        var sampledHeight = height

        while (sampledWidth > maxDimension || sampledHeight > maxDimension) {
            sampledWidth /= 2
            sampledHeight /= 2
            sample *= 2
        }

        return sample
    }

    // ─── MediaSession / Playback Service Management ─────────────────────────

    private fun createSessionPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Connect to PlaybackService via MediaController.
     * This is the correct Media3 pattern: when a MediaController connects to a
     * MediaSessionService, Media3 automatically starts the service, calls
     * startForeground(), and manages the entire foreground service lifecycle.
     *
     * The old approach (startForegroundService + manual intent) crashed with
     * ForegroundServiceDidNotStartInTimeException because onGetSession() could
     * return null if the MediaSession wasn't ready yet, preventing Media3 from
     * calling startForeground().
     */
    private fun startPlaybackService() {
        try {
            // Replace any existing controller/future before starting a new connection.
            mediaController?.release()
            mediaController = null
            mediaControllerFuture?.cancel(true)
            mediaControllerFuture = null

            val sessionToken = SessionToken(context, android.content.ComponentName(context, PlaybackService::class.java))
            val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
            mediaControllerFuture = controllerFuture
            controllerFuture.addListener({
                try {
                    // Ignore stale async completions from previous attempts.
                    if (mediaControllerFuture !== controllerFuture) {
                        runCatching { controllerFuture.get().release() }
                        return@addListener
                    }

                    mediaController = controllerFuture.get()
                    Log.d(TAG, "MediaController connected to PlaybackService")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect MediaController: ${e.message}", e)
                }
            }, MoreExecutors.directExecutor())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start PlaybackService: ${e.message}", e)
        }
    }

    /**
     * Release the app-side MediaController and let Media3 own the foreground-service
     * lifecycle. We deliberately do NOT call context.stopService() here: PlaybackService
     * is a MediaLibraryService that manages its own FGS state, and hand-rolling
     * stopService races that teardown (ForegroundServiceDidNotStartInTimeException on
     * Android 12+ if a controller reconnects mid-teardown). Once the player is stopped
     * (every caller stops it first), Media3 leaves the foreground on its own while
     * keeping the session alive for Android Auto browsing; releaseAll() releases the
     * session, which is what actually stops the service on real teardown.
     */
    private fun stopPlaybackService() {
        try {
            mediaController?.release()
            mediaController = null
            mediaControllerFuture?.cancel(true)
            mediaControllerFuture = null
            Log.d(TAG, "Released media controller; Media3 owns FGS teardown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release media controller: ${e.message}", e)
        }
    }
}

internal data class PlaybackProgressWritePlan(
    val useAtomicDelivery: Boolean,
    val updateAudioBook: Boolean,
    val useTerminalPath: Boolean,
)

internal fun playbackProgressWritePlan(
    isLocal: Boolean,
    hasServerSession: Boolean,
): PlaybackProgressWritePlan = if (!isLocal && !hasServerSession) {
    PlaybackProgressWritePlan(
        useAtomicDelivery = true,
        updateAudioBook = true,
        useTerminalPath = false,
    )
} else {
    PlaybackProgressWritePlan(
        useAtomicDelivery = false,
        updateAudioBook = true,
        useTerminalPath = true,
    )
}

// ─── Bounded InputStream ──────────────────────────────────────────────────

/**
 * Wraps an [InputStream] and enforces a hard byte limit.
 * After [maxBytes] have been read, further reads return EOF (-1).
 * Prevents unbounded memory allocation when Content-Length is unknown
 * (e.g. chunked transfer encoding).
 */
private class BoundedInputStream(
    stream: InputStream,
    private val maxBytes: Long,
) : FilterInputStream(stream) {
    private var bytesRead: Long = 0

    override fun read(): Int {
        if (bytesRead >= maxBytes) return -1
        val b = super.read()
        if (b != -1) bytesRead++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (bytesRead >= maxBytes) return -1
        val allowed = len.toLong().coerceAtMost(maxBytes - bytesRead).toInt()
        if (allowed <= 0) return -1
        val n = super.read(b, off, allowed)
        if (n > 0) bytesRead += n
        return n
    }
}

// ─── Events ───────────────────────────────────────────────────────────────

sealed class PlaybackEvent {
    data class BookLoaded(val book: AudioBook) : PlaybackEvent()
    data class TrackChanged(val index: Int) : PlaybackEvent()
    data class Error(val message: String) : PlaybackEvent()
    data object BookFinished : PlaybackEvent()
}
