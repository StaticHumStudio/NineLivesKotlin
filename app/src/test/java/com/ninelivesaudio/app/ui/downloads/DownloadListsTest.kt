package com.ninelivesaudio.app.ui.downloads

import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Downloads screen's lists come from one joined query, split here. */
class DownloadListsTest {

    private fun row(
        id: String,
        status: DownloadStatus,
        bookId: String = "book-$id",
        startedAt: Long = 0L,
        completedAt: Long? = null,
        bookIsDownloaded: Boolean = false,
    ) = DownloadRow(
        download = DownloadItem(
            id = id,
            audioBookId = bookId,
            title = id,
            status = status,
            startedAt = startedAt,
            completedAt = completedAt,
        ),
        coverPath = "cover-$id",
        bookIsDownloaded = bookIsDownloaded,
    )

    private fun DownloadLists.activeIds() = active.map { it.download.id }
    private fun DownloadLists.completedIds() = completed.map { it.download.id }

    @Test
    fun `in progress statuses stay active and completed goes to completed`() {
        val lists = splitDownloadRows(
            listOf(
                row("q", DownloadStatus.Queued, startedAt = 4),
                row("d", DownloadStatus.Downloading, startedAt = 3),
                row("p", DownloadStatus.Paused, startedAt = 2),
                row("prep", DownloadStatus.Preparing, startedAt = 1),
                row("c", DownloadStatus.Completed, completedAt = 9),
                row("x", DownloadStatus.Cancelled),
            ),
        )

        assertEquals(listOf("q", "d", "p", "prep"), lists.activeIds())
        assertEquals(listOf("c"), lists.completedIds())
    }

    @Test
    fun `the row's cover comes along`() {
        val lists = splitDownloadRows(listOf(row("q", DownloadStatus.Queued)))

        assertEquals("cover-q", lists.active.single().coverPath)
    }
}
