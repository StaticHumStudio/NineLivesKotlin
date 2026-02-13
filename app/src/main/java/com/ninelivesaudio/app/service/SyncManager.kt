package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.dao.PlaybackProgressDao
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.data.repository.LibraryRepository
import com.ninelivesaudio.app.data.repository.ProgressRepository
import com.ninelivesaudio.app.domain.model.AudioBook
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages periodic synchronization with the Audiobookshelf server.
 *
 * Responsibilities:
 * - Periodic sync of libraries and audiobooks from server
 * - Progress sync (pull from server, push offline queue)
 * - Offline queue flushing on reconnect
 * - Throttled position reporting during playback
 *
 * Port of C# SyncService.cs.
 */
@Singleton
class SyncManager @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val audioBookRepository: AudioBookRepository,
    private val progressRepository: ProgressRepository,
    private val audioBookDao: AudioBookDao,
    private val playbackProgressDao: PlaybackProgressDao,
    private val connectivityMonitor: ConnectivityMonitor,
    private val settingsManager: SettingsManager,
) {
    companion object {
        private const val INITIAL_DELAY_MS = 500L             // 0.5s — populate home screen fast
        private const val DEFAULT_SYNC_INTERVAL_MS = 300_000L // 5 minutes
        private const val MIN_SYNC_INTERVAL_MS = 30_000L     // 30 seconds between position pushes
        private const val MIN_POSITION_DELTA = 2.0            // seconds
        private const val MIN_PROGRESS_DELTA = 0.01           // 1%
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    // Sync state
    private var syncJob: Job? = null
    private var connectivityJob: Job? = null
    @Volatile private var activeItemId: String? = null

    // Throttle state for position reporting
    private var lastSyncedTime: Double = 0.0
    private var lastSyncTimestamp: Long = 0L

    // ─── Events ──────────────────────────────────────────────────────────────

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncCompleted = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 4)
    val syncCompleted: SharedFlow<Unit> = _syncCompleted.asSharedFlow()

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Start the periodic sync timer.
     * Call this once when the app is initialized and authenticated.
     */
    fun start() {
        stop()
        syncJob = scope.launch {
            // Fast first sync
            delay(INITIAL_DELAY_MS)
            syncNow()

            // Periodic sync
            while (isActive) {
                delay(DEFAULT_SYNC_INTERVAL_MS)
                syncNow()
            }
        }

        // Also flush offline queue when connectivity returns
        connectivityJob?.cancel()
        connectivityJob = scope.launch {
            connectivityMonitor.connectionStatus.collect { status ->
                if (status == ConnectivityMonitor.ConnectionStatus.CONNECTED) {
                    flushOfflineQueue()
                }
            }
        }
    }

    /** Stop the periodic sync timer and connectivity listener. */
    fun stop() {
        syncJob?.cancel()
        syncJob = null
        connectivityJob?.cancel()
        connectivityJob = null
    }

    // ─── Sync Operations ─────────────────────────────────────────────────────

    /**
     * Execute a full sync: libraries + audiobooks + progress.
     * Thread-safe via Mutex.
     */
    suspend fun syncNow() {
        if (!hasAuthToken()) return

        // Prevent concurrent syncs
        if (!syncMutex.tryLock()) return

        try {
            _isSyncing.value = true
            connectivityMonitor.setSyncing(true)

            // Progress sync FIRST — this populates the home screen grid immediately.
            // Library sync runs after (heavier, fetches all book metadata).
            syncProgress()
            syncLibraries()

            _syncCompleted.tryEmit(Unit)
        } catch (e: Exception) {
            // Non-fatal — log and continue
        } finally {
            _isSyncing.value = false
            connectivityMonitor.setSyncing(false)
            syncMutex.unlock()
        }
    }

    /**
     * Sync libraries and their audiobooks from the server.
     */
    private suspend fun syncLibraries() {
        try {
            // Sync libraries
            val libraries = libraryRepository.syncFromServer()
            if (libraries.isEmpty()) return

            // Sync audiobooks for each library
            for (library in libraries) {
                try {
                    // syncLibraryItems already preserves local download state
                    audioBookRepository.syncLibraryItems(library.id)
                } catch (_: Exception) {
                    // Continue with next library on failure
                }
            }
        } catch (_: Exception) {
            // Non-fatal
        }
    }

    /**
     * Sync progress from the server.
     * Server is source of truth (except for actively playing items).
     */
    private suspend fun syncProgress() {
        try {
            val serverProgressList = progressRepository.fetchAllProgressFromServer()
            if (serverProgressList.isEmpty()) return

            val activeId = activeItemId

            for (progress in serverProgressList) {
                // Skip the actively-playing item — the session owns its progress
                if (progress.libraryItemId == activeId) continue

                try {
                    var book = audioBookDao.getById(progress.libraryItemId)
                    if (book == null) {
                        val remoteBook = audioBookRepository.fetchFromServer(progress.libraryItemId)
                        if (remoteBook != null) {
                            audioBookDao.upsert(remoteBook.toEntity())
                            book = audioBookDao.getById(progress.libraryItemId)
                        }
                    }

                    val currentTimeSecs = progress.currentTime.inWholeMilliseconds / 1000.0
                    val positionSeconds = if (currentTimeSecs > 0) {
                        currentTimeSecs
                    } else {
                        // Estimate from progress fraction × duration
                        if (book != null && book.durationSeconds > 0) {
                            progress.progress * book.durationSeconds
                        } else 0.0
                    }

                    val updatedAtStr = progress.lastUpdate?.let { ts ->
                        Instant.ofEpochMilli(ts)
                            .atOffset(ZoneOffset.UTC)
                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    }

                    // Save server progress to local DB
                    playbackProgressDao.upsert(
                        PlaybackProgressEntity(
                            audioBookId = progress.libraryItemId,
                            positionSeconds = positionSeconds,
                            isFinished = if (progress.isFinished) 1 else 0,
                            updatedAt = updatedAtStr,
                        )
                    )

                    // Also update audiobook entity progress
                    if (book != null) {
                        audioBookDao.upsert(
                            book.copy(
                                currentTimeSeconds = positionSeconds,
                                progress = progress.progress,
                                isFinished = if (progress.isFinished) 1 else 0,
                            )
                        )
                    }
                } catch (_: Exception) {
                    // Continue with next item
                }
            }
        } catch (_: Exception) {
            // Non-fatal
        }
    }

    // ─── Position Reporting (from PlaybackManager) ───────────────────────────

    /**
     * Mark the currently playing item.
     * Called by PlaybackManager when a new audiobook starts playing.
     */
    fun setActivePlaybackItem(itemId: String?) {
        activeItemId = itemId
        lastSyncedTime = 0.0
        lastSyncTimestamp = 0L
    }

    /**
     * Report playback position with throttling.
     * Always saves locally. Only pushes to server if throttle conditions are met.
     */
    suspend fun reportPlaybackPosition(
        itemId: String,
        currentTime: Double,
        duration: Double,
        isFinished: Boolean,
    ) {
        val progress = if (duration > 0) (currentTime / duration).coerceIn(0.0, 1.0) else 0.0

        // Always save locally (crash safety)
        progressRepository.savePlaybackProgress(
            audioBookId = itemId,
            position = currentTime.seconds,
            isFinished = isFinished,
        )

        // Update audiobook entity
        try {
            val book = audioBookDao.getById(itemId)
            if (book != null) {
                audioBookDao.upsert(
                    book.copy(
                        currentTimeSeconds = currentTime,
                        progress = progress,
                        isFinished = if (isFinished) 1 else 0,
                    )
                )
            }
        } catch (e: Exception) {
            // Non-fatal: progress already saved to PlaybackProgress table
        }

        // Throttle network pushes
        val now = System.currentTimeMillis()
        val timeSinceLastSync = now - lastSyncTimestamp
        val positionDelta = kotlin.math.abs(currentTime - lastSyncedTime)
        val lastSyncedProgress = lastSyncedTime / duration.coerceAtLeast(1.0)
        val shouldSync = isFinished ||
                (timeSinceLastSync >= MIN_SYNC_INTERVAL_MS &&
                        (positionDelta >= MIN_POSITION_DELTA || (progress - lastSyncedProgress) >= MIN_PROGRESS_DELTA))

        if (shouldSync && connectivityMonitor.isOnline.value) {
            try {
                val success = progressRepository.pushProgressToServer(itemId, currentTime, isFinished)
                if (success) {
                    lastSyncedTime = currentTime
                    lastSyncTimestamp = now
                }
            } catch (_: Exception) {
                // Non-fatal: will sync later
            }
        }
    }

    /**
     * Force-push final position on playback stop.
     * If offline, enqueue for later.
     */
    suspend fun flushPlaybackProgress(
        itemId: String,
        currentTime: Double,
        isFinished: Boolean,
    ) {
        // Always save locally first
        progressRepository.savePlaybackProgress(
            audioBookId = itemId,
            position = currentTime.seconds,
            isFinished = isFinished,
        )

        if (connectivityMonitor.isOnline.value) {
            try {
                progressRepository.pushProgressToServer(itemId, currentTime, isFinished)
            } catch (_: Exception) {
                // Failed → enqueue
                progressRepository.enqueuePendingProgress(itemId, currentTime, isFinished)
            }
        } else {
            // Offline → enqueue for later
            progressRepository.enqueuePendingProgress(itemId, currentTime, isFinished)
        }

        // Clear active item
        if (activeItemId == itemId) {
            activeItemId = null
        }
    }

    // ─── Offline Queue ───────────────────────────────────────────────────────

    /**
     * Flush all pending progress updates to the server.
     * Called on reconnect.
     */
    private suspend fun flushOfflineQueue() {
        if (!hasAuthToken()) return
        try {
            val count = progressRepository.getPendingProgressCount()
            if (count > 0) {
                progressRepository.flushPendingProgress()
            }
        } catch (_: Exception) {
            // Will try again on next reconnect
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private suspend fun hasAuthToken(): Boolean {
        return settingsManager.getAuthToken()?.isNotEmpty() == true
    }
}
