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
import androidx.media3.common.*
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.data.repository.ListeningSessionRepository
import com.ninelivesaudio.app.data.repository.ProgressRepository
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Chapter
import com.ninelivesaudio.app.domain.model.PlaybackSessionInfo
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val progressRepository: ProgressRepository,
    private val audioBookDao: AudioBookDao,
    private val audioBookRepository: AudioBookRepository,
    private val sessionRepository: ListeningSessionRepository,
    private val syncManagerLazy: Lazy<SyncManager>,
    private val connectivityMonitor: ConnectivityMonitor,
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "PlaybackManager"
        private const val ARTWORK_MAX_DOWNLOAD_BYTES = 3L * 1024 * 1024 // 3MB
        private const val ARTWORK_MAX_DIMENSION = 768
        private const val ARTWORK_MAX_EMBED_BYTES = 300 * 1024
        private const val ARTWORK_MIN_JPEG_QUALITY = 50
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
        val book = withContext(Dispatchers.IO) {
            audioBookRepository.getById(bookId)
                ?: audioBookRepository.fetchFromServer(bookId)
        } ?: return false

        return withContext(Dispatchers.Main) {
            loadAudioBook(book)
        }
    }

    /**
     * Load and play a book by its ID for Android Auto.
     * Skips startPlaybackService() since we are already inside the service's
     * onSetMediaItems callback — creating a new MediaController back to the
     * same service would deadlock.
     */
    suspend fun loadBookByIdForAuto(bookId: String): Boolean {
        val book = withContext(Dispatchers.IO) {
            audioBookRepository.getById(bookId)
                ?: audioBookRepository.fetchFromServer(bookId)
        } ?: return false

        // Refuse archived books: the source file is gone, so loading would
        // attach a dead SAF URI. Browse/search already hide them, but a stale
        // Auto queue entry could still request one by id.
        if (book.isArchived) return false

        return withContext(Dispatchers.Main) {
            loadAudioBook(book, skipServiceStart = true)
        }
    }

    // ─── Coroutine Scope ──────────────────────────────────────────────────
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionPollingJob: Job? = null
    private var sessionSyncJob: Job? = null

    // Auto-rewind: timestamp of last pause
    private var pausedAtTimestamp: Long? = null

    // ─── State ────────────────────────────────────────────────────────────

    private val _playbackState = MutableStateFlow(PlaybackState.STOPPED)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

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
    private val sessionMutex = Mutex()
    private var currentSession: PlaybackSessionInfo? = null
    private var accumulatedListenTime: Double = 0.0
    private var lastSyncTimestamp: Long = 0L

    // Local listening session (LOCAL mode) — mirrors the server-session bookkeeping
    // above so the Nightwatch Dossier can read local sessions through the same model.
    private var currentLocalSessionId: Long? = null
    private var localSessionAccumSec: Double = 0.0
    // Cap to ignore long gaps (background, doze) between heartbeats. 60s ≫ the 12s normal interval.
    private val localSessionMaxTickSec: Double = 60.0

    /**
     * Elapsed seconds since the last heartbeat tick, capped. Used at session-close
     * moments (book switch, end-of-book) to add the tail interval that the 12s
     * heartbeat hasn't folded into [localSessionAccumSec] yet.
     *
     * Returns 0 when no heartbeat baseline has been set yet — without this guard,
     * a session closed before its first heartbeat would compute `now - 0` and add
     * a capped 60s phantom to TimeListening.
     */
    private fun finalLocalSessionTickSec(): Double {
        if (lastSyncTimestamp == 0L) return 0.0
        val now = System.currentTimeMillis()
        val raw = (now - lastSyncTimestamp).coerceAtLeast(0L) / 1000.0
        return raw.coerceAtMost(localSessionMaxTickSec)
    }

    // Track durations for position calculation
    private var trackDurations: List<Double> = emptyList() // cumulative seconds

    // ─── Load ─────────────────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    suspend fun loadAudioBook(book: AudioBook, skipServiceStart: Boolean = false): Boolean {
        // ExoPlayer must be created and accessed from the Main thread
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "loadAudioBook must be called from the Main thread"
        }
        var effectiveBook = book
        try {
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
            val priorLocalSessionId = currentLocalSessionId
            if (priorLocalSessionId != null) {
                val priorPosSec = _position.value.toDouble(kotlin.time.DurationUnit.SECONDS)
                val priorAccum = localSessionAccumSec + finalLocalSessionTickSec()
                val priorUpdatedAt = System.currentTimeMillis()
                scope.launch(Dispatchers.IO) {
                    try {
                        sessionRepository.updateLocalSession(
                            id = priorLocalSessionId,
                            timeListeningSec = priorAccum,
                            currentTimeSec = priorPosSec,
                            updatedAt = priorUpdatedAt,
                        )
                    } catch (_: Exception) {}
                }
                currentLocalSessionId = null
                localSessionAccumSec = 0.0
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

            // Start server session for Audiobookshelf books. Scanned local-library
            // books must stay fully local and never ask the server for a session.
            sessionMutex.withLock { currentSession = null }
            if (!isScannedLocalBook) {
                withContext(Dispatchers.IO) {
                    try {
                        val session = apiService.startPlaybackSession(book.id)
                        if (session != null) {
                            sessionMutex.withLock { currentSession = session }
                            if (cachedChapters.isEmpty() && session.chapters.isNotEmpty()) {
                                cachedChapters = session.chapters.sortedBy { it.start }
                                _chapters.value = cachedChapters
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "loadAudioBook: session error: ${e.message}", e)
                    }
                }
            }

            // If no session audio tracks AND no local audio files, fetch full book details
            val sessionHasTracks = sessionMutex.withLock { currentSession?.audioTracks?.isNotEmpty() == true }
            if (!isScannedLocalBook && !sessionHasTracks && effectiveBook.audioFiles.isEmpty() && !(effectiveBook.isDownloaded && !effectiveBook.localPath.isNullOrEmpty())) {
                withContext(Dispatchers.IO) {
                    try {
                        val fullBook = apiService.getAudioBook(book.id)
                        if (fullBook != null && fullBook.audioFiles.isNotEmpty()) {
                            effectiveBook = fullBook.copy(currentTime = book.currentTime, progress = book.progress)
                            _currentBook.value = effectiveBook
                            if (cachedChapters.isEmpty() && fullBook.chapters.isNotEmpty()) {
                                cachedChapters = fullBook.chapters.sortedBy { it.start }
                                _chapters.value = cachedChapters
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "loadAudioBook: failed to fetch full book: ${e.message}", e)
                    }
                }
            }

            // Resolve the best known position from all sources
            var startPosition = effectiveBook.currentTime
            withContext(Dispatchers.IO) {
                // Check server session for a more recent position
                val sessionTime = sessionMutex.withLock { (currentSession?.currentTime ?: 0.0).seconds }
                if (sessionTime > startPosition) {
                    startPosition = sessionTime
                }

                // Check local PlaybackProgress table for an even newer position
                try {
                    val localProgress = progressRepository.getPlaybackProgress(effectiveBook.id)
                    if (localProgress != null) {
                        val (localPos, _) = localProgress
                        if (localPos > startPosition) {
                            startPosition = localPos
                        }
                    }
                } catch (_: Exception) {}

                // Save the resolved position
                try {
                    progressRepository.savePlaybackProgress(
                        audioBookId = effectiveBook.id,
                        position = startPosition,
                        isFinished = false,
                    )
                } catch (_: Exception) {}
            }

            // If the resolved position is at or beyond the book's duration (finished book),
            // reset to the beginning so the player doesn't immediately trigger STATE_ENDED.
            val bookDuration = effectiveBook.duration
            if (bookDuration > Duration.ZERO && startPosition >= bookDuration) {
                Log.d(TAG, "loadAudioBook: position at/past end ($startPosition >= $bookDuration), resetting to start")
                startPosition = Duration.ZERO
            }

            // Determine source and load
            val isLocal = effectiveBook.isDownloaded && !effectiveBook.localPath.isNullOrEmpty()
            _isLocalFile.value = isLocal

            // Reuse the persistent ExoPlayer — load new media items into it
            val player = exoPlayer!!

            // Build media items with metadata baked in (avoids replaceMediaItem resets)
            val metadata = buildMediaMetadata(effectiveBook)
            if (isLocal) {
                loadLocalTracks(player, effectiveBook, metadata)
            } else {
                loadStreamTracks(player, effectiveBook, metadata)
            }

            // Set playback parameters
            player.playbackParameters = PlaybackParameters(_speed.value)
            player.volume = _volume.value

            // Load EQ and volume boost settings
            val appSettings = settingsManager.currentSettings
            _eqEnabled.value = appSettings.eqEnabled
            _eqBandGains.value = appSettings.eqBandGains
            _volumeBoost.value = appSettings.volumeBoostGain

            // Attach audio effects
            attachEqualizer()
            attachLoudnessEnhancer()

            // Seek to saved position before prepare
            if (startPosition > Duration.ZERO) {
                seekToPosition(startPosition)
            }

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
            player.playWhenReady = true

            // Update duration
            _duration.value = calculateTotalDuration(effectiveBook)

            _events.tryEmit(PlaybackEvent.BookLoaded(effectiveBook))

            // Notify SyncManager of active playback item (prevents sync overwriting position)
            syncManager.setActivePlaybackItem(effectiveBook.id)

            // Open a local listening session for scanned local-library books so the
            // Nightwatch Dossier has rows to aggregate. Heartbeats in syncProgressNow
            // accumulate timeListening; loadAudioBook itself just creates the row.
            if (isScannedLocalBook) {
                val startSec = startPosition.toDouble(kotlin.time.DurationUnit.SECONDS)
                val newSessionId = withContext(Dispatchers.IO) {
                    try {
                        sessionRepository.startLocalSession(
                            audioBookId = effectiveBook.id,
                            libraryId = effectiveBook.libraryId.orEmpty(),
                            displayTitle = effectiveBook.title,
                            startPositionSec = startSec,
                        ).takeIf { it > 0L }
                    } catch (e: Exception) {
                        Log.w(TAG, "loadAudioBook: failed to open local session: ${e.message}")
                        null
                    }
                }
                currentLocalSessionId = newSessionId
                localSessionAccumSec = 0.0
                // Seed the heartbeat baseline at session-open time so a close before
                // the first sync tick computes a real tail (~0s) instead of inheriting
                // a stale or zero baseline that would inflate TimeListening.
                if (newSessionId != null) {
                    lastSyncTimestamp = System.currentTimeMillis()
                }
            }

            Log.d(TAG, "loadAudioBook: OK local=$isLocal pos=$startPosition dur=${_duration.value} tracks=${player.mediaItemCount}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "loadAudioBook: FAILED: ${e.message}", e)
            _playbackState.value = PlaybackState.STOPPED
            _events.tryEmit(PlaybackEvent.Error("Failed to load: ${e.message}"))
            return false
        }
    }

    @OptIn(UnstableApi::class)
    private fun loadLocalTracks(player: ExoPlayer, book: AudioBook, metadata: MediaMetadata) {
        // Scanned local-library books store SAF content:// URIs, not filesystem paths.
        // They must be parsed as URIs; File()/Uri.fromFile() would produce invalid file:///content:/... URIs.
        if (book.isLocal) {
            loadScannedLocalTracks(player, book, metadata)
            return
        }

        val localPath = book.localPath ?: return
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
                    cumulative += af.duration.toDouble(kotlin.time.DurationUnit.SECONDS)
                    durations.add(cumulative)
                }
            }
        } else if (localDir != null && localDir.isDirectory) {
            // No audio file metadata — scan the directory for audio files
            val audioExtensions = setOf("mp3", "m4a", "m4b", "opus", "ogg", "flac", "aac", "wma", "wav")
            val files = localDir.listFiles()
                ?.filter { f -> f.isFile && f.extension.lowercase() in audioExtensions }
                ?.sortedBy { it.name }
                ?: emptyList()

            for (file in files) {
                mediaItems.add(
                    MediaItem.Builder()
                        .setUri(Uri.fromFile(file))
                        .setMediaMetadata(metadata)
                        .build()
                )
                // No duration metadata available — ExoPlayer will determine it
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

        trackDurations = durations
        player.setMediaItems(mediaItems)
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
    private suspend fun loadStreamTracks(player: ExoPlayer, book: AudioBook, metadata: MediaMetadata) {
        val session = sessionMutex.withLock { currentSession }
        val serverUrl = settingsManager.currentSettings.serverUrl.trimEnd('/')
        val token = settingsManager.getAuthToken() ?: ""

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

        trackDurations = durations

        val mediaSources: List<MediaSource> = mediaItems.map { item ->
            mediaSourceFactory.createMediaSource(item)
        }

        player.setMediaSources(mediaSources)
    }

    // ─── Playback Controls ────────────────────────────────────────────────

    fun play() {
        exoPlayer?.let { player ->
            // ── Auto-Rewind ───────────────────────────────────────────
            val settings = settingsManager.currentSettings
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
            // ── Resume ────────────────────────────────────────────────
            if (player.playbackState == Player.STATE_ENDED) {
                // Player reached end-of-book — re-prepare to allow seeking/playing
                player.seekTo(player.currentMediaItemIndex, player.currentPosition)
                player.prepare()
                // prepare() moves the player to BUFFERING, so the STATE_READY
                // branch below is skipped and the listener takes over. Reflect the
                // resume immediately so the UI does not stay showing STOPPED while
                // it buffers.
                _playbackState.value = PlaybackState.BUFFERING
            }
            player.playWhenReady = true
            // If already in STATE_READY, start immediately
            if (player.playbackState == Player.STATE_READY) {
                _playbackState.value = PlaybackState.PLAYING
                startPositionPolling()
                startSessionSync()
            }
            // Otherwise the player listener will handle the transition when ready
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
        exoPlayer?.let { player ->
            player.playWhenReady = false
            _playbackState.value = PlaybackState.PAUSED
            pausedAtTimestamp = System.currentTimeMillis()
            stopPositionPolling()
            stopSessionSync()
            scope.launch(Dispatchers.IO) { syncProgressNow() }
        }
    }

    fun stop() {
        Log.d(TAG, "stop: book=${_currentBook.value?.title} pos=${_position.value}")
        pausedAtTimestamp = null
        stopPositionPolling()
        stopSessionSync()

        val book = _currentBook.value
        val pos = _position.value
        val dur = _duration.value

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
        val capturedLocalSessionId = currentLocalSessionId
        val capturedLocalAccum = localSessionAccumSec + finalLocalSessionTickSec()
        val capturedLocalUpdatedAt = System.currentTimeMillis()
        currentLocalSessionId = null
        localSessionAccumSec = 0.0

        // Flush progress in the background AFTER releasing the player.
        // All values were captured above so no player access is needed.
        if (book != null) {
            val isFinished = dur > Duration.ZERO && pos >= (dur - 1.seconds).coerceAtLeast(Duration.ZERO)
            scope.launch(Dispatchers.IO) {
                try {
                    syncProgressNow()
                } catch (_: Exception) {}
                // Flush progress through SyncManager (handles offline queue)
                syncManager.flushPlaybackProgress(
                    itemId = book.id,
                    currentTime = pos.toDouble(kotlin.time.DurationUnit.SECONDS),
                    isFinished = isFinished,
                    duration = dur.toDouble(kotlin.time.DurationUnit.SECONDS),
                )
                closeSession()
                // Explicit final write for the captured local session (syncProgressNow
                // above no longer touches it because currentLocalSessionId is null).
                if (capturedLocalSessionId != null) {
                    try {
                        sessionRepository.updateLocalSession(
                            id = capturedLocalSessionId,
                            timeListeningSec = capturedLocalAccum,
                            currentTimeSec = pos.toDouble(kotlin.time.DurationUnit.SECONDS),
                            updatedAt = capturedLocalUpdatedAt,
                        )
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun seekTo(position: Duration) {
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
        _speed.value = speed.coerceIn(0.5f, 3.0f)
        exoPlayer?.playbackParameters = PlaybackParameters(_speed.value)
    }

    fun setVolume(vol: Float) {
        _volume.value = vol.coerceIn(0f, 1f)
        exoPlayer?.volume = _volume.value
    }

    fun setEqEnabled(enabled: Boolean) {
        _eqEnabled.value = enabled
        equalizer?.enabled = enabled
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
        _volumeBoost.value = gainMb.coerceIn(0, 1000)
        loudnessEnhancer?.let {
            it.setTargetGain(_volumeBoost.value)
            it.enabled = _volumeBoost.value > 0
        }
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

        // If the player is in STATE_ENDED (book finished), re-prepare before seeking
        val wasEnded = exoPlayer?.playbackState == Player.STATE_ENDED
        seekTo(chapter.startTime)
        _currentChapter.value = chapter
        _currentChapterIndex.value = chapterIndex
        chapterPlayer?.currentChapter = chapter
        chapterPlayer?.currentChapterIndex = chapterIndex

        if (wasEnded) {
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true
            _playbackState.value = PlaybackState.PLAYING
            startPositionPolling()
            startSessionSync()
        }
    }

    // ─── Position Calculation (multi-track aware) ─────────────────────────

    private fun seekToPosition(position: Duration) {
        val player = exoPlayer ?: return
        val posSeconds = position.toDouble(kotlin.time.DurationUnit.SECONDS)

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
                    _playbackState.value = PlaybackState.BUFFERING
                }
                Player.STATE_READY -> {
                    if (exoPlayer?.playWhenReady == true) {
                        _playbackState.value = PlaybackState.PLAYING
                        startPositionPolling()
                        startSessionSync()
                    } else {
                        _playbackState.value = PlaybackState.PAUSED
                    }
                }
                Player.STATE_ENDED -> {
                    // End of all tracks
                    stopPositionPolling()
                    stopSessionSync()
                    _playbackState.value = PlaybackState.STOPPED
                    stopPlaybackService()

                    val book = _currentBook.value
                    val finalLocalSessionId = currentLocalSessionId
                    // Include the partial interval since the last heartbeat so end-of-book
                    // doesn't drop up to one tick worth of listen time.
                    val finalLocalAccum = localSessionAccumSec + finalLocalSessionTickSec()
                    val finalLocalUpdatedAt = System.currentTimeMillis()
                    if (book != null) {
                        val durSecs = _duration.value.toDouble(kotlin.time.DurationUnit.SECONDS)
                        scope.launch(Dispatchers.IO) {
                            // Flush through SyncManager (handles both local save + server/offline queue)
                            syncManager.flushPlaybackProgress(
                                itemId = book.id,
                                currentTime = durSecs,
                                isFinished = true,
                                duration = durSecs,
                            )
                            closeSession()
                            // Persist a final state for the local session, if any.
                            if (finalLocalSessionId != null) {
                                try {
                                    sessionRepository.updateLocalSession(
                                        id = finalLocalSessionId,
                                        timeListeningSec = finalLocalAccum,
                                        currentTimeSec = durSecs,
                                        updatedAt = finalLocalUpdatedAt,
                                    )
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    // Drop the active local session id so subsequent playback opens a fresh row.
                    currentLocalSessionId = null
                    localSessionAccumSec = 0.0
                    _events.tryEmit(PlaybackEvent.BookFinished)
                }
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
            stopPositionPolling()
            stopSessionSync()
            _playbackState.value = PlaybackState.STOPPED
            stopPlaybackService()
            _events.tryEmit(PlaybackEvent.Error("Playback error: ${error.message}"))
        }
    }

    // ─── Position Polling ─────────────────────────────────────────────────

    private fun startPositionPolling() {
        positionPollingJob?.cancel()
        positionPollingJob = scope.launch {
            while (isActive) {
                delay(500)
                val pos = getCurrentPosition()
                _position.value = pos
                chapterPlayer?.absolutePositionMs = pos.inWholeMilliseconds
                updateCurrentChapter(pos)

                // Report position to SyncManager for throttled server pushes.
                // Calling sequentially (not launch) prevents unbounded coroutine accumulation.
                // Room and network calls inside reportPlaybackPosition handle their own dispatchers.
                val book = _currentBook.value
                val dur = _duration.value
                if (book != null && dur > Duration.ZERO) {
                    try {
                        syncManager.reportPlaybackPosition(
                            itemId = book.id,
                            currentTime = pos.toDouble(kotlin.time.DurationUnit.SECONDS),
                            duration = dur.toDouble(kotlin.time.DurationUnit.SECONDS),
                            isFinished = false,
                        )
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun stopPositionPolling() {
        positionPollingJob?.cancel()
        positionPollingJob = null
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
        lastSyncTimestamp = System.currentTimeMillis()
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
        val book = _currentBook.value ?: return
        val pos = _position.value
        val dur = _duration.value
        val posSec = pos.toDouble(kotlin.time.DurationUnit.SECONDS)
        val durSec = dur.toDouble(kotlin.time.DurationUnit.SECONDS)
        val progressFraction = if (durSec > 0) (posSec / durSec).coerceIn(0.0, 1.0) else 0.0

        // Save to PlaybackProgress table
        try {
            progressRepository.savePlaybackProgress(
                audioBookId = book.id,
                position = pos,
                isFinished = false,
            )
        } catch (_: Exception) {}

        // Also update AudioBook entity so position persists across loads
        try {
            audioBookDao.updateProgress(
                id = book.id,
                currentTimeSeconds = posSec,
                progress = progressFraction,
                isFinished = 0,
            )
        } catch (_: Exception) {}

        // Local-mode session heartbeat: accumulate listen time on the open local session row.
        // Same elapsed-since-last-tick math as the server path below; uses the shared
        // lastSyncTimestamp so we never double-count when switching between server and local.
        val localSessionId = currentLocalSessionId
        if (book.isLocal && localSessionId != null) {
            try {
                val now = System.currentTimeMillis()
                val rawElapsed = (now - lastSyncTimestamp).coerceAtLeast(0) / 1000.0
                // Cap individual ticks to ignore background gaps / doze sleep.
                val elapsed = rawElapsed.coerceAtMost(localSessionMaxTickSec)
                lastSyncTimestamp = now
                localSessionAccumSec += elapsed
                sessionRepository.updateLocalSession(
                    id = localSessionId,
                    timeListeningSec = localSessionAccumSec,
                    currentTimeSec = posSec,
                    updatedAt = now,
                )
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
        val tick: ServerSyncTick? = sessionMutex.withLock {
            val session = currentSession
            if (session == null) {
                null
            } else {
                val now = System.currentTimeMillis()
                val elapsed = (now - lastSyncTimestamp).coerceAtLeast(0) / 1000.0
                lastSyncTimestamp = now
                accumulatedListenTime += elapsed
                ServerSyncTick(session.id, accumulatedListenTime)
            }
        }
        if (tick != null) {
            try {
                apiService.syncSessionProgress(
                    sessionId = tick.sessionId,
                    currentTime = pos.toDouble(kotlin.time.DurationUnit.SECONDS),
                    duration = dur.toDouble(kotlin.time.DurationUnit.SECONDS),
                    timeListened = tick.timeListened,
                )
            } catch (_: Exception) {}
        } else {
            // Enqueue for later sync
            try {
                progressRepository.enqueuePendingProgress(
                    book.id,
                    pos.toDouble(kotlin.time.DurationUnit.SECONDS),
                    isFinished = false,
                )
            } catch (_: Exception) {}
        }
    }

    private suspend fun closeSession() {
        val session = sessionMutex.withLock { currentSession } ?: return
        try {
            apiService.closeSession(session.id)
        } catch (_: Exception) {}
        sessionMutex.withLock { currentSession = null }
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
        if (_playbackState.value == PlaybackState.STOPPED) return

        scope.launch(Dispatchers.IO) {
            val session = sessionMutex.withLock { currentSession } ?: return@launch
            Log.d(TAG, "recoverIfSessionStale: testing session ${session.id} for '${book.title}'")

            try {
                val success = apiService.syncSessionProgress(
                    sessionId = session.id,
                    currentTime = _position.value.toDouble(kotlin.time.DurationUnit.SECONDS),
                    duration = _duration.value.toDouble(kotlin.time.DurationUnit.SECONDS),
                )
                if (success) {
                    Log.d(TAG, "recoverIfSessionStale: session still valid")
                } else {
                    Log.w(TAG, "recoverIfSessionStale: session stale, recovering...")
                    recoverStaleSession(book)
                }
            } catch (e: Exception) {
                Log.w(TAG, "recoverIfSessionStale: sync failed (${e.message}), recovering...")
                recoverStaleSession(book)
            }
        }
    }

    /**
     * Replace a dead server session with a fresh one.
     * If the server is still unreachable, clear the session so progress
     * falls back to the offline queue (SyncManager / PendingProgress).
     */
    private suspend fun recoverStaleSession(book: AudioBook) {
        try {
            val newSession = apiService.startPlaybackSession(book.id)
            if (newSession != null) {
                sessionMutex.withLock {
                    currentSession = newSession
                    accumulatedListenTime = 0.0
                    lastSyncTimestamp = System.currentTimeMillis()
                }
                Log.d(TAG, "recoverStaleSession: OK newSessionId=${newSession.id}")
            } else {
                Log.w(TAG, "recoverStaleSession: server returned null — falling back to offline queue")
                sessionMutex.withLock { currentSession = null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "recoverStaleSession: failed (${e.message}) — falling back to offline queue")
            sessionMutex.withLock { currentSession = null }
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
            builder.setArtworkUri(Uri.parse(book.effectiveCoverPath))

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

    private fun stopPlaybackService() {
        try {
            mediaController?.release()
            mediaController = null
            mediaControllerFuture?.cancel(true)
            mediaControllerFuture = null
            val intent = Intent(context, PlaybackService::class.java)
            context.stopService(intent)
            Log.d(TAG, "Stopped PlaybackService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop PlaybackService: ${e.message}", e)
        }
    }
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
