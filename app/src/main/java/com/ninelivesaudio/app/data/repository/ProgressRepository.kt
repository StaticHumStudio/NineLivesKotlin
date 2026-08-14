package com.ninelivesaudio.app.data.repository

import androidx.room.withTransaction
import com.ninelivesaudio.app.data.local.AppDatabase
import com.ninelivesaudio.app.data.local.dao.PendingProgressDao
import com.ninelivesaudio.app.data.local.dao.PlaybackProgressDao
import com.ninelivesaudio.app.data.local.entity.PendingProgressEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.domain.model.UserProgress
import com.ninelivesaudio.app.domain.util.toEpochMillis
import com.ninelivesaudio.app.domain.util.toIso8601
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
            if (
                synchronized(ownerLock) {
                    activeItemId != itemId && (terminalImportLeases[itemId] ?: 0) == 0
                }
            ) block() else null
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

internal suspend fun acknowledgePendingFallbackOnSuccess(
    deliver: suspend () -> Boolean,
    acknowledge: suspend () -> Unit,
): Boolean {
    val delivered = deliver()
    if (delivered) acknowledge()
    return delivered
}

@Singleton
class ProgressRepository @Inject constructor(
    private val database: AppDatabase,
    private val playbackProgressDao: PlaybackProgressDao,
    private val pendingProgressDao: PendingProgressDao,
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

    suspend fun getPendingProgressCount(): Int =
        pendingProgressQueueOwner.withLock { pendingProgressDao.getCount() }

    suspend fun clearPendingProgress() {
        pendingProgressQueueOwner.withLock {
            val rowIds = pendingProgressDao.getAll().map { it.id }
            pendingProgressDao.deleteAll()
            pendingProgressQueueOwner.forgetRows(rowIds)
        }
    }

    internal suspend fun importServerProgressIfNoPending(
        progress: PlaybackProgressEntity,
        importToken: PendingProgressQueueOwner.ImportToken,
        onImported: suspend () -> Unit,
    ): Boolean =
        pendingProgressQueueOwner.withItemLockIfInactive(progress.audioBookId) {
            pendingProgressQueueOwner.withLock {
                if (!pendingProgressQueueOwner.importTokenIsCurrent(progress.audioBookId, importToken) ||
                    !serverProgressMayReplaceLocal(
                        hasPendingProgress = pendingProgressDao.getCountForItem(progress.audioBookId) > 0,
                    )
                ) {
                    false
                } else {
                    database.withTransaction {
                        playbackProgressDao.upsert(progress)
                        onImported()
                    }
                    true
                }
            }
        } ?: false

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
            try {
                apiService.updateProgress(itemId, currentTime, isFinished, duration)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
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
        acknowledgePendingFallbackOnSuccess(
            deliver = {
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

    suspend fun fetchAllProgressFromServer(): List<UserProgress> =
        apiService.getAllUserProgress()

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

    /** Flush all pending progress updates to the server. */
    suspend fun flushPendingProgress(): Boolean {
        val itemIds = pendingProgressQueueOwner.withLock {
            pendingProgressDao.getAll().map { it.itemId }.distinct()
        }
        if (itemIds.isEmpty()) return true

        // Group by item, push only the latest entry per item. On success, delete
        // ALL fetched rows for that item so superseded older rows don't linger.
        var allSuccess = true
        for (itemId in itemIds) {
            pendingProgressQueueOwner.withItemLock(itemId) {
                var rows = pendingProgressQueueOwner.withLock {
                    pendingProgressDao.getForItem(itemId)
                }

                var durableProgress = playbackProgressDao.getByAudioBookId(itemId)
                val legacySnapshot = legacyProgressSnapshot(rows, durableProgress)
                if (legacySnapshot != null) {
                    val timestamp = System.currentTimeMillis().toIso8601()
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

                if (queuedRowsAreSuperseded(rows, durableProgress)) {
                    withContext(NonCancellable) {
                        pendingProgressQueueOwner.withLock {
                            pendingProgressDao.deleteByItemId(itemId)
                            pendingProgressQueueOwner.forgetItemRows(itemId)
                        }
                        pendingProgressQueueOwner.localWriteOccurred(itemId)
                    }
                    return@withItemLock
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
                val push = latestPushArgs(currentRows) ?: return@withItemLock
                val success = apiService.updateProgress(
                    itemId,
                    push.currentTime,
                    push.isFinished,
                    push.duration,
                )
                if (success) {
                    withContext(NonCancellable) {
                        pendingProgressQueueOwner.withLock {
                            pendingProgressDao.deleteByItemId(itemId)
                            pendingProgressQueueOwner.forgetItemRows(itemId)
                        }
                        pendingProgressQueueOwner.localWriteOccurred(itemId)
                    }
                } else {
                    allSuccess = false
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
}

data class PendingProgressEntry(
    val itemId: String,
    val currentTime: Double,
    val isFinished: Boolean,
    val duration: Double,
    val timestamp: Long,
)

/** The fields pushed for one item's queued progress: the latest row wins. */
data class PendingPushArgs(
    val currentTime: Double,
    val isFinished: Boolean,
    val duration: Double,
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
 * Pick the latest queued row by its auto-generated Room ID. IDs preserve local
 * insertion order even if the device wall clock moves backward. Pure, so the
 * duration-carrying behavior is unit-testable without the DB.
 */
internal fun latestPushArgs(rows: List<PendingProgressEntity>): PendingPushArgs? {
    val latest = rows.maxByOrNull { it.id } ?: return null
    if (latest.isAtomic != 1) return null
    if (!progressCanBeDelivered(latest.isFinished == 1, latest.duration)) return null
    return PendingPushArgs(latest.currentTime, latest.isFinished == 1, latest.duration)
}

internal fun progressCanBeDelivered(isFinished: Boolean, duration: Double): Boolean =
    isFinished || duration > 0.0

internal fun serverProgressMayReplaceLocal(hasPendingProgress: Boolean): Boolean =
    !hasPendingProgress

/**
 * Atomic delivery writes the durable and queued state from the same values.
 * For rows marked as atomic, a content mismatch therefore proves a later
 * plain durable writer superseded the queue row. Legacy rows are first promoted
 * from their durable source of truth by [legacyProgressSnapshot], so this check
 * only decides the fate of rows with proven atomic provenance.
 */
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
