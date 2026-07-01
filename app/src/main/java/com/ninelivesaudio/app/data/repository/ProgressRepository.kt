package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.local.dao.PendingProgressDao
import com.ninelivesaudio.app.data.local.dao.PlaybackProgressDao
import com.ninelivesaudio.app.data.local.entity.PendingProgressEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.domain.model.UserProgress
import com.ninelivesaudio.app.domain.util.toEpochMillis
import com.ninelivesaudio.app.domain.util.toIso8601
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Singleton
class ProgressRepository @Inject constructor(
    private val playbackProgressDao: PlaybackProgressDao,
    private val pendingProgressDao: PendingProgressDao,
    private val apiService: ApiService,
) {
    // ─── Local Playback Progress ─────────────────────────────────────────

    suspend fun savePlaybackProgress(
        audioBookId: String,
        position: Duration,
        isFinished: Boolean,
    ) {
        playbackProgressDao.upsert(
            PlaybackProgressEntity(
                audioBookId = audioBookId,
                positionSeconds = position.toDouble(kotlin.time.DurationUnit.SECONDS),
                isFinished = if (isFinished) 1 else 0,
                updatedAt = System.currentTimeMillis().toIso8601(),
            )
        )
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

    suspend fun enqueuePendingProgress(
        itemId: String,
        currentTime: Double,
        isFinished: Boolean,
        duration: Double = 0.0,
    ) {
        pendingProgressDao.insert(
            PendingProgressEntity(
                itemId = itemId,
                currentTime = currentTime,
                isFinished = if (isFinished) 1 else 0,
                duration = duration,
                timestamp = System.currentTimeMillis().toIso8601(),
            )
        )
    }

    suspend fun getPendingProgressEntries(): List<PendingProgressEntry> =
        pendingProgressDao.getAll().map { entity ->
            PendingProgressEntry(
                itemId = entity.itemId,
                currentTime = entity.currentTime,
                isFinished = entity.isFinished == 1,
                duration = entity.duration,
                timestamp = entity.timestamp.toEpochMillis() ?: 0L
            )
        }

    suspend fun getPendingProgressCount(): Int =
        pendingProgressDao.getCount()

    suspend fun clearPendingProgress() {
        pendingProgressDao.deleteAll()
    }

    // ─── Remote Progress ─────────────────────────────────────────────────

    suspend fun fetchAllProgressFromServer(): List<UserProgress> =
        apiService.getAllUserProgress()

    suspend fun fetchProgressFromServer(itemId: String): UserProgress? =
        apiService.getUserProgress(itemId)

    suspend fun pushProgressToServer(
        itemId: String,
        currentTime: Double,
        isFinished: Boolean,
        duration: Double = 0.0,
    ): Boolean = apiService.updateProgress(itemId, currentTime, isFinished, duration)

    /** Flush all pending progress updates to the server. */
    suspend fun flushPendingProgress(): Boolean {
        val entries = pendingProgressDao.getAll()
        if (entries.isEmpty()) return true

        // Group by item, push only the latest entry per item. On success, delete
        // ALL fetched rows for that item so superseded older rows don't linger.
        val entriesByItem = entries.groupBy { it.itemId }

        val deletableRowIds = mutableListOf<Long>()
        var allSuccess = true
        for ((itemId, rows) in entriesByItem) {
            val push = latestPushArgs(rows) ?: continue
            val success = apiService.updateProgress(
                itemId,
                push.currentTime,
                push.isFinished,
                push.duration,
            )
            if (success) {
                rows.forEach { row -> deletableRowIds.add(row.id) }
            } else {
                allSuccess = false
            }
        }

        if (deletableRowIds.isNotEmpty()) {
            // Delete only successful items. Failed items stay queued for retry,
            // and rows inserted during the network round-trips are untouched.
            pendingProgressDao.deleteByIds(deletableRowIds)
        }

        return allSuccess
    }

    // ─── Clear ───────────────────────────────────────────────────────────

    suspend fun deleteAll() {
        playbackProgressDao.deleteAll()
        pendingProgressDao.deleteAll()
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

/**
 * Pick the latest queued row (by parsed instant, not lexicographic string order —
 * a varying timestamp format would otherwise pick the wrong "latest" and push
 * stale progress) and map it to the fields sent to the server. Pure, so the
 * duration-carrying behavior is unit-testable without the DB.
 */
internal fun latestPushArgs(rows: List<PendingProgressEntity>): PendingPushArgs? {
    val latest = rows.maxByOrNull { it.timestamp.toEpochMillis() ?: 0L } ?: return null
    return PendingPushArgs(latest.currentTime, latest.isFinished == 1, latest.duration)
}
