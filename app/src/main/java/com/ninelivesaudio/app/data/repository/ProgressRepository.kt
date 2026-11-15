package com.ninelivesaudio.app.data.repository

import androidx.room.withTransaction
import com.ninelivesaudio.app.data.local.AppDatabase
import com.ninelivesaudio.app.data.local.dao.PendingProgressDao
import com.ninelivesaudio.app.data.local.dao.PlaybackProgressDao
import com.ninelivesaudio.app.data.local.entity.PendingProgressEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.remote.ServerProgressRead
import com.ninelivesaudio.app.domain.model.UserProgress
import com.ninelivesaudio.app.domain.util.toEpochMillis
import com.ninelivesaudio.app.domain.util.toIso8601
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * Drop a book's queue in favor of the server's newer spot, and keep that
     * spot locally unless the book is playing or stopping, whose progress the
     * player owns. The caller holds the book's item lock.
     */
    private suspend fun adoptServerProgressLocked(
        itemId: String,
        progress: UserProgress,
        durationSeconds: Double,
        onAdopted: suspend (itemId: String, positionSeconds: Double, progress: UserProgress) -> Unit,
    ) {
        val positionSeconds = serverProgressPositionSeconds(progress, durationSeconds)
        withContext(NonCancellable) {
            pendingProgressQueueOwner.withLock {
                database.withTransaction {
                    pendingProgressDao.deleteByItemId(itemId)
                    if (pendingProgressQueueOwner.itemIsInactiveLocked(itemId)) {
                        playbackProgressDao.upsert(
                            PlaybackProgressEntity(
                                audioBookId = itemId,
                                positionSeconds = positionSeconds,
                                isFinished = if (progress.isFinished) 1 else 0,
                                updatedAt = progress.lastUpdate?.toIso8601(),
                            )
                        )
                        onAdopted(itemId, positionSeconds, progress)
                    }
                }
                pendingProgressQueueOwner.forgetItemRows(itemId)
            }
            pendingProgressQueueOwner.localWriteOccurred(itemId)
        }
    }

    /**
     * Flush all pending progress updates to the server. Each book's newest
     * row meets the server's progress first (see [decideQueuedProgress]).
     * When the server's spot stands, [onServerProgressAdopted] runs inside
     * the same transaction as the local write, to update the shelf.
     */
    suspend fun flushPendingProgress(
        onServerProgressAdopted: suspend (itemId: String, positionSeconds: Double, progress: UserProgress) -> Unit,
    ): Boolean {
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
                val acknowledge: suspend () -> Unit = {
                    withContext(NonCancellable) {
                        pendingProgressQueueOwner.withLock {
                            pendingProgressDao.deleteByItemId(itemId)
                            pendingProgressQueueOwner.forgetItemRows(itemId)
                        }
                        pendingProgressQueueOwner.localWriteOccurred(itemId)
                    }
                }
                // The row may be hours old. Another device may have moved the
                // book since, so the server is read first (issue #118).
                val settled = settleQueuedProgress(
                    row = QueuedProgressFacts(
                        positionSeconds = push.currentTime,
                        isFinished = push.isFinished,
                        durationSeconds = push.duration,
                        timestampMs = currentRows.maxBy { it.id }.timestamp.toEpochMillis(),
                    ),
                    readServer = { apiService.readUserProgress(itemId) },
                    phoneNowMs = System::currentTimeMillis,
                    push = {
                        apiService.updateProgress(
                            itemId,
                            push.currentTime,
                            push.isFinished,
                            push.duration,
                        )
                    },
                    acknowledge = acknowledge,
                    adoptServerProgress = { progress ->
                        adoptServerProgressLocked(
                            itemId = itemId,
                            progress = progress,
                            durationSeconds = push.duration,
                            onAdopted = onServerProgressAdopted,
                        )
                    },
                )
                if (!settled) allSuccess = false
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

/**
 * A server import never replaces a book with a queued local write, whatever
 * the clocks say. The queued write meets the server's progress in the flush
 * instead, where [decideQueuedProgress] orders the two by age, each measured
 * on its own clock.
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

/** The newest queued row for one book, the one a flush would send. */
internal data class QueuedProgressFacts(
    val positionSeconds: Double,
    val isFinished: Boolean,
    val durationSeconds: Double,
    /** When the phone saved the row, on the phone's clock. */
    val timestampMs: Long?,
)

internal enum class QueuedProgressDecision {
    /** The row is the newer act. Send it. */
    PUSH,

    /** The server already holds the row's spot. Nothing to send. */
    ALREADY_ON_SERVER,

    /** The server's spot is newer, or the order cannot be told. Keep it. */
    SERVER_STANDS,
}

/** A queued row must be this much later than the server's last update to win. */
internal const val QUEUED_PROGRESS_ORDER_MARGIN_MS = 30_000L

/** Two positions this close are the same spot. */
internal const val SAME_SPOT_TOLERANCE_SECONDS = 0.05

/**
 * The Date header counts whole seconds, so a server age a hair below zero is
 * rounding, not a clock that moved. Anything further below zero is.
 */
internal const val CLOCK_ROUNDING_TOLERANCE_MS = 2_000L

/**
 * Decide what a queued row does against what the server holds. Never
 * overwrite newer progress another device made, and never lose progress made
 * here. The two acts are ordered by age, each measured on its own clock (the
 * server's age from the server's Date header, the row's age from the phone's
 * clock), so a wrong wall clock on either side cancels out. Anything too close
 * to call keeps the server's spot.
 */
internal fun decideQueuedProgress(
    row: QueuedProgressFacts,
    server: ServerProgressRead.Answered,
    phoneNowMs: Long,
): QueuedProgressDecision {
    val found = when (server) {
        is ServerProgressRead.Missing -> return QueuedProgressDecision.PUSH
        is ServerProgressRead.Found -> server
    }
    val serverPosition = serverProgressPositionSeconds(found.progress, row.durationSeconds)
    if (found.progress.isFinished == row.isFinished &&
        kotlin.math.abs(serverPosition - row.positionSeconds) <= SAME_SPOT_TOLERANCE_SECONDS
    ) {
        return QueuedProgressDecision.ALREADY_ON_SERVER
    }

    val serverLastUpdate = found.progress.lastUpdate
    val rowTimestamp = row.timestampMs
    if (serverLastUpdate == null || rowTimestamp == null) return QueuedProgressDecision.SERVER_STANDS

    val rowIsLater = when (val serverNow = found.serverNowMs) {
        // No server clock: the row's own time meets the server's lastUpdate.
        null -> rowTimestamp >= serverLastUpdate + QUEUED_PROGRESS_ORDER_MARGIN_MS
        else -> {
            val serverAge = serverNow - serverLastUpdate
            val ourAge = phoneNowMs - rowTimestamp
            if (serverAge < -CLOCK_ROUNDING_TOLERANCE_MS || ourAge < -CLOCK_ROUNDING_TOLERANCE_MS) {
                return QueuedProgressDecision.SERVER_STANDS
            }
            ourAge.coerceAtLeast(0) + QUEUED_PROGRESS_ORDER_MARGIN_MS <= serverAge.coerceAtLeast(0)
        }
    }
    return if (rowIsLater) QueuedProgressDecision.PUSH else QueuedProgressDecision.SERVER_STANDS
}

/**
 * One book's queued progress meets the server before it is sent. Returns
 * whether the book's queue is settled. A server that cannot be asked keeps
 * the queue for the next flush. [acknowledge] clears the queue, and
 * [adoptServerProgress] clears it and keeps the server's spot.
 */
internal suspend fun settleQueuedProgress(
    row: QueuedProgressFacts,
    readServer: suspend () -> ServerProgressRead,
    phoneNowMs: () -> Long,
    push: suspend () -> Boolean,
    acknowledge: suspend () -> Unit,
    adoptServerProgress: suspend (UserProgress) -> Unit,
): Boolean {
    val server = when (val read = readServer()) {
        ServerProgressRead.Unavailable -> return false
        is ServerProgressRead.Answered -> read
    }
    return when (decideQueuedProgress(row, server, phoneNowMs())) {
        QueuedProgressDecision.PUSH -> {
            val pushed = push()
            if (pushed) acknowledge()
            pushed
        }
        QueuedProgressDecision.ALREADY_ON_SERVER -> {
            acknowledge()
            true
        }
        QueuedProgressDecision.SERVER_STANDS -> {
            // Only a Found answer can stand against the row.
            adoptServerProgress((server as ServerProgressRead.Found).progress)
            true
        }
    }
}
