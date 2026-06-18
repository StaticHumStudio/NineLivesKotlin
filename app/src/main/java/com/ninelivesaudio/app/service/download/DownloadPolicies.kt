package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.domain.model.AudioFile
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
 * Folder name for a downloaded book: "Author - Title" when a real author is
 * known, otherwise just the title, falling back to the book id when both are
 * blank. Sanitized for the filesystem.
 */
internal fun downloadFolderName(author: String, title: String, fallbackId: String): String {
    val realAuthor = author.takeIf { it.isNotBlank() && it != "Unknown Author" }
    val raw = if (realAuthor != null) "$realAuthor - $title" else title
    return sanitizeDownloadFileName(raw).ifBlank { fallbackId }
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
