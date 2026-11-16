package com.ninelivesaudio.app.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.ninelivesaudio.app.data.local.AppDatabase
import com.ninelivesaudio.app.data.local.dao.OfflineListeningSessionDao
import com.ninelivesaudio.app.data.local.dao.PendingProgressDao
import com.ninelivesaudio.app.data.local.dao.PlaybackProgressDao
import com.ninelivesaudio.app.data.local.entity.OfflineListeningSessionEntity
import com.ninelivesaudio.app.data.local.entity.PendingProgressEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.remote.LocalSessionSync
import com.ninelivesaudio.app.data.remote.ServerProgressRead
import com.ninelivesaudio.app.data.remote.dto.ApiLocalSession
import com.ninelivesaudio.app.domain.model.UserProgress
import com.ninelivesaudio.app.domain.util.toEpochMillis
import com.ninelivesaudio.app.domain.util.toIso8601
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class PendingProgressQueueOwner {
    private val mutex = Mutex()
    private val ownerLock = Any()
    private val itemMutexes = mutableMapOf<String, Mutex>()
    private val itemGenerations = mutableMapOf<String, Long>()
    private val rowTokens = mutableMapOf<Long, Token>()
    private val activeTransitionMutex = Mutex()
    private var activeItemId: String? = null
    private val terminalImportLeases = mutableMapOf<String, Int>()
    private val importGenerations = mutableMapOf<String, Long>()

    data class Token(val itemId: String, val generation: Long)
    data class ImportToken(val generations: Map<String, Long>)

    private fun itemMutex(itemId: String): Mutex = synchronized(ownerLock) {
        itemMutexes.getOrPut(itemId, ::Mutex)
    }

    suspend fun <T> withLock(block: suspend () -> T): T =
        mutex.withLock { block() }

    suspend fun <T> withItemLock(itemId: String, block: suspend () -> T): T =
        itemMutex(itemId).withLock { block() }

    suspend fun <T> withItemLockIfCurrent(
        itemId: String,
        isCurrent: () -> Boolean,
        block: suspend () -> T,
    ): T? = itemMutex(itemId).withLock {
        if (isCurrent()) block() else null
    }

    suspend fun <T> withItemLockIfInactive(itemId: String, block: suspend () -> T): T? =
        itemMutex(itemId).withLock {
            if (itemIsInactiveLocked(itemId)) block() else null
        }

    /**
     * Whether nothing plays or stops [itemId] right now, so server progress
     * may land on it. Call it while holding the book's item lock, which a
     * book needs to become active, so the answer cannot flip to active
     * underneath the caller.
     */
    fun itemIsInactiveLocked(itemId: String): Boolean = synchronized(ownerLock) {
        activeItemId != itemId && (terminalImportLeases[itemId] ?: 0) == 0
    }

    suspend fun <T> withTerminalImportLease(itemId: String, block: suspend () -> T): T {
        synchronized(ownerLock) {
            terminalImportLeases[itemId] = (terminalImportLeases[itemId] ?: 0) + 1
        }
        return try {
            block()
        } finally {
            synchronized(ownerLock) {
                val remaining = (terminalImportLeases[itemId] ?: 1) - 1
                if (remaining == 0) terminalImportLeases.remove(itemId)
                else terminalImportLeases[itemId] = remaining
            }
        }
    }

    suspend fun setActiveItem(
        itemId: String?,
        isCurrent: () -> Boolean = { true },
    ): Boolean = activeTransitionMutex.withLock {
        if (!isCurrent()) return@withLock false
        if (itemId == null) {
            publishActiveItem(null)
            true
        } else {
            itemMutex(itemId).withLock {
                if (!isCurrent()) {
                    false
                } else {
                    publishActiveItem(itemId)
                    true
                }
            }
        }
    }

    suspend fun clearActiveItemIf(itemId: String): Boolean = activeTransitionMutex.withLock {
        itemMutex(itemId).withLock {
            if (synchronized(ownerLock) { activeItemId != itemId }) {
                false
            } else {
                publishActiveItem(null)
                true
            }
        }
    }

    private fun publishActiveItem(itemId: String?) = synchronized(ownerLock) {
        val previous = activeItemId
        activeItemId = itemId
        listOfNotNull(previous, itemId).distinct().forEach(::incrementImportGeneration)
    }

    fun importToken(): ImportToken = synchronized(ownerLock) {
        ImportToken(importGenerations.toMap())
    }

    fun importTokenIsCurrent(itemId: String, token: ImportToken): Boolean =
        synchronized(ownerLock) {
            (token.generations[itemId] ?: 0L) == (importGenerations[itemId] ?: 0L)
        }

    fun localWriteOccurred(itemId: String) {
        synchronized(ownerLock) { incrementImportGeneration(itemId) }
    }

    private fun incrementImportGeneration(itemId: String) {
        importGenerations[itemId] = (importGenerations[itemId] ?: 0L) + 1L
    }

    fun token(itemId: String): Token = synchronized(ownerLock) {
        Token(itemId, itemGenerations[itemId] ?: 0L)
    }

    fun invalidate(itemId: String) {
        synchronized(ownerLock) {
            itemGenerations[itemId] = (itemGenerations[itemId] ?: 0L) + 1L
        }
    }

    fun trackRow(rowId: Long, token: Token) {
        synchronized(ownerLock) { rowTokens[rowId] = token }
    }

    fun rowIsCurrent(rowId: Long): Boolean = synchronized(ownerLock) {
        val token = rowTokens[rowId] ?: return@synchronized true
        token.generation == (itemGenerations[token.itemId] ?: 0L)
    }

    fun forgetRows(rowIds: Collection<Long>) {
        synchronized(ownerLock) { rowIds.forEach(rowTokens::remove) }
    }

    fun forgetItemRows(itemId: String) {
        synchronized(ownerLock) {
            rowTokens.entries.removeAll { (_, token) -> token.itemId == itemId }
        }
    }
}

