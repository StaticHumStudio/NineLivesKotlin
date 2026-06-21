package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus

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
