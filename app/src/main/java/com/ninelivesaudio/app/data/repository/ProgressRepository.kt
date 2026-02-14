package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.local.dao.PendingProgressDao
import com.ninelivesaudio.app.data.local.dao.PlaybackProgressDao
import com.ninelivesaudio.app.data.local.entity.PendingProgressEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.domain.model.UserProgress
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
                updatedAt = Instant.now().atOffset(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            )
        )
    }

    suspend fun getPlaybackProgress(audioBookId: String): Pair<Duration, Boolean>? {
        val result = playbackProgressDao.getPositionAndFinished(audioBookId) ?: return null
        return result.PositionSeconds.seconds to (result.IsFinished == 1)
    }

    suspend fun getPlaybackProgressWithTimestamp(audioBookId: String): Triple<Duration, Boolean, Long>? {
        val entity = playbackProgressDao.getByAudioBookId(audioBookId) ?: return null
        val updatedAt = entity.updatedAt?.let {
            try { Instant.parse(it).toEpochMilli() } catch (_: Exception) { 0L }
        } ?: 0L
        return Triple(
            entity.positionSeconds.seconds,
            entity.isFinished == 1,
            updatedAt
        )
    }

    // ─── Offline Queue ───────────────────────────────────────────────────

    suspend fun enqueuePendingProgress(itemId: String, currentTime: Double, isFinished: Boolean) {
        pendingProgressDao.insert(
            PendingProgressEntity(
                itemId = itemId,
                currentTime = currentTime,
                isFinished = if (isFinished) 1 else 0,
                timestamp = Instant.now().atOffset(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            )
        )
    }

    suspend fun getPendingProgressEntries(): List<PendingProgressEntry> =
        pendingProgressDao.getAll().map { entity ->
            PendingProgressEntry(
                itemId = entity.itemId,
                currentTime = entity.currentTime,
                isFinished = entity.isFinished == 1,
                timestamp = try {
                    Instant.parse(entity.timestamp).toEpochMilli()
                } catch (_: Exception) { 0L }
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

        // Capture the IDs we fetched — only these will be deleted after sync.
        // Any entries inserted by the playback service during the flush window
        // will have newer IDs and won't be touched.
        val fetchedIds = entries.map { it.id }

        // Group by item and take the latest entry for each
        val latest = entries.groupBy { it.itemId }
            .mapValues { (_, entries) -> entries.maxByOrNull { it.timestamp } }

        var allSuccess = true
        for ((itemId, entry) in latest) {
            if (entry == null) continue
            val success = apiService.updateProgress(
                itemId,
                entry.currentTime,
                entry.isFinished == 1,
            )
            if (!success) allSuccess = false
        }

        if (allSuccess) {
            // Delete only the entries we fetched and synced — not newer ones
            // that may have been inserted during the network round-trips.
            pendingProgressDao.deleteByIds(fetchedIds)
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
    val timestamp: Long,
)
