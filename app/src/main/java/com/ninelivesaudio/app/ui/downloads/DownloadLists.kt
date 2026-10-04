package com.ninelivesaudio.app.ui.downloads

import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus

/** One download row with what the screen needs from its book. */
internal data class DownloadRow(
    val download: DownloadItem,
    val coverPath: String?,
    val bookIsDownloaded: Boolean,
)

/** The Downloads screen's two lists, newest first. */
internal data class DownloadLists(
    val active: List<DownloadRow>,
    val completed: List<DownloadRow>,
)

private val IN_PROGRESS = setOf(
    DownloadStatus.Queued,
    DownloadStatus.Downloading,
    DownloadStatus.Paused,
    DownloadStatus.Preparing,
)

/**
 * Sort every download row into the active and completed lists.
 *
 * Active holds Queued, Downloading, Paused and Preparing (a slot claim waiting
 * on its details fetch, kept visible so the tap never looks ignored).
 */
internal fun splitDownloadRows(rows: List<DownloadRow>): DownloadLists {
    val active = rows
        .filter { it.download.status in IN_PROGRESS }
        .sortedByDescending { it.download.startedAt ?: 0L }
    val completed = rows
        .filter { it.download.status == DownloadStatus.Completed }
        .sortedByDescending { it.download.completedAt ?: 0L }
    return DownloadLists(active = active, completed = completed)
}
