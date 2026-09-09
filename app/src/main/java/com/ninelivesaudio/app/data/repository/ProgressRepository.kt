package com.ninelivesaudio.app.data.repository

import androidx.room.withTransaction
import com.ninelivesaudio.app.data.local.AppDatabase
import com.ninelivesaudio.app.data.local.dao.PendingProgressDao
import com.ninelivesaudio.app.data.local.dao.PlaybackProgressDao
import com.ninelivesaudio.app.data.local.entity.PendingProgressEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.data.remote.ActiveRemoteScope
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

internal const val LOCAL_PROGRESS_OWNER_KEY = "local-progress-v1"

/** The complete ownership key for every mutable pending-progress lifetime. */
internal data class ProgressIdentity(
    val ownerKey: String,
    val itemId: String,
)

/** Only confirmed local work or an exact active remote scope may become executable. */
internal sealed interface ProgressScope {
    data object Local : ProgressScope
    data class Remote(val scope: ActiveRemoteScope) : ProgressScope
}

/**
 * Resolves the only durable identity accepted by C2a. Remote IDs must already
 * be C3 envelopes for the exact captured scope, and a stale scope resolves to
 * nothing. There is deliberately no ownerless remote variant.
 */
internal fun resolveProgressIdentity(
    progressScope: ProgressScope,
    itemId: String,
    localItemIsConfirmed: Boolean = true,
    isRemoteScopeCurrent: (ActiveRemoteScope) -> Boolean,
): ProgressIdentity? = when (progressScope) {
    ProgressScope.Local -> if (localItemIsConfirmed) {
        ProgressIdentity(LOCAL_PROGRESS_OWNER_KEY, itemId)
    } else {
        null
    }
    is ProgressScope.Remote -> progressScope.scope
        .takeIf(isRemoteScopeCurrent)
        ?.decodeForEgress(itemId)
        ?.let { ProgressIdentity(progressScope.scope.ownerKey, itemId) }
}

internal class PendingProgressQueueOwner {
    private val mutex = Mutex()
    private val ownerLock = Any()
    private val itemMutexes = mutableMapOf<ProgressIdentity, Mutex>()
    private val itemGenerations = mutableMapOf<ProgressIdentity, Long>()
    private val rowTokens = mutableMapOf<Long, Token>()
    private val activeTransitionMutex = Mutex()
    private var activeIdentity: ProgressIdentity? = null
    private val terminalImportLeases = mutableMapOf<ProgressIdentity, Int>()
    private val importGenerations = mutableMapOf<ProgressIdentity, Long>()

    data class Token(val identity: ProgressIdentity, val generation: Long)
    data class ImportToken(val generations: Map<ProgressIdentity, Long>)

    private fun itemMutex(identity: ProgressIdentity): Mutex = synchronized(ownerLock) {
        itemMutexes.getOrPut(identity, ::Mutex)
    }

    suspend fun <T> withLock(block: suspend () -> T): T =
        mutex.withLock { block() }

    suspend fun <T> withItemLock(identity: ProgressIdentity, block: suspend () -> T): T =
        itemMutex(identity).withLock { block() }

    suspend fun <T> withItemLockIfCurrent(
        identity: ProgressIdentity,
        isCurrent: () -> Boolean,
        block: suspend () -> T,
    ): T? = itemMutex(identity).withLock {
        if (isCurrent()) block() else null
    }

    suspend fun <T> withItemLockIfInactive(identity: ProgressIdentity, block: suspend () -> T): T? =
        itemMutex(identity).withLock {
            if (
                synchronized(ownerLock) {
                    activeIdentity != identity && (terminalImportLeases[identity] ?: 0) == 0
                }
            ) block() else null
        }

    suspend fun <T> withTerminalImportLease(identity: ProgressIdentity, block: suspend () -> T): T {
        synchronized(ownerLock) {
            terminalImportLeases[identity] = (terminalImportLeases[identity] ?: 0) + 1
        }
        return try {
            block()
        } finally {
            synchronized(ownerLock) {
                val remaining = (terminalImportLeases[identity] ?: 1) - 1
                if (remaining == 0) terminalImportLeases.remove(identity)
                else terminalImportLeases[identity] = remaining
            }
        }
    }

