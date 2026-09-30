package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

// ─── WorkManager identifier + queue selection ─────────────────────────────
//
// Downloads run on a single "drain the queue" worker rather than one chained
// worker per book. Sequencing is a plain loop inside the worker (pick the next
// item, download it, repeat), which avoids the WorkManager dependency-chain
// handoff that left the next item stuck after the first finished.

/** Unique-work name for the single download-queue worker. */
const val DOWNLOAD_WORK_NAME = "audiobook_downloads"

/**
 * Pick the next item the drain worker should download from the active set: an
 * interrupted Downloading item first (resume it after process death), otherwise
 * the oldest Queued item. Paused, completed, failed, and cancelled items are
 * never selected. Returns null when there is nothing left to download.
 */
fun selectNextDownload(items: List<DownloadItem>): DownloadItem? =
    items
        .filter { it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued }
        .minWithOrNull(
            compareByDescending<DownloadItem> { it.status == DownloadStatus.Downloading }
                .thenBy { it.startedAt ?: Long.MAX_VALUE }
        )

/**
 * Run a user's pause, cancel or delete write so the engine cannot undo it.
 *
 * DownloadEngine upserts a stale Downloading snapshot on every progress tick
 * and marks the book downloaded when it finishes. A write that lands while it
 * is still streaming this book gets clobbered straight back. So when the
 * engine is on this book, [stopEngine] (cancel plus confirmed termination)
 * runs first, and only then [write].
 */
internal suspend fun <T> writeAfterEngineStops(
    engineActive: Boolean,
    stopEngine: suspend () -> Unit,
    write: suspend () -> T,
): T {
    if (engineActive) stopEngine()
    return write()
}

/**
 * Run a user's pause, cancel or delete in [owner] and await it.
 *
 * Callers are screen coroutines that die when the user navigates away. Once
 * the engine has been stopped, the row write and the queue restart must still
 * happen, or the book sits Downloading with the queue stopped. The caller can
 * be cancelled and stop waiting, the operation itself carries on.
 */
internal suspend fun <T> finishInOwnerScope(
    owner: CoroutineScope,
    operation: suspend () -> T,
): T = owner.async { operation() }.await()