internal suspend fun insertOwnedPendingProgress(
    enqueue: suspend () -> Long,
    isCurrent: () -> Boolean,
    delete: suspend (Long) -> Unit,
    onInserted: (Long) -> Unit = {},
    onDeleted: (Long) -> Unit = {},
) = withContext(NonCancellable) {
    val rowId = enqueue()
    onInserted(rowId)
    if (!isCurrent()) {
        delete(rowId)
        onDeleted(rowId)
    }
}

/**
 * Deliver a session save and acknowledge its queued row only once the server
 * holds everything the row does. The session sync carries the position and
 * listening time but no finished flag, so a finished save also pushes the
 * flag itself, after the sync so the flag is the last word. If either call
 * fails the row stays queued and the reconnect flush delivers it. Returns
 * whether the session sync landed, which is what acknowledges listening time.
 */
internal suspend fun deliverSessionProgress(
    isFinished: Boolean,
    syncSession: suspend () -> Boolean,
    pushFinished: suspend () -> Boolean,
    acknowledge: suspend () -> Unit,
): Boolean {
    val synced = syncSession()
    if (synced && (!isFinished || pushFinished())) acknowledge()
    return synced
}

@Singleton
class ProgressRepository @Inject constructor(
    private val database: AppDatabase,
    private val playbackProgressDao: PlaybackProgressDao,
    private val pendingProgressDao: PendingProgressDao,
    private val offlineSessionDao: OfflineListeningSessionDao,
    private val apiService: ApiService,
) {
    private val pendingProgressQueueOwner = PendingProgressQueueOwner()

    // ─── Local Playback Progress ─────────────────────────────────────────

    suspend fun savePlaybackProgress(
        audioBookId: String,
        position: Duration,
        isFinished: Boolean,
        onPersisted: suspend () -> Unit = {},
    ) = pendingProgressQueueOwner.withItemLock(audioBookId) {
        withContext(NonCancellable) {
            database.withTransaction {
                savePlaybackProgressLocked(audioBookId, position, isFinished)
                onPersisted()
            }
        }
    }

    private suspend fun savePlaybackProgressLocked(
        audioBookId: String,
        position: Duration,
        isFinished: Boolean,
    ) {
        try {
            playbackProgressDao.upsert(
                PlaybackProgressEntity(
                    audioBookId = audioBookId,
                    positionSeconds = position.toDouble(kotlin.time.DurationUnit.SECONDS),
                    isFinished = if (isFinished) 1 else 0,
                    updatedAt = System.currentTimeMillis().toIso8601(),
                )
            )
        } finally {
            pendingProgressQueueOwner.localWriteOccurred(audioBookId)
        }
    }

    suspend fun getPlaybackProgress(audioBookId: String): Pair<Duration, Boolean>? {
        val result = playbackProgressDao.getPositionAndFinished(audioBookId) ?: return null
        return result.PositionSeconds.seconds to (result.IsFinished == 1)
    }

    suspend fun getPlaybackProgressWithTimestamp(audioBookId: String): Triple<Duration, Boolean, Long>? {
        val entity = playbackProgressDao.getByAudioBookId(audioBookId) ?: return null
        val updatedAt = entity.updatedAt?.toEpochMillis() ?: 0L
        return Triple(
            entity.positionSeconds.seconds,
            entity.isFinished == 1,
            updatedAt
        )
    }

    // ─── Offline Queue ───────────────────────────────────────────────────

    internal fun pendingProgressToken(itemId: String): PendingProgressQueueOwner.Token =
        pendingProgressQueueOwner.token(itemId)

    internal fun invalidatePendingProgressLifetime(itemId: String) {
        pendingProgressQueueOwner.invalidate(itemId)
    }

    suspend fun getPendingProgressEntries(): List<PendingProgressEntry> =
        pendingProgressQueueOwner.withLock {
            pendingProgressDao.getAll().map { entity ->
                PendingProgressEntry(
                    itemId = entity.itemId,
                    currentTime = entity.currentTime,
                    isFinished = entity.isFinished == 1,
                    duration = entity.duration,
                    timestamp = entity.timestamp.toEpochMillis() ?: 0L
                )
            }
        }

    /** Queued progress rows plus sessions whose listening the server lacks. */
    suspend fun getPendingProgressCount(): Int =
        pendingProgressQueueOwner.withLock {
            pendingProgressDao.getCount() + offlineSessionDao.getUnsyncedCount()
        }

    suspend fun clearPendingProgress() {
        pendingProgressQueueOwner.withLock {
            val rowIds = pendingProgressDao.getAll().map { it.id }
            pendingProgressDao.deleteAll()
            offlineSessionDao.deleteAll()
            pendingProgressQueueOwner.forgetRows(rowIds)
        }
    }

    /** Whether the book has local listening the server has not received. */
    private suspend fun hasUnsyncedListeningLocked(itemId: String): Boolean =
        pendingProgressDao.getCountForItem(itemId) > 0 ||
            offlineSessionDao.getUnsyncedCountForItem(itemId) > 0

    /**
     * Add [listenedSeconds] of listening, made with no server session open, to
     * the book's offline session, ending at [currentTime] now. Listening after
     * a long gap starts a new session (see [offlineSessionAfterListening]).
     */
    suspend fun recordOfflineListening(
        itemId: String,
        displayTitle: String?,
        listenedSeconds: Double,
        currentTime: Double,
        duration: Double,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (listenedSeconds <= 0.0) return
        pendingProgressQueueOwner.withItemLock(itemId) {
            withContext(NonCancellable) {
                val latest = offlineSessionDao.getForItem(itemId).maxByOrNull { it.updatedAt }
                offlineSessionDao.upsert(
                    offlineSessionAfterListening(
                        latest = latest,
                        itemId = itemId,
                        displayTitle = displayTitle,
                        listenedSeconds = listenedSeconds,
                        currentTime = currentTime,
                        duration = duration,
                        nowMs = nowMs,
                        newId = { UUID.randomUUID().toString() },
                    )
                )
            }
        }
    }

    /**
     * Land one server progress record on a book the phone has no unsynced
     * listening for. A book loaded in the player is not written here: the
     * player owns its spot, so [onLoadedBook] hears about it instead, with
     * the spot the phone had saved, and takes it only while paused.
     */
    internal suspend fun importServerProgressIfNoPending(
        progress: PlaybackProgressEntity,
        importToken: PendingProgressQueueOwner.ImportToken,
        onImported: suspend () -> Unit,
        onLoadedBook: suspend (savedPositionSeconds: Double?) -> Unit = {},
    ): Boolean =
        pendingProgressQueueOwner.withItemLock(progress.audioBookId) {
            pendingProgressQueueOwner.withLock {
                if (!pendingProgressQueueOwner.importTokenIsCurrent(progress.audioBookId, importToken) ||
                    !serverProgressMayReplaceLocal(
                        hasPendingProgress = hasUnsyncedListeningLocked(progress.audioBookId),
                    )
                ) {
                    false
                } else if (!pendingProgressQueueOwner.itemIsInactiveLocked(progress.audioBookId)) {
                    onLoadedBook(playbackProgressDao.getByAudioBookId(progress.audioBookId)?.positionSeconds)
                    false
                } else {
                    database.withTransaction {
                        playbackProgressDao.upsert(progress)
                        onImported()
                    }
                    true
                }
            }
        }

    internal fun progressImportToken(): PendingProgressQueueOwner.ImportToken =
        pendingProgressQueueOwner.importToken()

    internal suspend fun <T> withTerminalProgressOwnership(
        itemId: String,
        block: suspend () -> T,
    ): T = pendingProgressQueueOwner.withTerminalImportLease(itemId, block)

    suspend fun setActiveProgressItem(
        itemId: String?,
        isCurrent: () -> Boolean = { true },
    ): Boolean = pendingProgressQueueOwner.setActiveItem(itemId, isCurrent)

    suspend fun clearActiveProgressItemIf(itemId: String): Boolean =
        pendingProgressQueueOwner.clearActiveItemIf(itemId)

    suspend fun savePushOrEnqueueProgress(
        itemId: String,
        currentTime: Double,
        isFinished: Boolean,
        duration: Double,
        pushToServer: Boolean,
        pushTimeout: Duration = Duration.INFINITE,
        onPersisted: suspend () -> Unit = {},
    ): Boolean = pendingProgressQueueOwner.withItemLock(itemId) {
        persistProgressAndEnqueueLocked(
            itemId = itemId,
            currentTime = currentTime,
            isFinished = isFinished,
            duration = duration,
            onPersisted = onPersisted,
        )
        val pushed = if (pushToServer && progressCanBeDelivered(isFinished, duration)) {
            // The row queued above delivers it later if this gives up.
            pushWithin(pushTimeout) {
                try {
                    apiService.updateProgress(itemId, currentTime, isFinished, duration)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    false
                }
            }
        } else {
            false
        }
        if (pushed) acknowledgePendingProgressLocked(itemId)
        pushed
    }

    suspend fun saveSessionProgressOrEnqueue(
        itemId: String,
        sessionId: String,
        currentTime: Double,
        isFinished: Boolean,
        duration: Double,
        timeListened: Double,
        onPersisted: suspend () -> Unit = {},
    ): Boolean = pendingProgressQueueOwner.withItemLock(itemId) {
        persistProgressAndEnqueueLocked(
            itemId = itemId,
            currentTime = currentTime,
            isFinished = isFinished,
            duration = duration,
            onPersisted = onPersisted,
        )
        deliverSessionProgress(
            isFinished = isFinished,
            syncSession = {
                try {
                    apiService.syncSessionProgress(
                        sessionId = sessionId,
                        currentTime = currentTime,
                        duration = duration,
                        timeListened = timeListened,
                    )
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    false
                }
            },
            pushFinished = {
                try {
                    apiService.updateProgress(itemId, currentTime, true, duration)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    false
                }
            },
            acknowledge = { acknowledgePendingProgressLocked(itemId) },
        )
    }

    private suspend fun persistProgressAndEnqueueLocked(
        itemId: String,
        currentTime: Double,
        isFinished: Boolean,
        duration: Double,
        onPersisted: suspend () -> Unit,
    ) {
        val timestamp = System.currentTimeMillis().toIso8601()
        withContext(NonCancellable) {
            pendingProgressQueueOwner.withLock {
                database.withTransaction {
                    pendingProgressDao.saveProgressAndEnqueue(
                        progress = PlaybackProgressEntity(
                            audioBookId = itemId,
                            positionSeconds = currentTime,
                            isFinished = if (isFinished) 1 else 0,
                            updatedAt = timestamp,
                        ),
                        pending = PendingProgressEntity(
                            itemId = itemId,
                            currentTime = currentTime,
                            isFinished = if (isFinished) 1 else 0,
                            duration = duration,
                            isAtomic = 1,
                            timestamp = timestamp,
                        ),
                    )
                    onPersisted()
                }
            }
            pendingProgressQueueOwner.localWriteOccurred(itemId)
        }
    }

    private suspend fun acknowledgePendingProgressLocked(itemId: String) {
        withContext(NonCancellable) {
            pendingProgressQueueOwner.withLock {
                pendingProgressDao.deleteByItemId(itemId)
                pendingProgressQueueOwner.forgetItemRows(itemId)
            }
            pendingProgressQueueOwner.localWriteOccurred(itemId)
        }
    }

    // ─── Remote Progress ─────────────────────────────────────────────────

    /** Every progress record of the account, or null when the server did not answer. */
    suspend fun fetchAllProgressFromServer(): List<UserProgress>? =
        apiService.fetchAllUserProgressOrNull()

    suspend fun fetchProgressFromServer(itemId: String): UserProgress? =
        apiService.getUserProgress(itemId)

    suspend fun syncSessionProgress(
        itemId: String,
        sessionId: String,
        currentTime: Double,
        duration: Double,
        timeListened: Double = 0.0,
    ) = pendingProgressQueueOwner.withItemLock(itemId) {
        apiService.syncSessionProgress(sessionId, currentTime, duration, timeListened)
    }

    internal suspend fun syncSessionProgressIfCurrent(
        itemId: String,
        sessionId: String,
        currentTime: Double,
        duration: Double,
        timeListened: Double = 0.0,
        isCurrent: () -> Boolean,
    ): Boolean? = pendingProgressQueueOwner.withItemLockIfCurrent(itemId, isCurrent) {
        apiService.syncSessionProgress(sessionId, currentTime, duration, timeListened)
    }

    /**
     * The server's spot for a book, after the phone's unsynced listening for
     * it was delivered. A book nothing plays or stops takes it at once, inside
     * the same transaction as [onServerSpot]. A book loaded in the player is
     * left to the player, which takes it only while paused. The caller holds
     * the book's item lock.
     */
    private suspend fun adoptServerProgressLocked(
        itemId: String,
        progress: UserProgress,
        durationSeconds: Double?,
        savedPositionSeconds: Double?,
        onServerSpot: suspend (ServerSpot) -> Unit,
    ) {
        val positionSeconds = serverProgressPositionSeconds(progress, durationSeconds)
        withContext(NonCancellable) {
            pendingProgressQueueOwner.withLock {
                val inactive = pendingProgressQueueOwner.itemIsInactiveLocked(itemId)
                val spot = ServerSpot(
                    itemId = itemId,
                    positionSeconds = positionSeconds,
                    progress = progress,
                    bookIsLoaded = !inactive,
                    savedPositionSeconds = savedPositionSeconds,
                )
                if (inactive) {
                    database.withTransaction {
                        playbackProgressDao.upsert(
                            PlaybackProgressEntity(
                                audioBookId = itemId,
                                positionSeconds = positionSeconds,
                                isFinished = if (progress.isFinished) 1 else 0,
                                updatedAt = progress.lastUpdate?.toIso8601(),
                            )
                        )
                        onServerSpot(spot)
                    }
                } else {
                    onServerSpot(spot)
                }
            }
            pendingProgressQueueOwner.localWriteOccurred(itemId)
        }
    }

    /**
     * Send one book's unsynced listening (queued progress rows and offline
     * sessions) through the offline session sync, where the server keeps any
     * progress it updated more recently. The caller holds the book's item
     * lock. Nothing is cleared unless the server answered.
     */
    private suspend fun deliverItemLocked(itemId: String): ItemDelivery {
        var rows = pendingProgressQueueOwner.withLock {
            pendingProgressDao.getForItem(itemId)
        }

        var durableProgress = playbackProgressDao.getByAudioBookId(itemId)
        val legacySnapshot = legacyProgressSnapshot(rows, durableProgress)
        if (legacySnapshot != null) {
            // Keep the time the listening was saved. It is the time the
            // server orders this spot by against other devices.
            val timestamp = legacyPromotionTimestampMs(
                rows = rows,
                durableProgress = durableProgress,
                nowMs = System.currentTimeMillis(),
            ).toIso8601()
            withContext(NonCancellable) {
                pendingProgressQueueOwner.withLock {
                    pendingProgressDao.saveProgressAndEnqueue(
                        progress = PlaybackProgressEntity(
                            audioBookId = itemId,
                            positionSeconds = legacySnapshot.currentTime,
                            isFinished = if (legacySnapshot.isFinished) 1 else 0,
                            updatedAt = timestamp,
                        ),
                        pending = PendingProgressEntity(
                            itemId = itemId,
                            currentTime = legacySnapshot.currentTime,
                            isFinished = if (legacySnapshot.isFinished) 1 else 0,
                            duration = legacySnapshot.duration,
                            isAtomic = 1,
                            timestamp = timestamp,
                        ),
                    )
                    pendingProgressQueueOwner.forgetItemRows(itemId)
                }
                pendingProgressQueueOwner.localWriteOccurred(itemId)
            }
            rows = pendingProgressQueueOwner.withLock {
                pendingProgressDao.getForItem(itemId)
            }
            durableProgress = playbackProgressDao.getByAudioBookId(itemId)
        }

        val acknowledgeRows: suspend () -> Unit = {
            withContext(NonCancellable) {
                pendingProgressQueueOwner.withLock {
                    pendingProgressDao.deleteByItemId(itemId)
                    pendingProgressQueueOwner.forgetItemRows(itemId)
                }
                pendingProgressQueueOwner.localWriteOccurred(itemId)
            }
        }

        if (queuedRowsAreSuperseded(rows, durableProgress)) {
            acknowledgeRows()
            rows = emptyList()
        }

        val staleRowIds = rows.filterNot { row ->
            pendingProgressQueueOwner.rowIsCurrent(row.id)
        }.map { it.id }
        if (staleRowIds.isNotEmpty()) {
            pendingProgressQueueOwner.withLock {
                pendingProgressDao.deleteByIds(staleRowIds)
                pendingProgressQueueOwner.forgetRows(staleRowIds)
            }
        }
        val currentRows = rows.filterNot { it.id in staleRowIds }
        val latest = currentRows.maxByOrNull { it.id }?.takeIf { it.isAtomic == 1 }

        val stored = offlineSessionDao.getForItem(itemId)
        val plan = offlineDelivery(
            itemId = itemId,
            queued = latest?.let {
                QueuedSpot(
                    rowId = it.id,
                    rowTimestamp = it.timestamp,
                    positionSeconds = it.currentTime,
                    isFinished = it.isFinished == 1,
                    durationSeconds = it.duration,
                    // An unreadable time claims nothing newer than any spot
                    // the server holds.
                    timestampMs = it.timestamp.toEpochMillis() ?: 0L,
                )
            },
            stored = stored,
        )
        if (plan.sessions.isEmpty()) {
            // A delivered session already holds a later spot than the queue.
            if (currentRows.isEmpty()) return ItemDelivery.NOTHING
            acknowledgeRows()
            return ItemDelivery.DELIVERED
        }

        val outcome = withTimeoutOrNull(PROGRESS_SERVER_CALL_TIMEOUT) {
            apiService.syncLocalSessions(plan.sessions.map { it.toApiLocalSession() })
        } ?: LocalSessionSync.Unavailable

        return when (outcome) {
            LocalSessionSync.Unavailable -> ItemDelivery.UNAVAILABLE
            LocalSessionSync.ItemGone -> {
                Log.w(TAG, "Dropping unsynced progress for $itemId: the server no longer has this book")
                withContext(NonCancellable) {
                    pendingProgressQueueOwner.withLock {
                        pendingProgressDao.deleteByItemId(itemId)
                        offlineSessionDao.deleteForItem(itemId)
                        pendingProgressQueueOwner.forgetItemRows(itemId)
                    }
                    pendingProgressQueueOwner.localWriteOccurred(itemId)
                }
                ItemDelivery.ITEM_GONE
            }
            is LocalSessionSync.Delivered -> {
                val storedIds = stored.map { it.id }.toSet()
                withContext(NonCancellable) {
                    plan.sessions.filter { it.id in storedIds }.forEach { sent ->
                        offlineSessionDao.upsert(sent.copy(syncedTimeListening = sent.timeListening))
                    }
                }
                // The session sync carries no finished flag. A finished spot
                // the server took is marked finished outright, as live
                // playback does.
                val carrierTook = plan.carrierIndex?.let { outcome.progressSynced.getOrNull(it) } == true
                if (latest != null && latest.isFinished == 1 && carrierTook) {
                    val marked = withTimeoutOrNull(PROGRESS_SERVER_CALL_TIMEOUT) {
                        apiService.updateProgress(itemId, latest.currentTime, true, latest.duration)
                    } ?: false
                    if (!marked) return ItemDelivery.UNAVAILABLE
                }
                if (currentRows.isNotEmpty()) acknowledgeRows()
                withContext(NonCancellable) {
                    offlineSessionDao.deleteSyncedBefore(itemId, System.currentTimeMillis() - OFFLINE_SESSION_GAP_MS)
                }
                ItemDelivery.DELIVERED
            }
        }
    }

    private suspend fun readServerProgressWithin(itemId: String): ServerProgressRead =
        withTimeoutOrNull(PROGRESS_SERVER_CALL_TIMEOUT) { apiService.readUserProgress(itemId) }
            ?: ServerProgressRead.Unavailable

    /**
     * Deliver one book's unsynced listening, then read the book's progress
     * from the server, which is then the truth for the book. Anything left
     * undelivered makes the answer [ServerProgressRead.Unavailable], since
     * the server has not judged the phone's listening yet.
     */
    suspend fun deliverAndReadServerProgress(itemId: String): ServerProgressRead =
        pendingProgressQueueOwner.withItemLock(itemId) {
            when (deliverItemLocked(itemId)) {
                ItemDelivery.UNAVAILABLE, ItemDelivery.ITEM_GONE -> ServerProgressRead.Unavailable
                ItemDelivery.NOTHING, ItemDelivery.DELIVERED -> readServerProgressWithin(itemId)
            }
        }

    /**
     * Deliver all unsynced listening, book by book, through the offline
     * session sync. Once a book's listening is delivered the server's spot
     * is the truth, so it is read back and adopted ([adoptServerProgressLocked]).
     * Returns whether everything was delivered.
     */
    suspend fun flushPendingProgress(
        onServerSpot: suspend (ServerSpot) -> Unit,
    ): Boolean {
        val itemIds = pendingProgressQueueOwner.withLock {
            (pendingProgressDao.getAll().map { it.itemId } + offlineSessionDao.getItemIdsWithUnsynced()).distinct()
        }
        if (itemIds.isEmpty()) return true

        var allSuccess = true
        for (itemId in itemIds) {
            pendingProgressQueueOwner.withItemLock(itemId) {
                val saved = playbackProgressDao.getByAudioBookId(itemId)
                val durationHint = pendingProgressDao.getForItem(itemId).maxByOrNull { it.id }?.duration
                    ?: offlineSessionDao.getForItem(itemId).lastOrNull()?.duration
                when (deliverItemLocked(itemId)) {
                    ItemDelivery.UNAVAILABLE -> allSuccess = false
                    ItemDelivery.NOTHING, ItemDelivery.ITEM_GONE -> Unit
                    ItemDelivery.DELIVERED -> {
                        val read = readServerProgressWithin(itemId)
                        if (read is ServerProgressRead.Found) {
                            adoptServerProgressLocked(
                                itemId = itemId,
                                progress = read.progress,
                                durationSeconds = durationHint,
                                savedPositionSeconds = saved?.positionSeconds,
                                onServerSpot = onServerSpot,
                            )
                        }
                    }
                }
            }
        }

        return allSuccess
    }

    // ─── Clear ───────────────────────────────────────────────────────────

    suspend fun deleteAll() {
        playbackProgressDao.deleteAll()
        clearPendingProgress()
    }

    private companion object {
        const val TAG = "ProgressRepository"
    }
}

