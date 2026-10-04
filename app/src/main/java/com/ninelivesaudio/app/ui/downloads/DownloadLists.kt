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
 * on its details fetch, kept visible so the tap never looks ignored), plus
 * Failed. A failed download used to sit in neither list, so a server timeout
 * made the book vanish with no way to retry. A Failed row stays out only when
 * it is stale: the book got downloaded anyway, or a later attempt for the same
 * book exists (that row speaks for the book instead).
 */
internal fun splitDownloadRows(rows: List<DownloadRow>): DownloadLists {
    val byBook = rows.groupBy { it.download.audioBookId }
    val active = rows.filter { row ->
        when (row.download.status) {
            in IN_PROGRESS -> true
            DownloadStatus.Failed -> !row.isStaleFailure(byBook[row.download.audioBookId].orEmpty())
            else -> false
        }
    }.sortedByDescending { it.download.startedAt ?: 0L }
    val completed = rows
        .filter { it.download.status == DownloadStatus.Completed }
        .sortedByDescending { it.download.completedAt ?: 0L }
    return DownloadLists(active = active, completed = completed)
}

private fun DownloadRow.isStaleFailure(sameBook: List<DownloadRow>): Boolean {
    if (bookIsDownloaded) return true
    val startedAt = download.startedAt ?: 0L
    return sameBook.any { other ->
        other.download.id != download.id &&
            (other.download.status != DownloadStatus.Failed || (other.download.startedAt ?: 0L) > startedAt)
    }
}
