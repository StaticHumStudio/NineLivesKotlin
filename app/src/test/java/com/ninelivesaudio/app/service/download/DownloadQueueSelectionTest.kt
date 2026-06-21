package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The drain worker picks the next item with [selectNextDownload]. It must
 * resume an interrupted Downloading item before starting new Queued ones (so a
 * process-death survivor finishes first), take Queued items oldest-first, and
 * never pick Paused/Completed/Failed items.
 */
class DownloadQueueSelectionTest {

    private fun item(id: String, status: DownloadStatus, startedAt: Long?) =
        DownloadItem(id = id, status = status, startedAt = startedAt)

    @Test
    fun `returns null when nothing is downloadable`() {
        val items = listOf(
            item("a", DownloadStatus.Completed, 1),
            item("b", DownloadStatus.Paused, 2),
            item("c", DownloadStatus.Failed, 3),
        )
        assertNull(selectNextDownload(items))
    }

    @Test
    fun `picks the oldest queued item first`() {
        val items = listOf(
            item("new", DownloadStatus.Queued, 200),
            item("old", DownloadStatus.Queued, 100),
        )
        assertEquals("old", selectNextDownload(items)?.id)
    }

    @Test
    fun `resumes an interrupted download before starting a queued one`() {
        val items = listOf(
            item("queued-older", DownloadStatus.Queued, 100),
            item("downloading", DownloadStatus.Downloading, 500),
        )
        assertEquals("downloading", selectNextDownload(items)?.id)
    }

    @Test
    fun `never picks a paused item even if it is oldest`() {
        val items = listOf(
            item("paused-oldest", DownloadStatus.Paused, 1),
            item("queued", DownloadStatus.Queued, 100),
        )
        assertEquals("queued", selectNextDownload(items)?.id)
    }
}