/** A server spot for one book, read after the phone's listening was delivered. */
data class ServerSpot(
    val itemId: String,
    val positionSeconds: Double,
    val progress: UserProgress,
    /** The book is loaded in the player, so only the player may move it. */
    val bookIsLoaded: Boolean,
    /** Where the phone had the book saved when the server was asked. */
    val savedPositionSeconds: Double?,
)

internal enum class ItemDelivery {
    /** The book had nothing to send. */
    NOTHING,

    /** The server has all of it (whether it took the spot or kept a newer one). */
    DELIVERED,

    /** No answer. Everything stays for the next try. */
    UNAVAILABLE,

    /** The server no longer has the book. Its unsynced listening was dropped. */
    ITEM_GONE,
}

data class PendingProgressEntry(
    val itemId: String,
    val currentTime: Double,
    val isFinished: Boolean,
    val duration: Double,
    val timestamp: Long,
)

internal data class LegacyProgressSnapshot(
    val currentTime: Double,
    val isFinished: Boolean,
    val duration: Double,
)

/**
 * Version 7 queue rows predate the atomic durable-plus-queue transaction. Their
 * durable row is nevertheless the latest local source of truth because pending
 * rows block server imports. Carry forward only duration metadata from the
 * newest queued row, then persist this snapshot atomically before delivery.
 */
