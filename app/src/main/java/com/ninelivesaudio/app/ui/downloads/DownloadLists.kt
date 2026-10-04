package com.ninelivesaudio.app.ui.downloads

import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus

/** One download row with what the screen needs from its book. */
internal data class DownloadRow(
    val download: DownloadItem,
    val coverPath: String?,
    val bookIsDownloaded: Boolean,
)

/** A downloaded book as the screen needs it, from the book row. */
internal data class DownloadedBook(
    val audioBookId: String,
    val title: String,
    val coverPath: String?,
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
 *
 * Completed lists every book on the device. A Completed row speaks for its
 * book, and a downloaded book with no Completed row (its row was cleared, or
 * the engine has not written it yet) is listed from [downloadedBooks] after
 * them, by title. Without that, an offline book vanished from Downloads the
 * moment its tracking row went.
 */
internal fun splitDownloadRows(
    rows: List<DownloadRow>,
    downloadedBooks: List<DownloadedBook> = emptyList(),
): DownloadLists {
    val byBook = rows.groupBy { it.download.audioBookId }
    val active = rows.filter { row ->
        when (row.download.status) {
            in IN_PROGRESS -> true
            DownloadStatus.Failed -> !row.isStaleFailure(byBook[row.download.audioBookId].orEmpty())
            else -> false
        }
    }.sortedByDescending { it.download.startedAt ?: 0L }
    val completedRows = rows
        .filter { it.download.status == DownloadStatus.Completed }
        .sortedByDescending { it.download.completedAt ?: 0L }
    val listedBooks = completedRows.mapTo(HashSet()) { it.download.audioBookId }
    val bookOnly = downloadedBooks
        .filter { it.audioBookId !in listedBooks }
        .sortedBy { it.title.lowercase() }
        .map { book ->
            DownloadRow(
                download = DownloadItem(
                    id = "$BOOK_ONLY_ID_PREFIX${book.audioBookId}",
                    audioBookId = book.audioBookId,
                    title = book.title,
                    status = DownloadStatus.Completed,
                ),
                coverPath = book.coverPath,
                bookIsDownloaded = true,
            )
        }
    val completed = completedRows + bookOnly
    return DownloadLists(active = active, completed = completed)
}

/** Id prefix for a Completed entry built from a book row, never a real download id. */
internal const val BOOK_ONLY_ID_PREFIX = "book:"

private fun DownloadRow.isStaleFailure(sameBook: List<DownloadRow>): Boolean {
    if (bookIsDownloaded) return true
    val startedAt = download.startedAt ?: 0L
    return sameBook.any { other ->
        other.download.id != download.id &&
            (other.download.status != DownloadStatus.Failed || (other.download.startedAt ?: 0L) > startedAt)
    }
}
