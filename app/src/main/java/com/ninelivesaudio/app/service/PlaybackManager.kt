package com.ninelivesaudio.app.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.util.Log
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
import com.ninelivesaudio.app.data.repository.ProgressRepository
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Chapter
import com.ninelivesaudio.app.domain.model.PlaybackSessionInfo
import com.ninelivesaudio.app.MainActivity
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
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
    private val syncManagerLazy: Lazy<SyncManager>,
) {
    companion object {
        private const val TAG = "PlaybackManager"
    }

    private val syncManager: SyncManager get() = syncManagerLazy.get()
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var mediaController: MediaController? = null
    private var playbackService: PlaybackService? = null

    /** Expose the current ExoPlayer instance. */
    fun getPlayer(): ExoPlayer? = exoPlayer

    /** Expose the current MediaSession for PlaybackService.onGetSession(). */
    fun getMediaSession(): MediaSession? = mediaSession

    /** Called by PlaybackService when it's created/destroyed. */
    fun setPlaybackService(service: PlaybackService?) {
        playbackService = service
    }

    // ─── Coroutine Scope ──────────────────────────────────────────────────
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionPollingJob: Job? = null
    private var sessionSyncJob: Job? = null

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

    private val _currentChapterIndex = MutableStateFlow(-1)
    val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _volume = MutableStateFlow(0.8f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _isLocalFile = MutableStateFlow(false)
    val isLocalFile: StateFlow<Boolean> = _isLocalFile.asStateFlow()

    // Events
    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    // ─── Internal State ───────────────────────────────────────────────────

    private var chapters: List<Chapter> = emptyList()
    private var currentSession: PlaybackSessionInfo? = null
    private var accumulatedListenTime: Double = 0.0
    private var lastSyncTimestamp: Long = 0L

    // Track durations for position calculation
    private var trackDurations: List<Double> = emptyList() // cumulative seconds

    // ─── Load ─────────────────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    suspend fun loadAudioBook(book: AudioBook): Boolean {
        // ExoPlayer must be created and accessed from the Main thread
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "loadAudioBook must be called from the Main thread"
        }
        var effectiveBook = book
        try {
            Log.d(TAG, "loadAudioBook: '${book.title}' isDownloaded=${book.isDownloaded}")
            _playbackState.value = PlaybackState.LOADING
            _currentBook.value = book
            chapters = book.chapters.sortedBy { it.start }
            _currentChapter.value = null
            _currentChapterIndex.value = -1
            accumulatedListenTime = 0.0

            // Release previous player
            releasePlayer()

            // Start server session (must complete before building stream URLs)
            currentSession = null
            withContext(Dispatchers.IO) {
                try {
                    val session = apiService.startPlaybackSession(book.id)
                    if (session != null) {
                        currentSession = session
                        if (chapters.isEmpty() && session.chapters.isNotEmpty()) {
                            chapters = session.chapters.sortedBy { it.start }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "loadAudioBook: session error: ${e.message}", e)
                }
            }

            // If no session audio tracks AND no local audio files, fetch full book details
            val sessionHasTracks = currentSession?.audioTracks?.isNotEmpty() == true
            if (!sessionHasTracks && effectiveBook.audioFiles.isEmpty() && !(effectiveBook.isDownloaded && !effectiveBook.localPath.isNullOrEmpty())) {
                withContext(Dispatchers.IO) {
                    try {
                        val fullBook = apiService.getAudioBook(book.id)
                        if (fullBook != null && fullBook.audioFiles.isNotEmpty()) {
                            effectiveBook = fullBook.copy(currentTime = book.currentTime, progress = book.progress)
                            _currentBook.value = effectiveBook
                            if (chapters.isEmpty() && fullBook.chapters.isNotEmpty()) {
                                chapters = fullBook.chapters.sortedBy { it.start }
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
                val sessionTime = (currentSession?.currentTime ?: 0.0).seconds
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

            // Determine source and load
            val isLocal = effectiveBook.isDownloaded && !effectiveBook.localPath.isNullOrEmpty()
            _isLocalFile.value = isLocal

            // Create ExoPlayer with proper audio attributes and focus handling
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .setUsage(C.USAGE_MEDIA)
                .build()

            val player = ExoPlayer.Builder(context)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .build()
            exoPlayer = player

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

            // Listen for player events
            player.addListener(createPlayerListener())

            // Seek to saved position before prepare
            if (startPosition > Duration.ZERO) {
                seekToPosition(startPosition)
            }

            // Create MediaSession BEFORE prepare() so it captures all state transitions.
            // This is critical: Media3's automatic notification system only works when the
            // session exists with a bound player when state changes happen.
            mediaSession?.release()
            mediaSession = MediaSession.Builder(context, player)
                .setSessionActivity(createSessionPendingIntent())
                .build()

            // Start the foreground service — it retrieves the session via getMediaSession().
            // Media3 MediaSessionService handles startForeground() automatically.
            startPlaybackService()

            // If the service was already running, it won't get a new onCreate().
            // Explicitly add the new session so Media3 refreshes the notification.
            playbackService?.refreshSession(mediaSession!!)

            // NOW prepare — MediaSession exists to receive all state transitions
            player.playWhenReady = true
            player.prepare()

            // Update duration
            _duration.value = calculateTotalDuration(effectiveBook)

            _events.tryEmit(PlaybackEvent.BookLoaded(effectiveBook))

            // Notify SyncManager of active playback item (prevents sync overwriting position)
            syncManager.setActivePlaybackItem(effectiveBook.id)

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
        val localPath = book.localPath ?: return
        val localFile = File(localPath)

        val mediaItems = mutableListOf<MediaItem>()
        val durations = mutableListOf<Double>()
        var cumulative = 0.0

        // Determine the download directory
        val localDir = if (localFile.isDirectory) localFile else localFile.parentFile

        if (book.audioFiles.isNotEmpty()) {
            // We have audio file metadata — use it for ordered multi-track loading
            if (book.audioFiles.size == 1 && localFile.isFile) {
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
                            .setUri(Uri.fromFile(File(path)))
                            .setMediaMetadata(metadata)
                            .build()
                    )
                    cumulative += af.duration.inWholeMilliseconds / 1000.0
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
        } else if (localFile.isFile) {
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

    @OptIn(UnstableApi::class)
    private suspend fun loadStreamTracks(player: ExoPlayer, book: AudioBook, metadata: MediaMetadata) {
        val session = currentSession
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
                val url = "$serverUrl/api/items/${book.id}/file/${af.ino}"
                mediaItems.add(
                    MediaItem.Builder()
                        .setUri(url)
                        .setMediaMetadata(metadata)
                        .build()
                )
                cumulative += af.duration.inWholeMilliseconds / 1000.0
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

    fun pause() {
        exoPlayer?.let { player ->
            player.playWhenReady = false
            _playbackState.value = PlaybackState.PAUSED
            stopPositionPolling()
            stopSessionSync()
            scope.launch(Dispatchers.IO) { syncProgressNow() }
        }
    }

    fun stop() {
        stopPositionPolling()
        stopSessionSync()

        val book = _currentBook.value
        val pos = _position.value
        val dur = _duration.value

        // Release player and update state immediately so the UI reflects stopped state.
        releasePlayer()
        stopPlaybackService()
        _playbackState.value = PlaybackState.STOPPED

        // Flush progress in the background AFTER releasing the player.
        // All values were captured above so no player access is needed.
        if (book != null) {
            val isFinished = dur > Duration.ZERO && pos >= dur - 1.seconds
            scope.launch(Dispatchers.IO) {
                try {
                    syncProgressNow()
                } catch (_: Exception) {}
                // Flush progress through SyncManager (handles offline queue)
                syncManager.flushPlaybackProgress(
                    itemId = book.id,
                    currentTime = pos.inWholeMilliseconds / 1000.0,
                    isFinished = isFinished,
                )
                closeSession()
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

    fun seekToChapter(chapterIndex: Int) {
        if (chapterIndex < 0 || chapterIndex >= chapters.size) return
        val chapter = chapters[chapterIndex]
        seekTo(chapter.startTime)
        _currentChapter.value = chapter
        _currentChapterIndex.value = chapterIndex
    }

    // ─── Position Calculation (multi-track aware) ─────────────────────────

    private fun seekToPosition(position: Duration) {
        val player = exoPlayer ?: return
        val posSeconds = position.inWholeMilliseconds / 1000.0

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

    private fun createPlayerListener() = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
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

                    val book = _currentBook.value
                    if (book != null) {
                        val durSecs = _duration.value.inWholeMilliseconds / 1000.0
                        scope.launch(Dispatchers.IO) {
                            // Flush through SyncManager (handles both local save + server/offline queue)
                            syncManager.flushPlaybackProgress(
                                itemId = book.id,
                                currentTime = durSecs,
                                isFinished = true,
                            )
                            closeSession()
                        }
                    }
                    _events.tryEmit(PlaybackEvent.BookFinished)
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Track changed (multi-track auto-advance)
            val idx = exoPlayer?.currentMediaItemIndex ?: 0
            _events.tryEmit(PlaybackEvent.TrackChanged(idx))
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "onPlayerError: ${error.errorCodeName} — ${error.message}", error)
            _playbackState.value = PlaybackState.STOPPED
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
                updateCurrentChapter(pos)

                // Report position to SyncManager for throttled server pushes.
                // Uses withContext (not launch) to prevent unbounded coroutine accumulation.
                val book = _currentBook.value
                val dur = _duration.value
                if (book != null && dur > Duration.ZERO) {
                    withContext(Dispatchers.IO) {
                        try {
                            syncManager.reportPlaybackPosition(
                                itemId = book.id,
                                currentTime = pos.inWholeMilliseconds / 1000.0,
                                duration = dur.inWholeMilliseconds / 1000.0,
                                isFinished = false,
                            )
                        } catch (_: Exception) {}
                    }
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
        if (chapters.isEmpty()) return

        val posSeconds = position.inWholeMilliseconds / 1000.0
        var newIndex = -1
        for (i in chapters.indices) {
            if (posSeconds >= chapters[i].start && posSeconds < chapters[i].end) {
                newIndex = i
                break
            }
        }

        if (newIndex != _currentChapterIndex.value) {
            _currentChapterIndex.value = newIndex
            _currentChapter.value = if (newIndex >= 0) chapters[newIndex] else null
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
        val posSec = pos.inWholeMilliseconds / 1000.0
        val durSec = dur.inWholeMilliseconds / 1000.0
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

        // Sync to server session
        val session = currentSession
        if (session != null) {
            try {
                val now = System.currentTimeMillis()
                val elapsed = (now - lastSyncTimestamp).coerceAtLeast(0) / 1000.0
                lastSyncTimestamp = now
                accumulatedListenTime += elapsed
                apiService.syncSessionProgress(
                    sessionId = session.id,
                    currentTime = pos.inWholeMilliseconds / 1000.0,
                    duration = dur.inWholeMilliseconds / 1000.0,
                    timeListened = accumulatedListenTime,
                )
            } catch (_: Exception) {}
        } else {
            // Enqueue for later sync
            try {
                progressRepository.enqueuePendingProgress(
                    book.id,
                    pos.inWholeMilliseconds / 1000.0,
                    isFinished = false,
                )
            } catch (_: Exception) {}
        }
    }

    private suspend fun closeSession() {
        val session = currentSession ?: return
        try {
            apiService.closeSession(session.id)
        } catch (_: Exception) {}
        currentSession = null
    }

    // ─── Cleanup ──────────────────────────────────────────────────────────

    private fun releasePlayer() {
        val session = mediaSession
        if (session != null) {
            // Remove the session from the service before releasing so Media3 clears the notification
            playbackService?.removeSession(session)
            session.release()
            mediaSession = null
        }
        exoPlayer?.let { player ->
            player.stop()
            player.release()
        }
        exoPlayer = null
    }

    fun release() {
        stopPositionPolling()
        stopSessionSync()
        releasePlayer()
        stopPlaybackService()
        scope.cancel()
    }

    // ─── Media Metadata ───────────────────────────────────────────────────────

    private fun buildMediaMetadata(book: AudioBook): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(book.author)
            .setAlbumTitle(book.title)
            .setDisplayTitle(book.title)
            .setSubtitle(book.author)

        // Set cover art URI so the notification and lock screen show album art
        if (!book.coverPath.isNullOrEmpty()) {
            builder.setArtworkUri(Uri.parse(book.coverPath))
        }

        return builder.build()
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
            val sessionToken = SessionToken(context, android.content.ComponentName(context, PlaybackService::class.java))
            val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
            controllerFuture.addListener({
                try {
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
            val intent = Intent(context, PlaybackService::class.java)
            context.stopService(intent)
            Log.d(TAG, "Stopped PlaybackService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop PlaybackService: ${e.message}", e)
        }
    }
}

// ─── Events ───────────────────────────────────────────────────────────────

sealed class PlaybackEvent {
    data class BookLoaded(val book: AudioBook) : PlaybackEvent()
    data class TrackChanged(val index: Int) : PlaybackEvent()
    data class Error(val message: String) : PlaybackEvent()
    data object BookFinished : PlaybackEvent()
}