internal fun legacyProgressSnapshot(
    rows: List<PendingProgressEntity>,
    durableProgress: PlaybackProgressEntity?,
): LegacyProgressSnapshot? {
    val latest = rows.maxByOrNull { it.id } ?: return null
    if (latest.isAtomic == 1) return null
    return LegacyProgressSnapshot(
        currentTime = durableProgress?.positionSeconds ?: latest.currentTime,
        isFinished = (durableProgress?.isFinished ?: latest.isFinished) == 1,
        duration = latest.duration,
    )
}

/**
 * Whether a live push may send this spot with PATCH, which needs the length
 * to compute the fraction. Queued spots go through the offline session sync
 * instead, where the server supplies the length, so any of them can go.
 */
internal fun progressCanBeDelivered(isFinished: Boolean, duration: Double): Boolean =
    isFinished || duration > 0.0

/**
 * A server import never replaces a book with unsynced local listening,
 * whatever the clocks say. That listening goes to the server first, which
 * keeps its own progress when it is newer (see [offlineDelivery]).
 */
internal fun serverProgressMayReplaceLocal(hasPendingProgress: Boolean): Boolean =
    !hasPendingProgress

/**
 * Atomic delivery writes the durable and queued state from the same values.
 * For rows marked as atomic, a content mismatch therefore proves a later
 * plain durable writer superseded the queue row. Legacy rows are first promoted
 * from their durable source of truth by [legacyProgressSnapshot], so this check
 * only decides the fate of rows with proven atomic provenance.
 */
