package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Run the engine with [lock] held, bracketed by [onStart] and [onStop].
 *
 * WorkManager marks a cancelled drain finished as soon as the cancel is
 * recorded, and the stop wait gives up after ten seconds, but a read stuck on
 * a stalled server only unwinds at the 60 second read timeout. Its cleanup
 * then deletes the book's `.part` file, the one a replacement drain may be
 * writing by then. Holding one process-wide lock across the whole run, cleanup
 * included, means a replacement engine cannot start until the old one has
 * actually exited. [onStart] runs only once the lock is held, so whatever it
 * records names the engine that is really running.
 */
internal suspend fun <T> runEngineExclusively(
    lock: Mutex,
    onStart: () -> Unit,
    onStop: () -> Unit,
    block: suspend () -> T,
): T = lock.withLock {
    onStart()
    try {
        block()
    } finally {
        onStop()
    }
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

/**
 * Whether a pause should leave the row alone because the book really finished.
 *
 * The engine writes Completed before it marks the book downloaded, so a pause
 * that cancels it in between leaves a Completed row with no local copy. Only a
 * book row with [isDownloaded] set and a [localPath] proves the finish. Anything
 * short of that gets Paused, and resume re-runs the engine (it skips files it
 * already has) to finish the job.
 */
internal fun pauseKeepsCompletedRow(rowStatus: Int, isDownloaded: Int?, localPath: String?): Boolean =
    rowStatus == DownloadStatus.Completed.ordinal && isDownloaded == 1 && !localPath.isNullOrEmpty()
