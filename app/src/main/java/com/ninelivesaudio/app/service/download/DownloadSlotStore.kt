package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.dao.DownloadItemDao
import com.ninelivesaudio.app.data.local.dao.PlaybackProgressDao
import com.ninelivesaudio.app.data.local.entity.SlotBookRow
import com.ninelivesaudio.app.domain.model.DownloadItem
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
     * fields only after writing Completed, and an older Clear All deleted rows
     * while leaving files on disk.
     */
    suspend fun buildCandidates(): List<SlotCandidate> = withContext(Dispatchers.IO) {
        val rows = downloadItemDao.getAll().map { it.toDomain() }
        val rowsByBook = rows.groupBy { it.audioBookId }

        // Only downloaded books and books with a download row can hold the
        // slot, so only those rows are read, and only their slot fields.
        val books = loadSlotBooks(
            rowBookIds = rowsByBook.keys,
            getDownloaded = audioBookDao::getDownloadedSlotRows,
            getByIds = audioBookDao::getSlotRowsByIds,
        )

        assembleSlotCandidates(
            rowsByBook = rowsByBook,
            books = books,
            filesExist = ::filesExist,
            progressMillis = { progressMillis(it) },
        )
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

/** Largest id list bound in one slot lookup, safely under SQLite's 999. */
private const val SLOT_LOOKUP_CHUNK = 500

/**
 * The AudioBooks rows that can matter to the slot: every downloaded book plus
 * any book named by a download row. Keyed by id. Never reads the rest of the
 * library.
 */
internal suspend fun loadSlotBooks(
    rowBookIds: Collection<String>,
    getDownloaded: suspend () -> List<SlotBookRow>,
    getByIds: suspend (List<String>) -> List<SlotBookRow>,
): Map<String, SlotBookRow> {
    val books = LinkedHashMap<String, SlotBookRow>()
    getDownloaded().forEach { books[it.id] = it }
    val missing = rowBookIds.filterNot { it in books }.distinct()
    for (chunk in missing.chunked(SLOT_LOOKUP_CHUNK)) {
        getByIds(chunk).forEach { books[it.id] = it }
    }
    return books
}

/**
 * One candidate per book that is downloaded or has a download row.
 *
 * Keyed off AudioBooks as well as download rows, so a claim whose book record
 * has gone is reported as unusable rather than silently dropped.
 */
internal suspend fun assembleSlotCandidates(
    rowsByBook: Map<String, List<DownloadItem>>,
    books: Map<String, SlotBookRow>,
    filesExist: (String?) -> Boolean,
    progressMillis: suspend (String) -> Long?,
): List<SlotCandidate> {
    val offlineBooks = books.values.filter { it.isDownloaded == 1 || rowsByBook.containsKey(it.id) }
    val offlineById = offlineBooks.associateBy { it.id }
    val bookIds = (offlineBooks.map { it.id } + rowsByBook.keys).distinct()

    return bookIds.map { bookId ->
        val book = offlineById[bookId]
        // The newest row wins when duplicates exist. The pre-existing queue
        // race can already have left more than one row per book on upgraded
        // installs, and no dedupe migration runs.
        val row = rowsByBook[bookId]?.maxByOrNull { it.startedAt ?: Long.MIN_VALUE }

        SlotCandidate(
            audioBookId = bookId,
            isLocal = book?.isLocal == 1,
            hasAudioBookRecord = book != null,
            downloadStatus = row?.status,
            isDownloaded = book?.isDownloaded == 1,
            hasLocalPath = !book?.localPath.isNullOrBlank(),
            filesExist = filesExist(book?.localPath),
            progressUpdatedAt = progressMillis(bookId),
            completedAt = row?.completedAt,
            startedAt = row?.startedAt,
        )
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