/** A push that has not answered within [timeout] counts as not delivered. */
internal suspend fun pushWithin(timeout: Duration, push: suspend () -> Boolean): Boolean =
    withTimeoutOrNull(timeout) { push() } ?: false

internal fun queuedRowsAreSuperseded(
    rows: List<PendingProgressEntity>,
    durableProgress: PlaybackProgressEntity?,
): Boolean {
    val durable = durableProgress ?: return false
    val latest = rows.maxByOrNull { it.id } ?: return false
    return latest.isAtomic == 1 &&
        (durable.positionSeconds != latest.currentTime ||
            durable.isFinished != latest.isFinished)
}

/**
 * Server progress as a position in seconds. A server that reports 0 for
 * currentTime may still carry a progress fraction, so the position falls back
 * to that fraction of the book's duration when the duration is known.
 */
internal fun serverProgressPositionSeconds(progress: UserProgress, durationSeconds: Double?): Double {
    val currentTimeSeconds = progress.currentTime.toDouble(kotlin.time.DurationUnit.SECONDS)
    return when {
        currentTimeSeconds > 0 -> currentTimeSeconds
        durationSeconds != null && durationSeconds > 0 -> progress.progress * durationSeconds
        else -> 0.0
    }
}

/**
 * The time a promoted legacy queue was saved: the newest legacy row's own
 * timestamp, or the durable row's when that is later. Stamping it with now
 * would make an old offline row look brand new and win over another device's
 * later spot. With nothing parseable it falls back to [nowMs], which delivers
 * the row as before.
 */
