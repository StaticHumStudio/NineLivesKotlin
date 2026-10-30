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

/** What a successful [DownloadOwnership.claim] hands the engine: its row and its folder. */
internal data class Claimed(val row: DownloadItem, val folder: File)

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
 * or Downloading) along with the folder it will write, recording progress or
 * failure on that row (only while it is still the engine's, see
 * [recordProgress] and [recordFailure]), committing completion (row Completed
 * and the book's local copy in one step, only while the row still exists),
 * and letting the folder go. Delete, cancel and pause act
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
     * The engine claims [downloadId] and the folder [pickFolder] chooses: under
     * the lock it reads the row fresh and, only while it is still Queued or
     * Downloading, picks the folder and writes the row back as Downloading.
     * Picking under the lock means no delete, cancel or other claim changes
     * who holds a folder between the choice and the claim (see
     * [pickDownloadFolder]). Returns the row and the folder, or null when the
     * row is gone or moved on, in which case nothing is written and nothing is
     * claimed. A cancelled engine never claims.
     */
    internal suspend fun claim(
        downloadId: String,
        audioBookId: String,
        pickFolder: suspend () -> File,
        readRow: suspend () -> DownloadItem?,
        writeRow: suspend (DownloadItem) -> Unit,
    ): Claimed? = lock.withLock {
        currentCoroutineContext().ensureActive()
        val fresh = readRow()
            ?.takeIf { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Downloading }
            ?: return@withLock null
        val folder = pickFolder()
        val downloading = fresh.copy(status = DownloadStatus.Downloading)
        writeRow(downloading)
        current = EngineClaim(audioBookId, downloadId, folder)
        Claimed(downloading, folder)
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

    /**
     * The engine records [downloadedBytes] of [totalBytes] on its row. Under
     * the lock it reads the row fresh and writes the new counts onto that
     * fresh copy, only while the engine still holds [downloadId] and the row
     * is still Downloading. False means nothing was written: the row was
     * cancelled, paused, deleted or queued again while the engine ran on (a
     * stop that timed out), so the engine has to stop too.
     */
    internal suspend fun recordProgress(
        downloadId: String,
        downloadedBytes: Long,
        totalBytes: Long,
        readRow: suspend () -> DownloadItem?,
        writeRow: suspend (DownloadItem) -> Unit,
    ): Boolean = lock.withLock {
        if (current?.downloadId != downloadId) return@withLock false
        val fresh = readRow()?.takeIf { it.status == DownloadStatus.Downloading } ?: return@withLock false
        writeRow(fresh.copy(downloadedBytes = downloadedBytes, totalBytes = totalBytes))
        true
    }

    /**
     * Mark [downloadId] Failed with [errorMessage]. Under the lock it reads the
     * row fresh and writes Failed onto that fresh copy, only while the row is
     * still Queued or Downloading and no engine holds another download. Used by
     * the engine and by the drain's missing-book check. False means nothing was
     * written, because the row moved on (cancelled, paused, deleted).
     */
    internal suspend fun recordFailure(
        downloadId: String,
        errorMessage: String,
        downloadedBytes: Long,
        readRow: suspend () -> DownloadItem?,
        writeRow: suspend (DownloadItem) -> Unit,
    ): Boolean = lock.withLock {
        val claim = current
        if (claim != null && claim.downloadId != downloadId) return@withLock false
        val fresh = readRow()
            ?.takeIf { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Downloading }
            ?: return@withLock false
        writeRow(fresh.copy(status = DownloadStatus.Failed, errorMessage = errorMessage, downloadedBytes = downloadedBytes))
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

    /**
     * Run [act] under the lock unless the engine holds [audioBookId], and
     * null without running it when it does. For short file work in a
     * finished download's folder (the saved cover refresh, #70) that must
     * never race the engine writing the same folder. File IO only, never
     * network I/O.
     */
    internal suspend fun <T> whenEngineIsNotOn(audioBookId: String, act: suspend () -> T): T? =
        lock.withLock {
            if (current?.audioBookId == audioBookId) null else act()
        }
}

/**
 * Pause one download of [audioBookId], the same way for a tap and for an
 * unlock that lapsed. Runs through [DownloadOwnership.actOnceEngineIsOff], so
 * an engine on the book is stopped first and the row is written under the
 * lock. The row is read again after the stop, since the engine may have moved
 * its bytes on or finished the book meanwhile. A finished row whose book holds
 * its local copy stays Completed (see [pauseKeepsCompletedRow]), anything else
 * becomes Paused. A row [readRow] no longer returns is left alone. Even when
 * the stop timed out, a Paused row is final: the engine's next write is
 * refused (see [DownloadOwnership.recordProgress]) and it stops.
 *
 * Returns whether the engine was (or may have been) on the book, so the
 * caller knows to restart the drain for the rest of the queue.
 */
internal suspend fun pauseOnceEngineIsOff(
    ownership: DownloadOwnership,
    audioBookId: String,
    rowIsDownloading: suspend () -> Boolean,
    stopEngine: suspend () -> Boolean,
    readRow: suspend () -> DownloadItem?,
    readBook: suspend (String) -> AudioBook?,
    writeRow: suspend (DownloadItem) -> Unit,
): Boolean = ownership.actOnceEngineIsOff(audioBookId, rowIsDownloading, stopEngine) { stop ->
    val latest = readRow() ?: return@actOnceEngineIsOff stop.wasDownloading
    val book = readBook(latest.audioBookId)
    val keepsFinish = pauseKeepsCompletedRow(
        rowStatus = latest.status.ordinal,
        isDownloaded = book?.let { if (it.isDownloaded) 1 else 0 },
        localPath = book?.localPath,
    )
    if (!keepsFinish) writeRow(latest.copy(status = DownloadStatus.Paused))
    stop.wasDownloading
}

/**
 * Whether [folder], an existing download folder, holds another book's audio:
 * another book's stored file path is that folder, another download that has
 * started writing writes there (see [FolderReads.plannedLocation]), or a
 * local folder import plays tracks from it (see [localImportSharers]).
 * Compared canonically, and a path that will not resolve counts as held,
 * since any doubt keeps folders apart.
 *
 * A download with no bytes on record has finished no track anywhere (the
 * engine records a track's bytes before the track appears, see
 * [commitTrackFile]), so it holds no folder and picks its own when it
 * claims. Counting it would push a paused download out of the folder its
 * finished tracks sit in.
 */
internal suspend fun folderHeldByAnotherBook(reads: FolderReads, audioBookId: String, folder: File): Boolean {
    val target = canonicalOrNull(folder) ?: return true
    for (raw in reads.localPathsExcept(audioBookId)) {
        // Content URIs (local folder imports) are checked below.
        val other = bookPathAsFile(raw) ?: continue
        if ((canonicalOrNull(other) ?: return true) == target) return true
    }
    val started = reads.downloadRows()
        .filter { it.audioBookId != audioBookId && it.downloadedBytes > 0 }
        .map { it.audioBookId }
        .distinct()
    for (other in reads.books(started)) {
        val planned = reads.plannedLocation(other, startedWriting = true).folder
        if ((canonicalOrNull(planned) ?: return true) == target) return true
    }
    val root = folder.parentFile ?: return true
    val imports = localImportSharers(reads, audioBookId, DownloadLocation(root, folder, rootIsAppOwned = false))
    for (sharer in imports) {
        if ((canonicalOrNull(File(sharer.path)) ?: return true) == target) return true
    }
    return false
}

/**
 * The folder a download of [book] writes to (#88). Normally the one
 * [bookDownloadFolder] names. New folders carry the last eight letters and
 * digits of the item id, so two editions with the same author and title whose
 * ids end alike would share one. When that short-id folder already exists
 * and another book holds it ([folderHeldByAnotherBook]), the download goes to
 * the sibling named with the full id instead, which [bookDownloadFolder]
 * prefers from then on. A short-id folder this book left itself (a paused or
 * cancelled partial) is reused as before.
 *
 * Reads the database and the disk, never the network. Run it as the claim's
 * folder pick, under the ownership lock.
 */
internal suspend fun pickDownloadFolder(reads: FolderReads, book: AudioBook, startedWriting: Boolean): File {
    val planned = reads.plannedLocation(book, startedWriting).folder
    if (planned.name != itemDownloadFolderName(book.author, book.title, book.id)) return planned
    if (!planned.isDirectory) return planned
    if (!folderHeldByAnotherBook(reads, book.id, planned)) return planned
    return File(planned.parentFile, fullItemDownloadFolderName(book.author, book.title, book.id))
}

/**
 * What delete and cancel read to judge a folder. The engine backs it with
 * Room and its path rules.
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
    /**
     * Every other book whose stored localPath is a content URI (the local
     * folder imports), by id. See `AudioBookDao.getContentPathRowsExcept`.
     */
    suspend fun contentPathsExcept(audioBookId: String): Map<String, String>
    /** The file a local import's track URI names, see [safDocumentFile]. */
    fun contentTrackFile(uri: String): File?
}

/**
 * The local folder imports (#88) that play audio from inside the download
 * root of [location], as folder sharers for [deleteDownloadFiles]: one per
 * folder an import's tracks sit in, naming its tracks there.
 *
 * A user can pick a download folder and also import that same folder as a
 * local library. The import's tracks are SAF document URIs, which the
 * sharer check could not read, so deleting the server download took audio
 * the import still plays. Only imports whose stored localPath resolves under
 * the root are loaded. A track URI that names no file is skipped: only the
 * shared storage provider names files, and nothing else reaches a download
 * root.
 */
internal suspend fun localImportSharers(
    reads: FolderReads,
    audioBookId: String,
    location: DownloadLocation,
): List<FolderSharer> {
    val root = runCatching { location.root.canonicalFile }.getOrNull() ?: return emptyList()
    fun underRoot(file: File): Boolean =
        runCatching { file.canonicalFile.toPath().startsWith(root.toPath()) }.getOrDefault(false)
    val importIds = reads.contentPathsExcept(audioBookId)
        .filterValues { path -> reads.contentTrackFile(path)?.let(::underRoot) == true }
        .keys
        .toList()
    if (importIds.isEmpty()) return emptyList()
    return reads.books(importIds).flatMap { import ->
        import.audioFiles
            .mapNotNull { track -> track.localPath?.let(reads::contentTrackFile) }
            .groupBy { it.parentFile }
            .mapNotNull { (parent, tracks) ->
                parent?.let { FolderSharer(path = it.path, fileNames = tracks.map(File::getName)) }
            }
    }
}

/**
 * Delete [book]'s downloaded files from [localPath] and nothing else (#49).
 * Call it inside [DownloadOwnership.actOnceEngineIsOff], so what it reads
 * cannot change before the files go.
 *
 * Two editions with the same author and title shared one folder before each
 * download got its own (and those folders are still around), so the other
 * books stored there come along with their track lists, and only this book's
 * own files go. A local folder import playing tracks from the same folder
 * counts the same way (see [localImportSharers]). A folder another download may still be writing keeps
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
    val filePathSharers = reads.books(reads.filePathBookIdsExcept(book.id)).map { other ->
        FolderSharer(
            path = other.localPath.orEmpty(),
            fileNames = other.audioFiles.takeIf { it.isNotEmpty() }?.let(::downloadedFileNames),
        )
    }
    // A local folder import over the same folder keeps the tracks it plays (#88).
    val sharers = filePathSharers + localImportSharers(reads, book.id, location)
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
