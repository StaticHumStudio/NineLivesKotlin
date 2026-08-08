package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.dao.DownloadItemDao
import com.ninelivesaudio.app.data.local.dao.PlaybackProgressDao
import com.ninelivesaudio.app.domain.model.DownloadStatus
import com.ninelivesaudio.app.entitlement.DownloadSlotResolver
import com.ninelivesaudio.app.entitlement.EntitlementCachePrefs
import com.ninelivesaudio.app.entitlement.EntitlementRepository
import com.ninelivesaudio.app.entitlement.SlotCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles [SlotCandidate]s from Room and the filesystem, and remembers the
 * winner.
 *
 * Splits cleanly from [DownloadSlotResolver], which holds the rules and is pure.
 * This side is all the I/O the rules deliberately know nothing about: three
 * tables, an ISO 8601 string that has to become millis, and whether the files
 * are actually still there.
 *
 * It does NOT take the slot mutex. Callers do, because a claim has to span more
 * than a read.
 */
@Singleton
class DownloadSlotStore @Inject constructor(
    private val downloadItemDao: DownloadItemDao,
    private val audioBookDao: AudioBookDao,
    private val playbackProgressDao: PlaybackProgressDao,
    private val entitlements: EntitlementRepository,
    private val cache: EntitlementCachePrefs,
) {
    /** Free installs have a slot at all. Unlocked ones do not. */
    val slotApplies: Boolean get() = !entitlements.current.isUnlocked

    /** Persisted queue-level pause. See [EntitlementCachePrefs.downloadsPaused]. */
    var downloadsPaused: Boolean
        get() = cache.downloadsPaused
        set(value) {
            cache.downloadsPaused = value
        }

    var persistedWinner: String?
        get() = cache.slotWinnerAudioBookId
        set(value) {
            cache.slotWinnerAudioBookId = value
        }

    /**
     * Every book that currently occupies, or could occupy, the slot.
     *
     * Built from the union of live download rows and retained offline state,
     * because neither source alone is complete: the engine writes the offline
     * fields only after writing Completed, and `clearCompleted()` deletes rows
     * while leaving files on disk.
     */
    suspend fun buildCandidates(): List<SlotCandidate> = withContext(Dispatchers.IO) {
        val rows = downloadItemDao.getAll().map { it.toDomain() }
        val rowsByBook = rows.groupBy { it.audioBookId }

        val offlineBooks = audioBookDao.getAll()
            .map { it.toDomain() }
            .filter { it.isDownloaded || rowsByBook.containsKey(it.id) }

        // Keyed off AudioBooks rather than download rows, so a claim whose book
        // record has gone is naturally reported as unusable rather than being
        // silently dropped from the candidate set.
        val bookIds = (offlineBooks.map { it.id } + rowsByBook.keys).distinct()

        bookIds.map { bookId ->
            val book = offlineBooks.firstOrNull { it.id == bookId }
            // The newest row wins when duplicates exist. The pre-existing queue
            // race can already have left more than one row per book on upgraded
            // installs, and no dedupe migration runs.
            val row = rowsByBook[bookId]
                ?.maxByOrNull { it.startedAt ?: Long.MIN_VALUE }

            SlotCandidate(
                audioBookId = bookId,
                isLocal = book?.isLocal ?: false,
                hasAudioBookRecord = book != null,
                downloadStatus = row?.status,
                isDownloaded = book?.isDownloaded ?: false,
                hasLocalPath = !book?.localPath.isNullOrBlank(),
                filesExist = filesExist(book?.localPath),
                progressUpdatedAt = progressMillis(bookId),
                completedAt = row?.completedAt,
                startedAt = row?.startedAt,
            )
        }
    }

    /** Resolve, persist and return the winner. */
    suspend fun resolveAndPersistWinner(): String? {
        val winner = DownloadSlotResolver.resolveWinner(buildCandidates())
        persistedWinner = winner
        return winner
    }

    /**
     * The winner, revalidated. Recomputes and re-persists when the stored one no
     * longer holds, e.g. its files were deleted from under it.
     */
    suspend fun currentWinner(): String? {
        val candidates = buildCandidates()
        val stored = persistedWinner
        if (DownloadSlotResolver.isWinnerStillValid(stored, candidates)) return stored

        val recomputed = DownloadSlotResolver.resolveWinner(candidates)
        persistedWinner = recomputed
        return recomputed
    }

    /**
     * Whether [audioBookId] may take the slot right now.
     *
     * True when the slot does not apply, when the book already holds it, or when
     * nothing holds it.
     */
    suspend fun canClaim(audioBookId: String): Boolean {
        if (!slotApplies) return true
        val winner = currentWinner()
        return winner == null || winner == audioBookId
    }

    private fun filesExist(localPath: String?): Boolean {
        if (localPath.isNullOrBlank()) return false
        return runCatching { File(localPath).exists() }.getOrDefault(false)
    }

    /**
     * PlaybackProgress stores an ISO 8601 string. A malformed or missing one
     * reads as null, which the ladder treats as "never played" rather than as
     * epoch zero.
     */
    private suspend fun progressMillis(audioBookId: String): Long? {
        val raw = playbackProgressDao.getByAudioBookId(audioBookId)?.updatedAt ?: return null
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
    }
}

/** Statuses that mean a row is a live or finished occupant of the slot. */
internal val SLOT_OCCUPYING_STATUSES = setOf(
    DownloadStatus.Preparing,
    DownloadStatus.Queued,
    DownloadStatus.Downloading,
    DownloadStatus.Paused,
    DownloadStatus.Completed,
)
