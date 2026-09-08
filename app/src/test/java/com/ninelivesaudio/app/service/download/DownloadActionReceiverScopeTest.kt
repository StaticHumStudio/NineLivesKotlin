package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadActionReceiverScopeTest {

    @Test
    fun `notification cancel selects only the active owner row`() {
        val prefix = "nlr1:owner:"
        val raw = item("raw-download", "raw-book", 1)
        val foreign = item("nlr1:foreign:download", "nlr1:foreign:book", 2)
        val active = item("${prefix}download", "${prefix}book", 3)

        assertEquals(active, selectNotificationCancelDownload(listOf(raw, foreign, active), prefix))
        assertNull(selectNotificationCancelDownload(listOf(raw, foreign), prefix))
    }

    private fun item(id: String, bookId: String, startedAt: Long) = DownloadItem(
        id = id,
        audioBookId = bookId,
        status = DownloadStatus.Queued,
        startedAt = startedAt,
    )
}
