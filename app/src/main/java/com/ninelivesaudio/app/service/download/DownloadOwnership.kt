package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The book the engine is downloading right now, and the folder it writes to. */
internal data class EngineClaim(val audioBookId: String, val downloadId: String, val folder: File)

/** How [DownloadOwnership.actOnceEngineIsOff] dealt with the engine. */
internal data class EngineStop(
    /** The engine was (or may have been) on the book, so it was stopped first. */
    val wasDownloading: Boolean,
    /** The stop was confirmed. False when it timed out or was not needed. */
    val stopConfirmed: Boolean,
)

/**
 * Who owns a download folder, and the one rule that keeps delete and cancel
 * from taking another book's files (#49).
 *
 * The rule: every change to what the engine owns, and every delete or cancel
 * decision together with its file removal, runs under [lock]. The engine's
 * changes are claiming a row (only while it still exists and is still Queued
 * or Downloading) along with the folder it will write, committing completion
 * (row Completed and the book's local copy in one step, only while the row
 * still exists), and letting the folder go. Delete, cancel and pause act
 * through [actOnceEngineIsOff]: with the engine on the book they stop it
 * first, outside the lock, then act under it. The folder the engine is
 * writing is never deleted, whichever book it belongs to. No network I/O
 * ever runs under the lock, and any doubt keeps the files.
 *
 * Lock order: DownloadManager's row lock and the engine lock come first, this
 * one innermost.
 */
@Singleton
class DownloadOwnership @Inject constructor() {
    private val lock = Mutex()

    @Volatile
    private var current: EngineClaim? = null

    /** What the engine holds right now. Only stable under the lock. */
    internal val engineClaim: EngineClaim? get() = current

    /** The download row the engine is on, for the Wi-Fi only rule. A plain read, no lock. */
    val engineDownloadId: String? get() = current?.downloadId

    /**
     * The engine claims [downloadId] and [folder]: under the lock it reads the
     * row fresh and, only while it is still Queued or Downloading, writes it
     * back as Downloading. Returns that row, or null when the row is gone or
     * moved on, in which case nothing is written and nothing is claimed. A
     * cancelled engine never claims.
     */
    internal suspend fun claim(
        downloadId: String,
        audioBookId: String,
        folder: File,
        readRow: suspend () -> DownloadItem?,
        writeRow: suspend (DownloadItem) -> Unit,
    ): DownloadItem? = lock.withLock {
        currentCoroutineContext().ensureActive()
        val fresh = readRow()
            ?.takeIf { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Downloading }
            ?: return@withLock null
        val downloading = fresh.copy(status = DownloadStatus.Downloading)
        writeRow(downloading)
        current = EngineClaim(audioBookId, downloadId, folder)
        downloading
    }

    /**
     * The engine commits a finished download: [commit] (the Completed row and
     * the book's local copy) runs under the lock, and only while [rowExists].
     * False when the download was cancelled or deleted meanwhile.
     */
    internal suspend fun commitCompletion(rowExists: suspend () -> Boolean, commit: suspend () -> Unit): Boolean =
        lock.withLock {
            if (!rowExists()) return@withLock false
            commit()
            true
        }

    /** The engine lets its folder go. Runs even when the engine was cancelled. */
    internal suspend fun release() {
        withContext(NonCancellable) { lock.withLock { current = null } }
    }

    /**
     * Run [act] (a delete, cancel or pause of [audioBookId]) under the lock,
     * once the engine is off the book.
     *
     * When the engine is not on the book and its row is not Downloading, [act]
     * runs in the same lock section as that check, so the engine cannot claim
     * the book halfway through. Otherwise [stopEngine] runs first, outside the
     * lock (the engine needs the lock to let go), and [act] after it. Nothing
     * can claim the book again in between: the stopped drain is cancelled, a
     * cancelled engine never claims, and callers hold the row lock that every
     * drain start needs.
     */
    internal suspend fun <T> actOnceEngineIsOff(
        audioBookId: String,
        rowIsDownloading: suspend () -> Boolean,
        stopEngine: suspend () -> Boolean,
        act: suspend (EngineStop) -> T,
    ): T {
        lock.withLock {
            if (current?.audioBookId != audioBookId && !rowIsDownloading()) {
                return act(EngineStop(wasDownloading = false, stopConfirmed = false))
            }
        }
        val confirmed = stopEngine()
        return lock.withLock { act(EngineStop(wasDownloading = true, stopConfirmed = confirmed)) }
    }
}

/**
 * What delete and cancel read to judge a folder. DownloadManager backs it with
 * Room and the engine's path rules.
 */
