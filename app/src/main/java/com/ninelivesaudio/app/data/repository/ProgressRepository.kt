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

/** Opaque generation claim for one pending-progress lifetime. */
internal data class PendingLifetimeClaim(
    val identity: ProgressIdentity,
    val generation: Long,
)

internal fun scopedProgressIdentity(scope: ActiveRemoteScope, itemId: String): ProgressIdentity =
    ProgressIdentity(scope.ownerKey, itemId)

internal data class ProgressDeliveryOutcome(
    val persisted: Boolean,
    val remoteDelivered: Boolean,
    val sentRowIds: List<Long> = emptyList(),
    val noWork: Boolean = false,
)

internal data class ProgressActiveClaim(
    internal val claim: PendingProgressQueueOwner.ActiveClaim,
)

/** The selected queue snapshot is immutable for one identity-serialized PATCH. */
internal fun capturedPendingRowIds(rows: List<PendingProgressEntity>): List<Long> = rows.map { it.id }

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
    private var activeClaimGeneration: Long = 0L
    private var activeClaim: ActiveClaim? = null
    private var pendingLifetimeGeneration: Long = 0L
    private val pendingLifetimeClaims = mutableMapOf<ProgressIdentity, PendingLifetimeClaim>()
    private val terminalImportLeases = mutableMapOf<ProgressIdentity, Int>()
    private val importGenerations = mutableMapOf<ProgressIdentity, Long>()

    data class Token(val identity: ProgressIdentity, val generation: Long)
    data class ImportToken(val generations: Map<ProgressIdentity, Long>)
    data class ActiveClaim(val identity: ProgressIdentity, val generation: Long)

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
        isCurrent: suspend () -> Boolean = { true },
    ): Boolean {
        if (identity != null) return claimActiveItem(identity, isCurrent) != null
        return activeTransitionMutex.withLock {
            if (!isCurrent()) return@withLock false
            publishActiveIdentity(null)
            true
        }
    }

    suspend fun claimActiveItem(
        identity: ProgressIdentity,
        isCurrent: suspend () -> Boolean = { true },
    ): ActiveClaim? = activeTransitionMutex.withLock {
        if (!isCurrent()) return@withLock null
        itemMutex(identity).withLock {
            if (!isCurrent()) {
                null
            } else {
                val claim = synchronized(ownerLock) {
                    ActiveClaim(identity, ++activeClaimGeneration).also { activeClaim = it }
                }
                publishActiveIdentity(identity)
                claim
            }
        }
    }

    /**
     * This is called while ApiService owns its auth mutex. It is deliberately
     * synchronous and takes only ownerLock, preserving auth -> ownerLock.
     */
    fun claimActiveItemIfCurrent(identity: ProgressIdentity): ActiveClaim? = synchronized(ownerLock) {
        ActiveClaim(identity, ++activeClaimGeneration).also { claim ->
            activeClaim = claim
            publishActiveIdentityLocked(identity)
        }
    }

    suspend fun clearActiveItemIf(identity: ProgressIdentity): Boolean = activeTransitionMutex.withLock {
        itemMutex(identity).withLock {
            if (synchronized(ownerLock) { activeIdentity != identity }) {
                false
            } else {
                synchronized(ownerLock) { activeClaim = null }
                publishActiveIdentity(null)
                true
            }
        }
    }

    suspend fun clearActiveClaim(claim: ActiveClaim): Boolean = activeTransitionMutex.withLock {
        itemMutex(claim.identity).withLock {
            if (synchronized(ownerLock) { activeClaim != claim }) {
                false
            } else {
                synchronized(ownerLock) { activeClaim = null }
                publishActiveIdentity(null)
                true
            }
        }
    }

    private fun publishActiveIdentity(identity: ProgressIdentity?) = synchronized(ownerLock) {
        publishActiveIdentityLocked(identity)
    }

    private fun publishActiveIdentityLocked(identity: ProgressIdentity?) {
        val previous = activeIdentity
        activeIdentity = identity
        if (identity == null) activeClaim = null
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

    fun claimPendingLifetime(identity: ProgressIdentity): PendingLifetimeClaim = synchronized(ownerLock) {
        PendingLifetimeClaim(identity, ++pendingLifetimeGeneration).also { claim ->
            pendingLifetimeClaims[identity] = claim
        }
    }

    fun invalidatePendingLifetime(claim: PendingLifetimeClaim): Boolean = synchronized(ownerLock) {
        if (pendingLifetimeClaims[claim.identity] != claim) return@synchronized false
        pendingLifetimeClaims.remove(claim.identity)
        itemGenerations[claim.identity] = (itemGenerations[claim.identity] ?: 0L) + 1L
        true
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
    private fun isRemoteEnvelope(itemId: String) = itemId.startsWith("nlr1:")

    // ─── Local Playback Progress ─────────────────────────────────────────

    suspend fun savePlaybackProgress(
        audioBookId: String,
        position: Duration,
        isFinished: Boolean,
        onPersisted: suspend () -> Unit = {},
    ) {
        if (isRemoteEnvelope(audioBookId)) return
        pendingProgressQueueOwner.withItemLock(localIdentity(audioBookId)) {
            withContext(NonCancellable) {
                database.withTransaction {
                    savePlaybackProgressLocked(audioBookId, position, isFinished)
                    onPersisted()
                }
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
        if (isRemoteEnvelope(audioBookId)) return null
        val result = playbackProgressDao.getPositionAndFinished(audioBookId) ?: return null
        return result.PositionSeconds.seconds to (result.IsFinished == 1)
    }

    internal suspend fun getPlaybackProgress(scope: ActiveRemoteScope, audioBookId: String): Pair<Duration, Boolean>? {
        if (!apiService.isCurrentActiveRemoteScope(scope) || scope.decodeForEgress(audioBookId) == null) return null
        val result = playbackProgressDao.getPositionAndFinished(audioBookId) ?: return null
        if (!apiService.isCurrentActiveRemoteScope(scope)) return null
        return result.PositionSeconds.seconds to (result.IsFinished == 1)
    }

    suspend fun getPlaybackProgressWithTimestamp(audioBookId: String): Triple<Duration, Boolean, Long>? {
        if (isRemoteEnvelope(audioBookId)) return null
        val entity = playbackProgressDao.getByAudioBookId(audioBookId) ?: return null
        val updatedAt = entity.updatedAt?.toEpochMillis() ?: 0L
        return Triple(
            entity.positionSeconds.seconds,
            entity.isFinished == 1,
            updatedAt
        )
    }

    // ─── Offline Queue ───────────────────────────────────────────────────

    internal fun pendingProgressToken(itemId: String): PendingProgressQueueOwner.Token? =
        if (isRemoteEnvelope(itemId)) null else pendingProgressQueueOwner.token(localIdentity(itemId))

    internal fun pendingProgressToken(scope: ActiveRemoteScope, itemId: String): PendingProgressQueueOwner.Token? =
        scope.decodeForEgress(itemId)?.let { pendingProgressQueueOwner.token(ProgressIdentity(scope.ownerKey, itemId)) }

    internal fun invalidatePendingProgressLifetime(itemId: String) {
        if (isRemoteEnvelope(itemId)) return
        pendingProgressQueueOwner.invalidate(localIdentity(itemId))
    }

    /**
     * Cancellation is local bookkeeping, so it must invalidate the exact
     * captured identity even after authentication has changed. It never sends
     * or persists remote data.
     */
    internal fun invalidatePendingProgressLifetime(scope: ActiveRemoteScope, itemId: String): Boolean {
        if (scope.decodeForEgress(itemId) == null) return false
        pendingProgressQueueOwner.invalidate(ProgressIdentity(scope.ownerKey, itemId))
        return true
    }

    internal suspend fun claimPendingLifetime(
        scope: ActiveRemoteScope,
        itemId: String,
    ): PendingLifetimeClaim? {
        if (scope.decodeForEgress(itemId) == null) return null
        val identity = ProgressIdentity(scope.ownerKey, itemId)
        return apiService.publishIfCurrentActiveRemoteScope(scope) {
            pendingProgressQueueOwner.claimPendingLifetime(identity)
        }
    }

    /** Exact lifetime cleanup remains valid after its original scope expires. */
    internal fun invalidatePendingProgressLifetime(claim: PendingLifetimeClaim): Boolean =
        pendingProgressQueueOwner.invalidatePendingLifetime(claim)

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

    internal suspend fun pendingProgressCount(scope: ActiveRemoteScope): Int =
        if (!apiService.isCurrentActiveRemoteScope(scope)) 0
        else pendingProgressDao.countDeliverableForOwner(scope.ownerKey)

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

    internal suspend fun importServerProgressIfNoPending(
        scope: ActiveRemoteScope,
        progress: PlaybackProgressEntity,
        importToken: PendingProgressQueueOwner.ImportToken,
        onImported: suspend () -> Unit,
    ): Boolean {
        val identity = ProgressIdentity(scope.ownerKey, progress.audioBookId)
        if (scope.decodeForEgress(progress.audioBookId) == null || !apiService.isCurrentActiveRemoteScope(scope)) return false
        return pendingProgressQueueOwner.withItemLockIfInactive(identity) {
            if (!apiService.isCurrentActiveRemoteScope(scope) || !pendingProgressQueueOwner.importTokenIsCurrent(identity, importToken)) return@withItemLockIfInactive false
            try {
                database.withTransaction {
                    if (!apiService.isCurrentActiveRemoteScope(scope)) throw StaleScopedProgress()
                    val pendingRows = pendingProgressDao.getForOwnerAndItem(identity.ownerKey, identity.itemId)
                    if (!apiService.isCurrentActiveRemoteScope(scope)) throw StaleScopedProgress()
                    if (pendingRows.isNotEmpty()) return@withTransaction false
                    playbackProgressDao.upsert(progress)
                    if (!apiService.isCurrentActiveRemoteScope(scope)) throw StaleScopedProgress()
                    onImported()
                    if (!apiService.isCurrentActiveRemoteScope(scope)) throw StaleScopedProgress()
                    true
                }
            } catch (_: StaleScopedProgress) { false }
        } ?: false
    }

    internal fun progressImportToken(): PendingProgressQueueOwner.ImportToken =
        pendingProgressQueueOwner.importToken()

    internal fun progressImportToken(scope: ActiveRemoteScope): PendingProgressQueueOwner.ImportToken =
        pendingProgressQueueOwner.importToken()

    internal suspend fun <T> withTerminalProgressOwnership(
        itemId: String,
        block: suspend () -> T,
    ): T? = if (isRemoteEnvelope(itemId)) null
    else pendingProgressQueueOwner.withTerminalImportLease(localIdentity(itemId), block)

    internal suspend fun <T> withTerminalProgressOwnership(
        scope: ActiveRemoteScope,
        itemId: String,
        block: suspend () -> T,
    ): T? {
        if (!apiService.isCurrentActiveRemoteScope(scope) || scope.decodeForEgress(itemId) == null) return null
        return pendingProgressQueueOwner.withTerminalImportLease(ProgressIdentity(scope.ownerKey, itemId)) {
            if (apiService.isCurrentActiveRemoteScope(scope)) block() else null
        }
    }

    suspend fun setActiveProgressItem(
        itemId: String?,
        isCurrent: suspend () -> Boolean = { true },
    ): Boolean {
        if (itemId?.let(::isRemoteEnvelope) == true) return false
        return pendingProgressQueueOwner.setActiveItem(itemId?.let(::localIdentity), isCurrent)
    }

    internal suspend fun setActiveProgressItem(
        scope: ActiveRemoteScope,
        itemId: String,
        isCurrent: suspend () -> Boolean = { true },
    ): Boolean {
        if (!apiService.isCurrentActiveRemoteScope(scope) || scope.decodeForEgress(itemId) == null) return false
        return pendingProgressQueueOwner.setActiveItem(ProgressIdentity(scope.ownerKey, itemId)) {
            apiService.isCurrentActiveRemoteScope(scope) && isCurrent()
        }
    }

    internal suspend fun claimActiveProgressItem(
        scope: ActiveRemoteScope,
        itemId: String,
        isCurrent: suspend () -> Boolean = { true },
    ): ProgressActiveClaim? {
        if (scope.decodeForEgress(itemId) == null || !isCurrent()) return null
        val identity = ProgressIdentity(scope.ownerKey, itemId)
        return apiService.publishIfCurrentActiveRemoteScope(scope) {
            pendingProgressQueueOwner.claimActiveItemIfCurrent(identity)
                ?.let(::ProgressActiveClaim)
        }
    }

    internal suspend fun claimActiveProgressItem(
        itemId: String,
        isCurrent: suspend () -> Boolean = { true },
    ): ProgressActiveClaim? {
        if (isRemoteEnvelope(itemId)) return null
        return pendingProgressQueueOwner.claimActiveItem(localIdentity(itemId), isCurrent)
            ?.let(::ProgressActiveClaim)
    }

    /** Claim cleanup is bookkeeping, so an expired scope may clear only its exact claim. */
    internal suspend fun clearActiveProgressClaim(claim: ProgressActiveClaim): Boolean =
        pendingProgressQueueOwner.clearActiveClaim(claim.claim)

    suspend fun clearActiveProgressItemIf(itemId: String): Boolean =
        if (isRemoteEnvelope(itemId)) false else pendingProgressQueueOwner.clearActiveItemIf(localIdentity(itemId))

    internal suspend fun clearActiveProgressItemIf(scope: ActiveRemoteScope, itemId: String): Boolean {
        if (!apiService.isCurrentActiveRemoteScope(scope) || scope.decodeForEgress(itemId) == null) return false
        return pendingProgressQueueOwner.clearActiveItemIf(ProgressIdentity(scope.ownerKey, itemId))
    }

    suspend fun savePushOrEnqueueProgress(
        itemId: String,
        currentTime: Double,
        isFinished: Boolean,
        duration: Double,
        pushToServer: Boolean,
        onPersisted: suspend () -> Unit = {},
    ): Boolean = false

    internal suspend fun savePushOrEnqueueProgress(
        scope: ActiveRemoteScope,
        itemId: String,
        currentTime: Double,
        isFinished: Boolean,
        duration: Double,
        pushToServer: Boolean,
        onPersisted: suspend () -> Unit = {},
    ): ProgressDeliveryOutcome {
        if (scope.decodeForEgress(itemId) == null || !apiService.isCurrentActiveRemoteScope(scope)) {
            return ProgressDeliveryOutcome(false, false)
        }
        val identity = scopedProgressIdentity(scope, itemId)
        return pendingProgressQueueOwner.withItemLock(identity) {
            if (!apiService.isCurrentActiveRemoteScope(scope)) return@withItemLock ProgressDeliveryOutcome(false, false)
            try {
                val rowId = persistProgressAndEnqueueLocked(identity, currentTime, isFinished, duration, onPersisted) {
                    apiService.isCurrentActiveRemoteScope(scope)
                }
                if (!pushToServer) return@withItemLock ProgressDeliveryOutcome(true, false, listOf(rowId))
                if (!apiService.isCurrentActiveRemoteScope(scope)) return@withItemLock ProgressDeliveryOutcome(true, false, listOf(rowId))
                val pushed = apiService.updateProgress(scope, itemId, currentTime, isFinished, duration)
                val acknowledged = pushed && acknowledgePendingProgress(scope, itemId, listOf(rowId))
                ProgressDeliveryOutcome(true, acknowledged, listOf(rowId))
            } catch (_: StaleScopedProgress) { ProgressDeliveryOutcome(false, false) }
        }
    }

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
            try {
                persistProgressAndEnqueueLocked(
                    identity = currentIdentity,
                    currentTime = currentTime,
                    isFinished = isFinished,
                    duration = duration,
                    onPersisted = onPersisted,
                    isCurrent = {
                        resolveProgressIdentity(progressScope, itemId, localItemIsConfirmed, isRemoteScopeCurrent) != null
                    },
                )
                true
            } catch (_: StaleScopedProgress) { false }
        }
    }

    private suspend fun persistProgressAndEnqueueLocked(
        identity: ProgressIdentity,
        currentTime: Double,
        isFinished: Boolean,
        duration: Double,
        onPersisted: suspend () -> Unit,
        isCurrent: suspend () -> Boolean = { true },
    ): Long {
        val timestamp = System.currentTimeMillis().toIso8601()
        val rowId = withContext(NonCancellable) {
            pendingProgressQueueOwner.withLock {
                database.withTransaction {
                    if (!isCurrent()) throw StaleScopedProgress()
                    val rowId = pendingProgressDao.saveProgressAndEnqueue(
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
                    if (!isCurrent()) throw StaleScopedProgress()
                    onPersisted()
                    if (!isCurrent()) throw StaleScopedProgress()
                    rowId
                }
            }
        }
        pendingProgressQueueOwner.localWriteOccurred(identity)
        return rowId
    }

    internal suspend fun acknowledgePendingProgress(
        scope: ActiveRemoteScope,
        itemId: String,
        rowIds: List<Long>,
        afterDaoDelete: suspend () -> Unit = {},
    ): Boolean {
        if (scope.decodeForEgress(itemId) == null || !apiService.isCurrentActiveRemoteScope(scope)) return false
        return try {
            pendingProgressQueueOwner.withLock {
                database.withTransaction {
                    if (!apiService.isCurrentActiveRemoteScope(scope)) throw StaleScopedProgress()
                    pendingProgressDao.deleteIdsForOwnerAndItem(scope.ownerKey, itemId, rowIds)
                    afterDaoDelete()
                    if (!apiService.isCurrentActiveRemoteScope(scope)) throw StaleScopedProgress()
                }
                pendingProgressQueueOwner.forgetRows(rowIds)
            }
            true
        } catch (_: StaleScopedProgress) {
            false
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

    internal suspend fun fetchAllProgressFromServer(scope: ActiveRemoteScope): List<UserProgress> =
        apiService.getAllUserProgress(scope)

    suspend fun fetchProgressFromServer(itemId: String): UserProgress? = null

    suspend fun syncSessionProgress(
        itemId: String,
        sessionId: String,
        currentTime: Double,
        duration: Double,
        timeListened: Double = 0.0,
    ): Boolean = false

    internal suspend fun syncSessionProgress(
        scope: ActiveRemoteScope, itemId: String, sessionId: String, currentTime: Double, duration: Double, timeListened: Double = 0.0,
    ): Boolean = scope.decodeForEgress(itemId) != null && apiService.syncSessionProgress(scope, sessionId, currentTime, duration, timeListened)

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

    internal suspend fun flushPendingProgress(
        scope: ActiveRemoteScope,
        beforeItemLock: suspend (String) -> Unit = {},
    ): Boolean {
        if (!apiService.isCurrentActiveRemoteScope(scope)) return false
        val rows = pendingProgressDao.getDeliverableForOwner(scope.ownerKey)
        var allDeliveriesSucceeded = true
        rows.groupBy { it.itemId }.forEach { (itemId, _) ->
            beforeItemLock(itemId)
            if (!apiService.isCurrentActiveRemoteScope(scope)) return false
            val outcome = deliverPendingProgress(scope, itemId)
            if (!outcome.remoteDelivered && !outcome.noWork) {
                allDeliveriesSucceeded = false
            }
        }
        return allDeliveriesSucceeded
    }

    internal suspend fun deliverPendingProgress(scope: ActiveRemoteScope, itemId: String): ProgressDeliveryOutcome {
        if (!apiService.isCurrentActiveRemoteScope(scope)) return ProgressDeliveryOutcome(false, false)
        if (scope.decodeForEgress(itemId) == null) return ProgressDeliveryOutcome(false, false, noWork = true)
        val identity = scopedProgressIdentity(scope, itemId)
        return pendingProgressQueueOwner.withItemLock(identity) {
            if (!apiService.isCurrentActiveRemoteScope(scope)) return@withItemLock ProgressDeliveryOutcome(false, false)
            val rows = pendingProgressDao.getForOwnerAndItem(scope.ownerKey, itemId)
            val latest = latestPushArgs(rows) ?: return@withItemLock ProgressDeliveryOutcome(false, false, noWork = true)
            val sentRows = capturedPendingRowIds(rows)
            if (!apiService.updateProgress(scope, itemId, latest.currentTime, latest.isFinished, latest.duration)) {
                return@withItemLock ProgressDeliveryOutcome(false, false, sentRows)
            }
            val acknowledged = acknowledgePendingProgress(scope, itemId, sentRows)
            ProgressDeliveryOutcome(false, acknowledged, sentRows)
        }
    }

    // ─── Clear ───────────────────────────────────────────────────────────

    suspend fun deleteAll() {
        playbackProgressDao.deleteAll()
        clearPendingProgress()
    }
}

private class StaleScopedProgress : Exception()

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
