package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.data.repository.LibraryRepository
import com.ninelivesaudio.app.data.repository.ProgressRepository
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AudioBook
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.ninelivesaudio.app.domain.util.toIso8601
import kotlin.time.Duration.Companion.seconds
import javax.inject.Inject
import javax.inject.Singleton

private const val MIN_POSITION_SYNC_INTERVAL_MS = 30_000L
private const val MIN_POSITION_DELTA = 2.0
private const val MIN_PROGRESS_DELTA = 0.01

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
    private val connectivityMonitor: ConnectivityMonitor,
    private val settingsManager: SettingsManager,
) {
    companion object {
        private const val INITIAL_DELAY_MS = 500L             // 0.5s — populate home screen fast
        private const val DEFAULT_SYNC_INTERVAL_MS = 300_000L // 5 minutes
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleOwner = SyncLifecycleOwner(scope)
    private val syncMutex = Mutex()
    private val activeItemMutationMutex = Mutex()

    // Sync state
    @Volatile private var activeItemId: String? = null

    private val playbackThrottleOwner = PlaybackThrottleOwner()

    // ─── Events ──────────────────────────────────────────────────────────────

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    /**
     * What the last library sync produced. Surfaced in the bug report body so
     * "it says it's syncing but nothing shows up" arrives with the answer
     * attached instead of needing a code trace.
     */
    internal val lastSync: StateFlow<SyncSnapshot?> = settingsManager.settings
        .map { it.syncSnapshotForCurrentServer() }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = settingsManager.currentSettings.syncSnapshotForCurrentServer(),
        )

    private val _syncCompleted = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 4)
    val syncCompleted: SharedFlow<Unit> = _syncCompleted.asSharedFlow()

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Start the periodic sync timer.
     * Call this once when the app is initialized and authenticated.
     */
    fun start() {
        lifecycleOwner.restart {
            launch {
                // Fast first sync
                delay(INITIAL_DELAY_MS)
                syncNow()

                // Periodic sync
                while (isActive) {
                    delay(DEFAULT_SYNC_INTERVAL_MS)
                    syncNow()
                    // Also retry the offline queue here. The rising-edge flush only
                    // fires once per reconnect, so a push that failed right after
                    // reconnect would otherwise sit queued until the next full
                    // disconnect/reconnect cycle.
                    flushOfflineQueue()
                }
            }

            // Flush offline queue on the rising edge of (connected AND not LOCAL).
            // Triggers on both connectivity returns and mode switches back to AUDIOBOOKSHELF
            // while already connected, so progress queued during LOCAL mode doesn't sit indefinitely.
            launch {
                combine(
                    connectivityMonitor.connectionStatus,
                    settingsManager.settings,
                ) { status, settings ->
                    status == ConnectivityMonitor.ConnectionStatus.CONNECTED &&
                            settings.appMode != AppMode.LOCAL
                }
                    .distinctUntilChanged()
                    .collect { canFlush ->
                        if (canFlush) flushOfflineQueue()
                    }
            }

            launch {
                var previousMode: AppMode? = null
                settingsManager.settings
                    .map { it.appMode }
                    .distinctUntilChanged()
                    .map { newMode ->
                        val transition = previousMode to newMode
                        previousMode = newMode
                        transition
                    }
                    .drop(1)
                    .collect { (oldMode, newMode) ->
                        if (oldMode != null && shouldReconnectForModeTransition(oldMode, newMode)) {
                            performModeSwitchReconnect(
                                refreshIsOnline = connectivityMonitor::refreshIsOnlineFromSystem,
                                syncNow = { syncNow() },
                            )
                        }
                    }
            }
        }
    }

    /** Stop the periodic sync timer and connectivity listener. */
    fun stop() {
        lifecycleOwner.stop()
    }

    // ─── Sync Operations ─────────────────────────────────────────────────────

    /**
     * Execute a full sync: libraries + audiobooks + progress.
     * Thread-safe via Mutex.
     *
     * Returns whether it actually attempted a sync. Callers that report the
     * outcome to the user (Settings' manual "Sync Now") must not treat a
     * [SyncAttempt.SKIPPED] return as success: nothing ran, so whatever
     * [SettingsManager.currentSettings].lastSync happens to hold is a leftover
     * from some earlier attempt, not this one's result.
     */
    internal suspend fun syncNow(): SyncAttempt {
        val settingsAtStart = settingsManager.currentSettings
        val serverUrlAtStart = settingsAtStart.serverUrl
        // Cheap pre-check: authenticated, non-LOCAL, and the OS reports a network.
        if (!shouldRunSync(
                isOnline = connectivityMonitor.isOnline.value,
                isLocalMode = settingsAtStart.appMode == AppMode.LOCAL,
                hasAuth = hasAuthToken(),
            )
        ) return SyncAttempt.SKIPPED_NOT_READY

        // Actually reach the server before committing to a sync. A live VPN
        // interface (e.g. Tailscale) keeps isOnline=true with no real connectivity,
        // so without this fast probe the sync fires and hangs on the full 30s
        // request timeout — the "still trying to sync" symptom in airplane mode.
        //
        // A failed probe still has to be recorded. Returning silently here
        // used to leave a fresh install on a live, unreachable network with
        // no LastSyncRecord at all, indistinguishable from never having
        // synced.
        if (!connectivityMonitor.checkServerReachable()) {
            var recorded = false
            settingsManager.updateSettings {
                val updated = it.withLastSyncIfServerUnchanged(
                    report = unreachableServerSyncReport(),
                    completedAtMs = System.currentTimeMillis(),
                    serverUrlAtStart = serverUrlAtStart,
                )
                recorded = updated !== it
                updated
            }
            return if (recorded) SyncAttempt.RAN else SyncAttempt.DISCARDED_SERVER_CHANGED
        }
        // The probe took real time. Re-check eligibility so a mode switch or
        // sign-out that landed mid-probe does not start a sync that should no
        // longer run.
        if (!shouldRunSync(
                isOnline = connectivityMonitor.isOnline.value,
                isLocalMode = settingsManager.currentSettings.appMode == AppMode.LOCAL,
                hasAuth = hasAuthToken(),
            )
        ) return SyncAttempt.SKIPPED_NOT_READY

        // Prevent concurrent syncs. A caller reporting this outcome to the
        // user must not read the mutex owner's eventual record as its own:
        // that record belongs to whichever sync got there first.
        if (!syncMutex.tryLock()) return SyncAttempt.SKIPPED_BUSY

        try {
            _isSyncing.value = true
            connectivityMonitor.setSyncing(true)

            // Progress sync FIRST — this populates the home screen grid immediately.
            // Library sync runs after (heavier, fetches all book metadata).
            syncProgress()
            val recorded = syncLibraries(serverUrlAtStart)

            _syncCompleted.tryEmit(Unit)
            return if (recorded) SyncAttempt.RAN else SyncAttempt.DISCARDED_SERVER_CHANGED
        } catch (e: CancellationException) {
            // Don't swallow cancellation — let structured concurrency unwind.
            throw e
        } catch (e: Exception) {
            // Non-fatal — log and continue
        } finally {
            _isSyncing.value = false
            connectivityMonitor.setSyncing(false)
            syncMutex.unlock()
        }
        return SyncAttempt.RAN
    }

    /**
     * Sync libraries and their audiobooks from the server.
     * Returns whether the resulting record was persisted, which it is not
     * when the configured server changed while the sync was in flight.
     */
    private suspend fun syncLibraries(serverUrlAtStart: String): Boolean {
        val report = fetchLibrarySyncReport(
            fetchLibraries = libraryRepository::syncFromServer,
            // syncLibraryItems already preserves local download state.
            fetchItems = { library -> audioBookRepository.syncLibraryItems(library.id) },
        )
        val completedAtMs = System.currentTimeMillis()
        var recorded = false
        settingsManager.updateSettings {
            val updated = it.withLastSyncIfServerUnchanged(report, completedAtMs, serverUrlAtStart)
            recorded = updated !== it
            updated
        }
        return recorded
    }

    /**
     * Sync progress from the server.
     * Server is source of truth (except for actively playing items).
     */
    private suspend fun syncProgress() {
        try {
            val importToken = progressRepository.progressImportToken()
            val serverProgressList = progressRepository.fetchAllProgressFromServer()
            if (serverProgressList.isEmpty()) return

            for (progress in serverProgressList) {
                // Active playback and queued local writes own progress. In
                // particular, never compare server and phone wall clocks to
                // decide whether an unsent offline write survives.
                try {
                    var book = audioBookDao.getById(progress.libraryItemId)
                    if (book == null) {
                        val remoteBook = audioBookRepository.fetchFromServer(progress.libraryItemId)
                        if (remoteBook != null) {
                            audioBookDao.upsert(remoteBook.toEntity())
                            book = audioBookDao.getById(progress.libraryItemId)
                        }
                    }

                    val currentTimeSecs = progress.currentTime.toDouble(kotlin.time.DurationUnit.SECONDS)
                    val positionSeconds = if (currentTimeSecs > 0) {
                        currentTimeSecs
                    } else {
                        // Estimate from progress fraction × duration
                        if (book != null && book.durationSeconds > 0) {
                            progress.progress * book.durationSeconds
                        } else 0.0
                    }

                    val updatedAtStr = progress.lastUpdate?.toIso8601()

                    // The repository checks the queue while holding the same
                    // per-book ownership used by enqueue and flush. A pending
                    // local write wins regardless of server clock skew.
                    val imported = progressRepository.importServerProgressIfNoPending(
                        PlaybackProgressEntity(
                            audioBookId = progress.libraryItemId,
                            positionSeconds = positionSeconds,
                            isFinished = if (progress.isFinished) 1 else 0,
                            updatedAt = updatedAtStr,
                        ),
                        importToken = importToken,
                        onImported = {
                            if (book != null) {
                                audioBookDao.upsert(
                                    book.copy(
                                        currentTimeSeconds = positionSeconds,
                                        progress = progress.progress,
                                        isFinished = if (progress.isFinished) 1 else 0,
                                    )
                                )
                            }
                        },
                    )
                    if (!imported) continue
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
    suspend fun setActivePlaybackItem(
        itemId: String?,
        isCurrent: () -> Boolean = { true },
    ): Boolean = activeItemMutationMutex.withLock {
        val claimed = progressRepository.setActiveProgressItem(itemId, isCurrent)
        if (claimed) activeItemId = itemId
        claimed
    }

    private suspend fun clearActivePlaybackItemIf(itemId: String): Boolean =
        activeItemMutationMutex.withLock {
            val cleared = progressRepository.clearActiveProgressItemIf(itemId)
            if (cleared) activeItemId = null
            cleared
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
        val safeCurrentTime = currentTime.coerceAtLeast(0.0)
        val safeDuration = duration.coerceAtLeast(0.0)
        // Only auto-mark as finished if position is within 1 second of the end.
        // Exact >= comparison can fire prematurely during seeks near the end.
        val computedFinished = isFinished || (safeDuration > 0.0 && safeDuration - safeCurrentTime < 1.0)
        val isLocalMode = settingsManager.currentSettings.appMode == AppMode.LOCAL
        val now = System.currentTimeMillis()
        val shouldSync = !isLocalMode && shouldPushPlaybackPosition(
            throttle = playbackThrottleOwner.snapshot(itemId),
            currentTime = safeCurrentTime,
            duration = safeDuration,
            isFinished = computedFinished,
            now = now,
        )

        val updateShelf: suspend () -> Unit = {
            readAndWriteShelfProgressIfCurrent(
                isCurrent = { true },
                read = { audioBookDao.getById(itemId) },
                write = { book ->
                    if (book != null) {
                        audioBookDao.upsert(
                            book.copy(
                                currentTimeSeconds = safeCurrentTime,
                                progress = shelfProgress(
                                    currentTime = safeCurrentTime,
                                    duration = safeDuration,
                                    isFinished = computedFinished,
                                    existingProgress = book.progress,
                                ),
                                isFinished = if (computedFinished) 1 else 0,
                            ),
                        )
                    }
                },
            )
        }

        val pushed = if (isLocalMode) {
            progressRepository.savePlaybackProgress(
                audioBookId = itemId,
                position = safeCurrentTime.seconds,
                isFinished = computedFinished,
                onPersisted = updateShelf,
            )
            false
        } else {
            progressRepository.savePushOrEnqueueProgress(
                itemId = itemId,
                currentTime = safeCurrentTime,
                isFinished = computedFinished,
                duration = safeDuration,
                pushToServer = shouldSync && connectivityMonitor.isOnline.value,
                onPersisted = updateShelf,
            )
        }

        if (pushed) {
            playbackThrottleOwner.recordSuccess(itemId, safeCurrentTime, now)
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
        duration: Double = 0.0,
        onPersisted: suspend () -> Unit = {},
    ) {
        val safeCurrentTime = currentTime.coerceAtLeast(0.0)
        val safeDuration = duration.coerceAtLeast(0.0)
        val computedFinished = isFinished

        // LOCAL mode: local save above is the source of truth. Skip server push and
        // do NOT enqueue. Local item IDs would 404 against the server and poison the queue.
        if (settingsManager.currentSettings.appMode == AppMode.LOCAL) {
            progressRepository.savePlaybackProgress(
                audioBookId = itemId,
                position = safeCurrentTime.seconds,
                isFinished = computedFinished,
                onPersisted = onPersisted,
            )
            clearActivePlaybackItemIf(itemId)
            return
        }

        progressRepository.savePushOrEnqueueProgress(
            itemId = itemId,
            currentTime = safeCurrentTime,
            isFinished = computedFinished,
            duration = safeDuration,
            pushToServer = connectivityMonitor.isOnline.value,
            onPersisted = onPersisted,
        )

        // Clear active item
        clearActivePlaybackItemIf(itemId)
    }

    // ─── Offline Queue ───────────────────────────────────────────────────────

    /**
     * Flush all pending progress updates to the server.
     * Called on reconnect.
     */
    private suspend fun flushOfflineQueue() {
        if (!hasAuthToken()) return
        // Never push to a server while in LOCAL mode. (The rising-edge caller
        // already gates on this; guard here too since the periodic loop also calls us.)
        if (settingsManager.currentSettings.appMode == AppMode.LOCAL) return
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
        return settingsManager.getAuthToken()?.isNotBlank() == true
    }
}

internal class SyncLifecycleOwner(
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var job: Job? = null

    fun restart(block: suspend CoroutineScope.() -> Unit): Job = synchronized(lock) {
        job?.cancel()
        scope.launch {
            supervisorScope(block)
        }.also { job = it }
    }

    fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
        }
    }
}

/**
 * The mode-switch reconnect has the same blind spot the Home pill had: right
 * after connectivity returns, the cached isOnline flag can still say false
 * because the OS callback is delayed or missed, and both the reachability
 * check and syncNow short-circuit on that cache. Re-read the OS state first,
 * then let syncNow own the single reachability probe.
 */
internal suspend fun performModeSwitchReconnect(
    refreshIsOnline: () -> Unit,
    syncNow: suspend () -> Unit,
) {
    refreshIsOnline()
    syncNow()
}

internal fun shouldReconnectForModeTransition(
    previousMode: AppMode,
    newMode: AppMode,
): Boolean = previousMode != AppMode.AUDIOBOOKSHELF && newMode == AppMode.AUDIOBOOKSHELF

internal suspend fun isSyncEligibleAfterReachability(
    checkServerReachable: suspend () -> Boolean,
    isStillEligible: suspend () -> Boolean,
): Boolean = checkServerReachable() && isStillEligible()

/**
 * Gate for a server sync. Requires an authenticated, non-LOCAL session AND an
 * active network. The online check is the "internet connection check" that
 * keeps airplane mode from triggering doomed sync attempts.
 */
internal fun shouldRunSync(
    isOnline: Boolean,
    isLocalMode: Boolean,
    hasAuth: Boolean,
): Boolean = isOnline && !isLocalMode && hasAuth

/**
 * What syncNow() actually did. [SKIPPED_NOT_READY] and [SKIPPED_BUSY] both
 * mean no sync ran this call: nothing was fetched and no [LastSyncRecord] was
 * written on this attempt's behalf. A caller that reports the outcome to the
 * user must treat either skip as its own outcome, not read whatever record
 * happens to already be there and call that a result.
 */
internal enum class SyncAttempt {
    /** The gate failed: offline, LOCAL mode, or no auth token. */
    SKIPPED_NOT_READY,

    /** Another sync already held the mutex. */
    SKIPPED_BUSY,

    /** A sync actually ran (including the reachability-probe-failure path, which records FAILED). */
    RAN,

    /**
     * A sync ran but the configured server changed before it finished, so
     * its record was refused. The record now stored, if any, belongs to the
     * new server and is not this attempt's result.
     */
    DISCARDED_SERVER_CHANGED,
}

internal data class PlaybackThrottleSnapshot(
    val lastSyncedTime: Double = 0.0,
    val lastSyncTimestamp: Long = 0L,
)

internal class PlaybackThrottleOwner {
    private val lock = Any()
    private val states = mutableMapOf<String, PlaybackThrottleSnapshot>()

    fun snapshot(itemId: String): PlaybackThrottleSnapshot = synchronized(lock) {
        states[itemId] ?: PlaybackThrottleSnapshot()
    }

    fun recordSuccess(itemId: String, currentTime: Double, timestamp: Long) {
        synchronized(lock) {
            states[itemId] = PlaybackThrottleSnapshot(currentTime, timestamp)
        }
    }
}


internal fun shouldPushPlaybackPosition(
    throttle: PlaybackThrottleSnapshot,
    currentTime: Double,
    duration: Double,
    isFinished: Boolean,
    now: Long,
): Boolean {
    if (duration <= 0.0 && !isFinished) return false
    val progress = if (duration > 0.0) (currentTime / duration).coerceIn(0.0, 1.0) else 0.0
    val timeSinceLastSync = now - throttle.lastSyncTimestamp
    val positionDelta = kotlin.math.abs(currentTime - throttle.lastSyncedTime)
    val lastSyncedProgress = throttle.lastSyncedTime / duration.coerceAtLeast(1.0)
    return isFinished ||
        (timeSinceLastSync >= MIN_POSITION_SYNC_INTERVAL_MS &&
            (positionDelta >= MIN_POSITION_DELTA || progress - lastSyncedProgress >= MIN_PROGRESS_DELTA))
}

internal fun shelfProgress(
    currentTime: Double,
    duration: Double,
    isFinished: Boolean,
    existingProgress: Double,
): Double = when {
    isFinished -> 1.0
    duration > 0.0 -> (currentTime / duration).coerceIn(0.0, 1.0)
    else -> existingProgress
}

internal suspend fun <T> readAndWriteShelfProgressIfCurrent(
    isCurrent: () -> Boolean,
    read: suspend () -> T,
    write: suspend (T) -> Unit,
): Boolean {
    if (!isCurrent()) return false
    val current = read()
    if (!isCurrent()) return false
    write(current)
    return true
}