internal fun legacyPromotionTimestampMs(
    rows: List<PendingProgressEntity>,
    durableProgress: PlaybackProgressEntity?,
    nowMs: Long,
): Long {
    val rowMs = rows.maxByOrNull { it.id }?.timestamp?.toEpochMillis()
    val durableMs = durableProgress?.updatedAt?.toEpochMillis()
    return listOfNotNull(rowMs, durableMs).maxOrNull() ?: nowMs
}

/** A server call made while a book's item lock is held waits no longer than this. */
internal val PROGRESS_SERVER_CALL_TIMEOUT: Duration = 10.seconds

/**
 * Listening on the same book within this long of a session's last update
 * joins that session. Later listening starts a new one.
 */
internal const val OFFLINE_SESSION_GAP_MS = 10 * 60_000L

/**
 * The book's offline session once [listenedSeconds] more listening ended at
 * [currentTime], [nowMs]. Listening soon after the latest session continues
 * it, with its whole time, so a second delivery of it replaces the first on
 * the server. A gap, or a clock that moved back, starts a new one.
 */
internal fun offlineSessionAfterListening(
    latest: OfflineListeningSessionEntity?,
    itemId: String,
    displayTitle: String?,
    listenedSeconds: Double,
    currentTime: Double,
    duration: Double,
    nowMs: Long,
    newId: () -> String,
): OfflineListeningSessionEntity =
    if (latest != null && nowMs >= latest.updatedAt && nowMs - latest.updatedAt <= OFFLINE_SESSION_GAP_MS) {
        latest.copy(
            displayTitle = displayTitle ?: latest.displayTitle,
            updatedAt = nowMs,
            currentTime = currentTime,
            duration = if (duration > 0.0) duration else latest.duration,
            timeListening = latest.timeListening + listenedSeconds,
        )
    } else {
        OfflineListeningSessionEntity(
            id = newId(),
            itemId = itemId,
            displayTitle = displayTitle,
            startedAt = nowMs - (listenedSeconds * 1000).toLong(),
            updatedAt = nowMs,
            startTime = (currentTime - listenedSeconds).coerceAtLeast(0.0),
            currentTime = currentTime,
            duration = duration,
            timeListening = listenedSeconds,
        )
    }