    suspend fun setActiveItem(
        identity: ProgressIdentity?,
        isCurrent: () -> Boolean = { true },
    ): Boolean = activeTransitionMutex.withLock {
        if (!isCurrent()) return@withLock false
        if (identity == null) {
            publishActiveIdentity(null)
            true
        } else {
            itemMutex(identity).withLock {
                if (!isCurrent()) {
                    false
                } else {
                    publishActiveIdentity(identity)
                    true
                }
            }
        }
    }

    suspend fun clearActiveItemIf(identity: ProgressIdentity): Boolean = activeTransitionMutex.withLock {
        itemMutex(identity).withLock {
            if (synchronized(ownerLock) { activeIdentity != identity }) {
                false
            } else {
                publishActiveIdentity(null)
                true
            }
        }
    }

    private fun publishActiveIdentity(identity: ProgressIdentity?) = synchronized(ownerLock) {
        val previous = activeIdentity
        activeIdentity = identity
        listOfNotNull(previous, identity).distinct().forEach(::incrementImportGeneration)
    }

    fun importToken(): ImportToken = synchronized(ownerLock) {
        ImportToken(importGenerations.toMap())
    }

    fun importTokenIsCurrent(identity: ProgressIdentity, token: ImportToken): Boolean =
        synchronized(ownerLock) {
            (token.generations[identity] ?: 0L) == (importGenerations[identity] ?: 0L)
        }

    fun localWriteOccurred(identity: ProgressIdentity) {
        synchronized(ownerLock) { incrementImportGeneration(identity) }
    }

    private fun incrementImportGeneration(identity: ProgressIdentity) {
        importGenerations[identity] = (importGenerations[identity] ?: 0L) + 1L
    }

    fun token(identity: ProgressIdentity): Token = synchronized(ownerLock) {
        Token(identity, itemGenerations[identity] ?: 0L)
    }

    fun invalidate(identity: ProgressIdentity) {
        synchronized(ownerLock) {
            itemGenerations[identity] = (itemGenerations[identity] ?: 0L) + 1L
        }
    }

    fun trackRow(rowId: Long, token: Token) {
        synchronized(ownerLock) { rowTokens[rowId] = token }
    }

    fun rowIsCurrent(rowId: Long): Boolean = synchronized(ownerLock) {
        val token = rowTokens[rowId] ?: return@synchronized true
        token.generation == (itemGenerations[token.identity] ?: 0L)
    }

    fun forgetRows(rowIds: Collection<Long>) {
        synchronized(ownerLock) { rowIds.forEach(rowTokens::remove) }
    }

