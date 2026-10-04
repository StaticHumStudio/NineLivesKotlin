package com.ninelivesaudio.app.ui.downloads

import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A failed download used to fall out of both lists, so a server timeout made the
 * book vanish from Downloads with no Retry. Failed rows now show in the active
 * list unless a later attempt or a finished download speaks for the book.
 */
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
    fun `a failed download shows in the active list`() {
        val lists = splitDownloadRows(listOf(row("f", DownloadStatus.Failed)))

        assertEquals(listOf("f"), lists.activeIds())
        assertEquals(emptyList<String>(), lists.completedIds())
    }

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
    fun `a failure for a book that got downloaded anyway stays hidden`() {
        val lists = splitDownloadRows(listOf(row("f", DownloadStatus.Failed, bookIsDownloaded = true)))

        assertEquals(emptyList<String>(), lists.activeIds())
    }

    @Test
    fun `a later attempt for the same book replaces the old failure`() {
        val lists = splitDownloadRows(
            listOf(
                row("old", DownloadStatus.Failed, bookId = "b", startedAt = 1),
                row("new", DownloadStatus.Downloading, bookId = "b", startedAt = 2),
            ),
        )

        assertEquals(listOf("new"), lists.activeIds())
    }

    @Test
    fun `only the newest of two failures for one book shows`() {
        val lists = splitDownloadRows(
            listOf(
                row("old", DownloadStatus.Failed, bookId = "b", startedAt = 1),
                row("new", DownloadStatus.Failed, bookId = "b", startedAt = 2),
            ),
        )

        assertEquals(listOf("new"), lists.activeIds())
    }

    @Test
    fun `failures for other books do not hide each other`() {
        val lists = splitDownloadRows(
            listOf(
                row("a", DownloadStatus.Failed, bookId = "a", startedAt = 1),
                row("b", DownloadStatus.Completed, bookId = "b", completedAt = 5),
            ),
        )

        assertEquals(listOf("a"), lists.activeIds())
    }

    @Test
    fun `lists are newest first`() {
        val lists = splitDownloadRows(
            listOf(
                row("older", DownloadStatus.Queued, startedAt = 1),
                row("newer", DownloadStatus.Failed, startedAt = 2),
                row("c1", DownloadStatus.Completed, completedAt = 10),
                row("c2", DownloadStatus.Completed, completedAt = 20),
            ),
        )

        assertEquals(listOf("newer", "older"), lists.activeIds())
        assertEquals(listOf("c2", "c1"), lists.completedIds())
    }

    @Test
    fun `the row's cover comes along`() {
        val lists = splitDownloadRows(listOf(row("q", DownloadStatus.Queued)))

        assertEquals("cover-q", lists.active.single().coverPath)
    }

    // Clear All used to delete the download rows and keep the files, so every
    // offline book vanished from Downloads. Book rows now list them.

    private fun book(id: String, title: String = id) =
        DownloadedBook(audioBookId = id, title = title, coverPath = "book-cover-$id")

    @Test
    fun `a downloaded book with no download row still shows as completed`() {
        val lists = splitDownloadRows(emptyList(), listOf(book("b")))

        assertEquals(listOf("${BOOK_ONLY_ID_PREFIX}b"), lists.completedIds())
        val entry = lists.completed.single()
        assertEquals("b", entry.download.audioBookId)
        assertEquals("book-cover-b", entry.coverPath)
        assertEquals(DownloadStatus.Completed, entry.download.status)
    }

    @Test
    fun `a completed row speaks for its book, so the book is listed once`() {
        val lists = splitDownloadRows(
            listOf(row("c", DownloadStatus.Completed, bookId = "b", completedAt = 5)),
            listOf(book("b")),
        )

        assertEquals(listOf("c"), lists.completedIds())
    }

    @Test
    fun `books without rows follow the completed rows, by title`() {
        val lists = splitDownloadRows(
            listOf(row("c", DownloadStatus.Completed, bookId = "x", completedAt = 5)),
            listOf(book("z", title = "zebra"), book("a", title = "Aardvark")),
        )

        assertEquals(
            listOf("c", "${BOOK_ONLY_ID_PREFIX}a", "${BOOK_ONLY_ID_PREFIX}z"),
            lists.completedIds(),
        )
    }
}