internal interface FolderReads {
    suspend fun book(id: String): AudioBook?
    /** Books by id, any order, missing ones left out. */
    suspend fun books(ids: List<String>): List<AudioBook>
    /** Ids of other books stored at a file path. See `AudioBookDao.getFilePathBookIdsExcept`. */
    suspend fun filePathBookIdsExcept(audioBookId: String): List<String>
    /** Every other book's stored localPath. */
    suspend fun localPathsExcept(audioBookId: String): List<String>
    suspend fun downloadRows(): List<DownloadOwnerRow>
    /**
     * Where [book] downloads to under the current settings. [startedWriting]
     * is whether its row has bytes on record, see [bookDownloadFolder].
     */
    fun plannedLocation(book: AudioBook, startedWriting: Boolean): DownloadLocation
    /** Where a stored localPath sits, or null when the engine never wrote it. */
    fun storedLocation(localPath: String): DownloadLocation?
}

/**
 * Delete [book]'s downloaded files from [localPath] and nothing else (#49).
 * Call it inside [DownloadOwnership.actOnceEngineIsOff], so what it reads
 * cannot change before the files go.
 *
 * Two editions with the same author and title shared one folder before each
 * download got its own (and those folders are still around), so the other
 * books stored there come along with their track lists, and only this book's
 * own files go. A folder another download may still be writing keeps
 * everything: any unfinished row's folder (see [unfinishedDownloadOwners]) and
 * the folder the engine is really writing, even when a title change moved
 * where its row says it goes. The rules live in [deleteDownloadFiles].
 */
internal suspend fun removeDownloadedBookFiles(
    reads: FolderReads,
    ownership: DownloadOwnership,
    book: AudioBook,
    localPath: String,
): CancelCleanupDecision? {
    val location = reads.storedLocation(localPath) ?: return null
    val sharers = reads.books(reads.filePathBookIdsExcept(book.id)).map { other ->
        FolderSharer(
            path = other.localPath.orEmpty(),
            fileNames = other.audioFiles.takeIf { it.isNotEmpty() }?.let(::downloadedFileNames),
        )
    }
    val claim = ownership.engineClaim
    val rows = reads.downloadRows()
    val candidates = reads.books(rows.map { it.audioBookId }.filter { it != book.id }.distinct())
        .associateBy { it.id }
    val withLocalCopy = candidates.values
        .filter { it.isDownloaded && !it.localPath.isNullOrEmpty() }
        .mapTo(HashSet()) { it.id }
    val started = rows.filter { it.downloadedBytes > 0 }.mapTo(HashSet()) { it.audioBookId }
    val otherFolders = unfinishedDownloadOwners(rows, withLocalCopy, book.id)
        // No book row means nothing the engine could be writing for it.
        .mapNotNull { candidates[it] }
        .map { reads.plannedLocation(it, it.id in started).folder } +
        listOfNotNull(claim?.folder)
    return deleteDownloadFiles(location, downloadedFileNames(book.audioFiles), sharers, otherFolders)
}

/**
 * Delete the partial folder a cancelled download of [audioBookId] left behind
 * (#51), after its row is gone. [startedWriting] is whether that row had bytes
 * on record, which picks the folder (see [bookDownloadFolder]). Call it inside
 * [DownloadOwnership.actOnceEngineIsOff], so what it reads cannot change before
 * the files go. The rules live in [decideCancelCleanup] and keep the files on
 * any doubt, including when another book or download points at the same
 * folder, or the engine is really writing there (whatever its row now says).
 */
internal suspend fun removeCancelledPartialFiles(
    reads: FolderReads,
    ownership: DownloadOwnership,
    audioBookId: String,
    startedWriting: Boolean,
): CancelCleanupDecision? {
    val claim = ownership.engineClaim
    // The engine still on this book means its stop never confirmed.
    if (claim?.audioBookId == audioBookId) return CancelCleanupDecision.Keep(CancelKeepReason.SHARED)
    val book = reads.book(audioBookId) ?: return null
    val location = reads.plannedLocation(book, startedWriting)
    val otherBookPaths = reads.localPathsExcept(audioBookId)
    // Every download row still around, mapped to the folder it would write to.
    val rows = reads.downloadRows()
    val started = rows.filter { it.downloadedBytes > 0 }.mapTo(HashSet()) { it.audioBookId }
    val otherFolders = reads.books(rows.map { it.audioBookId }.distinct())
        .map { reads.plannedLocation(it, it.id in started).folder } + listOfNotNull(claim?.folder)
    val bookIsDownloaded = book.isDownloaded || !book.localPath.isNullOrEmpty()

    val decision = decideCancelCleanup(location, bookIsDownloaded, otherBookPaths, otherFolders)
    if (decision is CancelCleanupDecision.Delete) applyCancelCleanup(decision)
    return decision
}
