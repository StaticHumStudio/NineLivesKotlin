package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.domain.model.DownloadStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine writes the Completed download row before it marks the book
 * downloaded, and fetches the cover in between. A pause that lands there
 * cancels the cover fetch, so the row says Completed while the book has no
 * local copy. Pause used to leave any Completed row alone, which stranded it:
 * the worker skips it, Resume rejects it, and offline play never unlocks.
 */
class PauseNearCompletionTest {

    @Test
    fun `a finished download is left alone`() {
        assertTrue(
            pauseKeepsCompletedRow(DownloadStatus.Completed.ordinal, isDownloaded = 1, localPath = "/books/a"),
        )
    }

    @Test
    fun `a completed row without a downloaded book is paused so resume finishes it`() {
        assertFalse(pauseKeepsCompletedRow(DownloadStatus.Completed.ordinal, isDownloaded = 0, localPath = null))
        assertFalse(pauseKeepsCompletedRow(DownloadStatus.Completed.ordinal, isDownloaded = 1, localPath = null))
        assertFalse(pauseKeepsCompletedRow(DownloadStatus.Completed.ordinal, isDownloaded = 1, localPath = ""))
        assertFalse(pauseKeepsCompletedRow(DownloadStatus.Completed.ordinal, isDownloaded = null, localPath = null))
    }

    @Test
    fun `a row still downloading is paused`() {
        assertFalse(pauseKeepsCompletedRow(DownloadStatus.Downloading.ordinal, isDownloaded = 1, localPath = "/books/a"))
    }
}
