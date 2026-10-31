package com.ninelivesaudio.app.ui.bookdetail

import com.ninelivesaudio.app.domain.model.DownloadStatus
import com.ninelivesaudio.app.ui.bookdetail.BookDetailViewModel.DownloadButtonState
import org.junit.Assert.assertEquals
import kotlinx.coroutines.launch
import org.junit.Test

/** Book Detail follows the download row, and the book's own flag when there is no row (#89). */
class DownloadButtonStateTest {

    @Test
    fun `with no row a book deleted elsewhere reads as not downloaded`() {
        assertEquals(DownloadButtonState.NONE, downloadButtonState(null, bookIsDownloaded = false))
    }

    @Test
    fun `with no row a downloaded book reads as downloaded`() {
        assertEquals(DownloadButtonState.COMPLETED, downloadButtonState(null, bookIsDownloaded = true))
    }

    @Test
    fun `a preparing claim reads as queued`() {
        assertEquals(DownloadButtonState.QUEUED, downloadButtonState(DownloadStatus.Preparing, bookIsDownloaded = false))
        assertEquals(DownloadButtonState.QUEUED, downloadButtonState(DownloadStatus.Queued, bookIsDownloaded = false))
    }

    @Test
    fun `a failed or cancelled row reads as not downloaded`() {
        assertEquals(DownloadButtonState.NONE, downloadButtonState(DownloadStatus.Failed, bookIsDownloaded = false))
        assertEquals(DownloadButtonState.NONE, downloadButtonState(DownloadStatus.Cancelled, bookIsDownloaded = false))
    }

    @Test
    fun `running paused and completed rows map to their buttons`() {
        assertEquals(DownloadButtonState.DOWNLOADING, downloadButtonState(DownloadStatus.Downloading, false))
        assertEquals(DownloadButtonState.PAUSED, downloadButtonState(DownloadStatus.Paused, false))
        assertEquals(DownloadButtonState.COMPLETED, downloadButtonState(DownloadStatus.Completed, false))
    }
}

/** The page follows its own book's row and flag, not every download in the app. */
class BookDownloadFeedTest {

    private fun row(id: String, book: String, status: DownloadStatus = DownloadStatus.Downloading, bytes: Long = 0) =
        com.ninelivesaudio.app.data.local.entity.DownloadItemEntity(
            id = id,
            audioBookId = book,
            title = "t",
            status = status.ordinal,
            downloadedBytes = bytes,
        )

    @Test
    fun `another book's download ticking does not emit again`() = kotlinx.coroutines.runBlocking {
        val rows = kotlinx.coroutines.flow.MutableStateFlow(listOf(row("a", "mine"), row("b", "other", bytes = 1)))
        val seen = mutableListOf<BookDownloadFeed>()
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            bookDownloadFeed("mine", rows, kotlinx.coroutines.flow.flowOf(false)).collect { seen += it }
        }
        rows.value = listOf(row("a", "mine"), row("b", "other", bytes = 2))
        rows.value = listOf(row("a", "mine"), row("b", "other", bytes = 3))
        job.cancel()
        assertEquals(listOf(BookDownloadFeed("a", DownloadStatus.Downloading, false)), seen)
    }

    @Test
    fun `a local import ignores the stored downloaded flag`() {
        assertEquals(false, shownAsDownloaded(isLocal = true, storedFlag = true, current = false))
        assertEquals(true, shownAsDownloaded(isLocal = true, storedFlag = true, current = true))
        assertEquals(true, shownAsDownloaded(isLocal = false, storedFlag = true, current = false))
        assertEquals(false, shownAsDownloaded(isLocal = false, storedFlag = false, current = true))
    }
}