    fun forgetItemRows(identity: ProgressIdentity) {
        synchronized(ownerLock) {
            rowTokens.entries.removeAll { (_, token) -> token.identity == identity }
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

    // C2b replaces string-only caller bridges with captured remote scopes.
    // C2a's string-only remote entry points below are fail closed.
    private fun localIdentity(itemId: String) = ProgressIdentity(LOCAL_PROGRESS_OWNER_KEY, itemId)

    // ─── Local Playback Progress ─────────────────────────────────────────

    suspend fun savePlaybackProgress(
        audioBookId: String,
        position: Duration,
        isFinished: Boolean,
        onPersisted: suspend () -> Unit = {},
    ) = pendingProgressQueueOwner.withItemLock(localIdentity(audioBookId)) {
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
            pendingProgressQueueOwner.localWriteOccurred(localIdentity(audioBookId))
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
        pendingProgressQueueOwner.token(localIdentity(itemId))

    internal fun invalidatePendingProgressLifetime(itemId: String) {
        pendingProgressQueueOwner.invalidate(localIdentity(itemId))
    }

    suspend fun getPendingProgressEntries(): List<PendingProgressEntry> =
        pendingProgressQueueOwner.withLock {
            pendingProgressDao.getAll().map { entity ->
                PendingProgressEntry(
                    ownerKey = entity.ownerKey,
                    itemId = entity.itemId,
                    currentTime = entity.currentTime,
                    isFinished = entity.isFinished == 1,
                    duration = entity.duration,
                    timestamp = entity.timestamp.toEpochMillis() ?: 0L
                )
            }
        }

    suspend fun getPendingProgressCount(): Int =
        pendingProgressQueueOwner.withLock {
            pendingProgressDao.countDeliverableForOwner(LOCAL_PROGRESS_OWNER_KEY)
        }

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
    ): Boolean = false

    internal fun progressImportToken(): PendingProgressQueueOwner.ImportToken =
        pendingProgressQueueOwner.importToken()

    internal suspend fun <T> withTerminalProgressOwnership(
        itemId: String,
        block: suspend () -> T,
    ): T = pendingProgressQueueOwner.withTerminalImportLease(localIdentity(itemId), block)

    suspend fun setActiveProgressItem(
        itemId: String?,
        isCurrent: () -> Boolean = { true },
    ): Boolean = pendingProgressQueueOwner.setActiveItem(itemId?.let(::localIdentity), isCurrent)

    suspend fun clearActiveProgressItemIf(itemId: String): Boolean =
        pendingProgressQueueOwner.clearActiveItemIf(localIdentity(itemId))

    suspend fun savePushOrEnqueueProgress(
        itemId: String,
        currentTime: Double,
        isFinished: Boolean,
        duration: Double,
        pushToServer: Boolean,
        onPersisted: suspend () -> Unit = {},
    ): Boolean = false

    suspend fun saveSessionProgressOrEnqueue(
        itemId: String,
        sessionId: String,
        currentTime: Double,
        isFinished: Boolean,
        duration: Double,
        timeListened: Double,
        onPersisted: suspend () -> Unit = {},
    ): Boolean = false

    /**
     * C2b's only C2a persistence seam. It validates a captured scope before and
     * after obtaining the pair-keyed lock, then atomically writes the durable
     * progress row and its owner-stamped pending row. C2a never dispatches it.
     */
    internal suspend fun saveScopedProgressAndEnqueue(
        progressScope: ProgressScope,
        itemId: String,
        currentTime: Double,
        isFinished: Boolean,
        duration: Double,
        localItemIsConfirmed: Boolean = true,
        isRemoteScopeCurrent: (ActiveRemoteScope) -> Boolean,
        onPersisted: suspend () -> Unit = {},
    ): Boolean {
        val identity = resolveProgressIdentity(
            progressScope,
            itemId,
            localItemIsConfirmed,
            isRemoteScopeCurrent,
        ) ?: return false
        return pendingProgressQueueOwner.withItemLock(identity) {
            val currentIdentity = resolveProgressIdentity(
                progressScope,
                itemId,
                localItemIsConfirmed,
                isRemoteScopeCurrent,
            ) ?: return@withItemLock false
            persistProgressAndEnqueueLocked(
                identity = currentIdentity,
                currentTime = currentTime,
                isFinished = isFinished,
                duration = duration,
                onPersisted = onPersisted,
            )
            true
        }
    }

    private suspend fun persistProgressAndEnqueueLocked(
        identity: ProgressIdentity,
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
                        ownerKey = identity.ownerKey,
                        progress = PlaybackProgressEntity(
                            audioBookId = identity.itemId,
                            positionSeconds = currentTime,
                            isFinished = if (isFinished) 1 else 0,
                            updatedAt = timestamp,
                        ),
                        pending = PendingProgressEntity(
                            itemId = identity.itemId,
                            ownerKey = identity.ownerKey,
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
            pendingProgressQueueOwner.localWriteOccurred(identity)
        }
    }

    private suspend fun acknowledgePendingProgressLocked(identity: ProgressIdentity) {
        withContext(NonCancellable) {
            pendingProgressQueueOwner.withLock {
                pendingProgressDao.deleteForOwnerAndItem(identity.ownerKey, identity.itemId)
                pendingProgressQueueOwner.forgetItemRows(identity)
            }
            pendingProgressQueueOwner.localWriteOccurred(identity)
        }
    }

    // ─── Remote Progress ─────────────────────────────────────────────────

    suspend fun fetchAllProgressFromServer(): List<UserProgress> = emptyList()

    suspend fun fetchProgressFromServer(itemId: String): UserProgress? = null

    suspend fun syncSessionProgress(
        itemId: String,
        sessionId: String,
        currentTime: Double,
        duration: Double,
        timeListened: Double = 0.0,
    ): Boolean = false

    internal suspend fun syncSessionProgressIfCurrent(
        itemId: String,
        sessionId: String,
        currentTime: Double,
        duration: Double,
        timeListened: Double = 0.0,
        isCurrent: () -> Boolean,
    ): Boolean? = null

    /** Remote delivery remains disabled until C2b carries a captured scope end to end. */
    suspend fun flushPendingProgress(): Boolean = true

    // ─── Clear ───────────────────────────────────────────────────────────

    suspend fun deleteAll() {
        playbackProgressDao.deleteAll()
        clearPendingProgress()
    }
}

data class PendingProgressEntry(
    val ownerKey: String?,
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
