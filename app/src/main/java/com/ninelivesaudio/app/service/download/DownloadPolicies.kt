package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.domain.model.AudioFile
import com.ninelivesaudio.app.domain.model.DownloadStatus
import kotlin.math.pow

// ─── Download decision logic (pure, unit-testable) ────────────────────────
//
// These functions hold the error-prone decisions lifted out of the download
// streaming loop. They take plain values so they can be tested without
// WorkManager, Room, Retrofit, or the filesystem. DownloadEngine wires them to
// the real I/O.

/** Throttle thresholds for persisting download progress to Room + the UI. */
internal const val MIN_PROGRESS_UPDATE_INTERVAL_MS = 750L
internal const val MIN_PROGRESS_DELTA_BYTES = 512 * 1024L // 512 KB

/** Bitrate estimate (~128 kbps) used when the server reports no file size. */
private const val ESTIMATED_BYTES_PER_SECOND = 16_000L

/** Max filename length kept well under typical filesystem limits. */
private const val MAX_FILE_NAME_LENGTH = 200

/** Replace characters illegal in a filename, collapse whitespace, and cap length. */
internal fun sanitizeDownloadFileName(name: String): String =
    name
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_FILE_NAME_LENGTH)

/**
 * The folder name downloads used before #49: "Author - Title" when a real
 * author is known, otherwise just the title, falling back to the book id when
 * both are blank. Sanitized for the filesystem. Two editions with the same
 * author and title got the same folder, so only a download that started under
 * this name still uses it (see [bookDownloadFolder]).
 */
internal fun downloadFolderName(author: String, title: String, fallbackId: String): String =
    sanitizeDownloadFileName(authorTitle(author, title)).ifBlank { fallbackId }

private fun authorTitle(author: String, title: String): String {
    val realAuthor = author.takeIf { it.isNotBlank() && it != "Unknown Author" }
    return if (realAuthor != null) "$realAuthor - $title" else title
}

/** Characters of the item id a download folder's name ends with. */
private const val SHORT_ITEM_ID_LENGTH = 8

/** UTF-8 bytes a download folder's name may use. Android filesystems cap a name at 255. */
internal const val MAX_FOLDER_NAME_BYTES = 200

/**
 * The tail of an ABS item id, letters and digits only. ABS ids end in random
 * characters (a UUID, or `li_` plus random letters), so eight of them tell two
 * editions of one title apart. An id with no letters or digits uses its hash.
 */
internal fun shortItemId(itemId: String): String =
    itemId.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        .takeLast(SHORT_ITEM_ID_LENGTH)
        .ifEmpty { Integer.toHexString(itemId.hashCode()) }

/**
 * Folder name for a new download: "Author - Title [id]". The title comes
 * first so other apps show it readably, and the item id keeps two editions out
 * of each other's folder (#49). The title is cut so the whole name fits
 * [MAX_FOLDER_NAME_BYTES], and the id always survives.
 */
internal fun itemDownloadFolderName(author: String, title: String, itemId: String): String {
    val suffix = "[${shortItemId(itemId)}]"
    val room = MAX_FOLDER_NAME_BYTES - suffix.length - 1
    val base = takeUtf8Bytes(sanitizeDownloadFileName(authorTitle(author, title)), room).trimEnd()
    return if (base.isEmpty()) suffix else "$base $suffix"
}

/** The longest start of [text] that fits [maxBytes] of UTF-8, never splitting a character. */
private fun takeUtf8Bytes(text: String, maxBytes: Int): String {
    var bytes = 0
    var end = 0
    while (end < text.length) {
        val codePoint = text.codePointAt(end)
        val size = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
        if (bytes + size > maxBytes) break
        bytes += size
        end += Character.charCount(codePoint)
    }
    return text.substring(0, end)
}

/**
 * The leaf names a book's tracks get on disk, in the engine's order. Delete
 * uses the same list to tell this book's files from another edition's.
 */
internal fun downloadedFileNames(files: List<AudioFile>): List<String> =
    resolveDownloadFileNames(files.sortedBy { it.index })

/** Stable on-disk leaf names, disambiguating only sanitization collisions. */
internal fun resolveDownloadFileNames(files: List<AudioFile>): List<String> {
    val baseNames = files.mapIndexed { index, file ->
        sanitizeDownloadFileName(file.filename.ifBlank { "track_${index + 1}" })
    }
    val taken = mutableSetOf<String>()
    return baseNames.mapIndexed { index, base ->
        val resolved = if (baseNames.count { it == base } == 1) {
            base
        } else {
            val dot = base.lastIndexOf('.')
            val stem = if (dot <= 0) base else base.substring(0, dot)
            val extension = if (dot <= 0) "" else base.substring(dot)
            var counter = index + 1
            var candidate = "${stem}_$counter$extension"
            // The suffixed name has to be free of every other track's base name
            // and of everything already handed out, or two tracks share a file.
            while (candidate in baseNames || candidate in taken) {
                counter++
                candidate = "${stem}_$counter$extension"
            }
            candidate
        }
        taken += resolved
        resolved
    }
}

/**
 * Total bytes for a book. Uses reported file sizes when present, otherwise
 * estimates from total duration at a ~128 kbps bitrate so progress bars have a
 * denominator. Negative durations are floored at zero.
 */
internal fun estimateTotalBytes(files: List<AudioFile>): Long {
    val reported = files.sumOf { it.size }
    if (reported > 0) return reported
    val totalSeconds = files.sumOf { it.duration.inWholeSeconds.coerceAtLeast(0) }
    return totalSeconds * ESTIMATED_BYTES_PER_SECOND
}

/** Persist progress when enough bytes have arrived or enough time has passed. */
internal fun shouldPersistProgress(bytesDelta: Long, timeDeltaMs: Long): Boolean =
    bytesDelta >= MIN_PROGRESS_DELTA_BYTES || timeDeltaMs >= MIN_PROGRESS_UPDATE_INTERVAL_MS

/** Exponential backoff before the nth retry: 10s, 20s, 40s, ... */
internal fun retryBackoffMs(retryCount: Int): Long =
    (2.0.pow(retryCount) * 5_000).toLong()

/** A file is already downloaded if it exists on disk with non-zero length. */
internal fun shouldSkipDownloadedFile(exists: Boolean, length: Long): Boolean =
    exists && length > 0

/** What a Resume or Retry tap on one download row should do. */
internal enum class ResumeDecision { REQUEUE, BLOCKED_BY_FREE_SLOT, IGNORE }

/**
 * Only a Paused or Failed row can be resumed, and only when its book may hold
 * the free slot ([canClaim] is true whenever the slot does not apply). A
 * Failed row gives the slot up, so another book can download meanwhile.
 * Requeueing it anyway left it Queued forever: the drain only runs the slot
 * winner, which was the other book.
 */
internal suspend fun decideResume(
    status: DownloadStatus,
    canClaim: suspend () -> Boolean,
): ResumeDecision = when {
    status != DownloadStatus.Paused && status != DownloadStatus.Failed -> ResumeDecision.IGNORE
    !canClaim() -> ResumeDecision.BLOCKED_BY_FREE_SLOT
    else -> ResumeDecision.REQUEUE
}
