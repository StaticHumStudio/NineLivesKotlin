package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.domain.model.DownloadStatus
import com.ninelivesaudio.app.entitlement.FreeTier
import com.ninelivesaudio.app.service.DownloadManager
import com.ninelivesaudio.app.ui.downloads.resumeNotice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * On the free tier a failed download gives up the slot, so another book can
 * download meanwhile. Retry on the failed one used to requeue it without
 * asking, and it sat in Queued forever because the drain only runs the slot
 * winner. Retry now asks the slot first and says so when it is taken.
 */
class ResumeDecisionTest {

    @Test
    fun `retrying a failed download while another book holds the slot is refused`() = runBlocking {
        assertEquals(ResumeDecision.BLOCKED_BY_FREE_SLOT, decideResume(DownloadStatus.Failed) { false })
    }

    @Test
    fun `retrying a failed download with the slot free requeues it`() = runBlocking {
        assertEquals(ResumeDecision.REQUEUE, decideResume(DownloadStatus.Failed) { true })
    }

    @Test
    fun `a paused download the slot no longer favours is refused too`() = runBlocking {
        assertEquals(ResumeDecision.BLOCKED_BY_FREE_SLOT, decideResume(DownloadStatus.Paused) { false })
        assertEquals(ResumeDecision.REQUEUE, decideResume(DownloadStatus.Paused) { true })
    }

    @Test
    fun `other statuses are left alone without asking the slot`() = runBlocking {
        for (status in listOf(
            DownloadStatus.Queued,
            DownloadStatus.Downloading,
            DownloadStatus.Completed,
            DownloadStatus.Cancelled,
            DownloadStatus.Preparing,
        )) {
            assertEquals(ResumeDecision.IGNORE, decideResume(status) { error("slot asked for $status") })
        }
    }

    @Test
    fun `a refused retry tells the user why`() {
        assertEquals(FreeTier.DOWNLOAD_SLOT_NOTICE, resumeNotice(DownloadManager.ResumeResult.BLOCKED_BY_FREE_SLOT))
        assertNull(resumeNotice(DownloadManager.ResumeResult.RESUMED))
        assertNull(resumeNotice(DownloadManager.ResumeResult.NOT_RESUMABLE))
    }
}