/** The newest queued progress row of one book. */
internal data class QueuedSpot(
    val rowId: Long,
    val rowTimestamp: String,
    val positionSeconds: Double,
    val isFinished: Boolean,
    val durationSeconds: Double,
    /** When the phone saved the spot, which is when the listening happened. */
    val timestampMs: Long,
)

/**
 * What one delivery sends: [sessions] in the order the server applies them,
 * oldest first, and which of them carries the queued spot, if any.
 */
internal data class OfflineDeliveryPlan(
    val sessions: List<OfflineListeningSessionEntity>,
    val carrierIndex: Int?,
)

/**
 * Plan one book's delivery. Every session with listening the server lacks
 * goes. The queued spot rides on the latest session when it came after it
 * (and soon enough to belong to it), or on a session of its own with no
 * listening time when there is none. A queued spot older than a session's
 * spot claims nothing, because the session's spot is the phone's later one.
 * A finished spot is sent at the end of the book, where the server marks a
 * book finished. A spot-only session's id comes from the row, so sending the
 * same row twice is one session on the server.
 */
internal fun offlineDelivery(
    itemId: String,
    queued: QueuedSpot?,
    stored: List<OfflineListeningSessionEntity>,
): OfflineDeliveryPlan {
    val unsynced = stored.filter { it.timeListening > it.syncedTimeListening }
    if (queued == null) return OfflineDeliveryPlan(unsynced.sortedBy { it.updatedAt }, carrierIndex = null)

    val position = if (queued.isFinished && queued.durationSeconds > 0.0) {
        maxOf(queued.positionSeconds, queued.durationSeconds)
    } else {
        queued.positionSeconds
    }
    val newest = stored.maxByOrNull { it.updatedAt }
    if (newest != null && newest.updatedAt > queued.timestampMs) {
        return OfflineDeliveryPlan(unsynced.sortedBy { it.updatedAt }, carrierIndex = null)
    }
    val carrier = if (newest != null && queued.timestampMs - newest.updatedAt <= OFFLINE_SESSION_GAP_MS) {
        newest.copy(
            updatedAt = queued.timestampMs,
            currentTime = position,
            duration = if (queued.durationSeconds > 0.0) queued.durationSeconds else newest.duration,
        )
    } else {
        OfflineListeningSessionEntity(
            id = UUID.nameUUIDFromBytes("$itemId:${queued.rowId}:${queued.rowTimestamp}".toByteArray()).toString(),
            itemId = itemId,
            startedAt = queued.timestampMs,
            updatedAt = queued.timestampMs,
            startTime = position,
            currentTime = position,
            duration = queued.durationSeconds,
        )
    }
    val sessions = (unsynced.filterNot { it.id == carrier.id } + carrier).sortedBy { it.updatedAt }
    return OfflineDeliveryPlan(sessions, carrierIndex = sessions.indexOfFirst { it.id == carrier.id })
}

private val SESSION_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
private val SESSION_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.US)

/** The offline session sync's shape of one session, dated on the phone's calendar. */
internal fun OfflineListeningSessionEntity.toApiLocalSession(zone: ZoneId = ZoneId.systemDefault()): ApiLocalSession {
    val day = Instant.ofEpochMilli(updatedAt).atZone(zone)
    return ApiLocalSession(
        id = id,
        libraryItemId = itemId,
        displayTitle = displayTitle,
        duration = duration,
        startTime = startTime,
        currentTime = currentTime,
        timeListening = timeListening,
        startedAt = startedAt,
        updatedAt = updatedAt,
        date = SESSION_DATE_FORMAT.format(day),
        dayOfWeek = SESSION_DAY_FORMAT.format(day),
    )
}
