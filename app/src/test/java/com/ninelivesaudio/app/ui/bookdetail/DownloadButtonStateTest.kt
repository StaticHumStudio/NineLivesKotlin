package com.ninelivesaudio.app.ui.bookdetail

import com.ninelivesaudio.app.domain.model.DownloadStatus
import com.ninelivesaudio.app.ui.bookdetail.BookDetailViewModel.DownloadButtonState
import org.junit.Assert.assertEquals
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
