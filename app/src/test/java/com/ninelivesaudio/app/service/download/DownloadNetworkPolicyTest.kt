package com.ninelivesaudio.app.service.download

import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import com.ninelivesaudio.app.domain.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Download on Wi-Fi only" (#79). The single drain worker waits for an
 * unmetered network while the setting is on, a per-download override runs it
 * on any connection without touching the setting, and a waiting download says
 * it is waiting instead of looking stuck.
 */
class DownloadNetworkPolicyTest {

    private fun OneTimeWorkRequest.networkType(): NetworkType = workSpec.constraints.requiredNetworkType
    private fun OneTimeWorkRequest.waitsForUnmetered(): Boolean = workSpec.input.getBoolean(KEY_DRAIN_UNMETERED, false)

    private fun requestFor(wifiOnly: Boolean, overrideQueued: Boolean) =
        drainRequest(drainWaitsForUnmetered(wifiOnly = wifiOnly, overrideQueued = overrideQueued))

    // ─── The worker request ──────────────────────────────────────────────────

    @Test
    fun `with Wi-Fi only on the drain waits for an unmetered network`() {
        val request = requestFor(wifiOnly = true, overrideQueued = false)

        assertEquals(NetworkType.UNMETERED, request.networkType())
        assertTrue(request.waitsForUnmetered())
    }

    @Test
    fun `with Wi-Fi only off the drain runs on any connection`() {
        val request = requestFor(wifiOnly = false, overrideQueued = false)

        assertEquals(NetworkType.CONNECTED, request.networkType())
        assertFalse(request.waitsForUnmetered())
    }

    @Test
    fun `a mobile data override runs the drain on any connection`() {
        val request = requestFor(wifiOnly = true, overrideQueued = true)

        assertEquals(NetworkType.CONNECTED, request.networkType())
        assertFalse(request.waitsForUnmetered())
    }

    // ─── What a drain may download ───────────────────────────────────────────

    @Test
    fun `a drain on any connection only takes overridden books while Wi-Fi only is on`() {
        assertTrue(mayDownloadOnDrain(wifiOnly = true, drainUnmetered = false, overridden = true))
        assertFalse(mayDownloadOnDrain(wifiOnly = true, drainUnmetered = false, overridden = false))
    }

    @Test
    fun `a drain on Wi-Fi takes every book`() {
        assertTrue(mayDownloadOnDrain(wifiOnly = true, drainUnmetered = true, overridden = false))
    }

    @Test
    fun `with Wi-Fi only off every book goes, even on an old request`() {
        assertTrue(mayDownloadOnDrain(wifiOnly = false, drainUnmetered = false, overridden = false))
    }

    // ─── Already queued work when the setting flips ──────────────────────────

    @Test
    fun `turning Wi-Fi only on stops a book downloading on mobile data`() {
        assertTrue(
            wifiRuleChangeRestartsDrain(wifiOnly = true, drainRunning = true, drainUnmetered = false, engineRowOverridden = false)
        )
    }

    @Test
    fun `turning Wi-Fi only on leaves a book the user sent over mobile data running`() {
        assertFalse(
            wifiRuleChangeRestartsDrain(wifiOnly = true, drainRunning = true, drainUnmetered = false, engineRowOverridden = true)
        )
    }

    @Test
    fun `turning Wi-Fi only on leaves a drain already on Wi-Fi running`() {
        assertFalse(
            wifiRuleChangeRestartsDrain(wifiOnly = true, drainRunning = true, drainUnmetered = true, engineRowOverridden = false)
        )
    }

    @Test
    fun `turning Wi-Fi only off lets a drain on any connection carry on`() {
        assertFalse(
            wifiRuleChangeRestartsDrain(wifiOnly = false, drainRunning = true, drainUnmetered = false, engineRowOverridden = false)
        )
    }

    @Test
    fun `turning Wi-Fi only off frees a drain still bound to Wi-Fi`() {
        // Left alone, losing Wi-Fi later would park the queue with the setting off.
        assertTrue(
            wifiRuleChangeRestartsDrain(wifiOnly = false, drainRunning = true, drainUnmetered = true, engineRowOverridden = false)
        )
    }

    @Test
    fun `with no drain running a flip re-enqueues the queue under the new rule`() {
        assertTrue(
            wifiRuleChangeRestartsDrain(wifiOnly = false, drainRunning = false, drainUnmetered = false, engineRowOverridden = false)
        )
        assertTrue(
            wifiRuleChangeRestartsDrain(wifiOnly = true, drainRunning = false, drainUnmetered = false, engineRowOverridden = false)
        )
    }

    // ─── The override ────────────────────────────────────────────────────────

    @Test
    fun `an override restarts a drain that is waiting or stuck on Wi-Fi rules`() {
        assertTrue(overrideRestartsDrain(drainRunning = false, drainUnmetered = false))
        assertTrue(overrideRestartsDrain(drainRunning = true, drainUnmetered = true))
    }

    @Test
    fun `an override joins a drain already running on any connection`() {
        assertFalse(overrideRestartsDrain(drainRunning = true, drainUnmetered = false))
    }

    @Test
    fun `overrides for rows that are gone are dropped`() {
        assertEquals(setOf("a"), liveOverrides(setOf("a", "gone"), liveRowIds = listOf("a", "b")))
    }

    // ─── Starting the drain ──────────────────────────────────────────────────

    @Test
    fun `adding work joins a running drain and replaces an idle one`() {
        assertEquals(ExistingWorkPolicy.KEEP, drainWorkPolicy(replace = false, drainRunning = true))
        // An idle drain may be sitting on the old network rule, so it is swapped.
        assertEquals(ExistingWorkPolicy.REPLACE, drainWorkPolicy(replace = false, drainRunning = false))
        assertEquals(ExistingWorkPolicy.REPLACE, drainWorkPolicy(replace = true, drainRunning = true))
    }

    // ─── Saying it is waiting ────────────────────────────────────────────────

    @Test
    fun `a queued book on mobile data with Wi-Fi only on is waiting for Wi-Fi`() {
        assertTrue(isWaitingForWifi(DownloadStatus.Queued, wifiOnly = true, metered = true, overridden = false))
    }

    @Test
    fun `a book interrupted when Wi-Fi dropped is waiting, not stuck`() {
        assertTrue(isWaitingForWifi(DownloadStatus.Downloading, wifiOnly = true, metered = true, overridden = false))
    }

    @Test
    fun `nothing waits on Wi-Fi, with the setting off, or once overridden`() {
        assertFalse(isWaitingForWifi(DownloadStatus.Queued, wifiOnly = true, metered = false, overridden = false))
        assertFalse(isWaitingForWifi(DownloadStatus.Queued, wifiOnly = false, metered = true, overridden = false))
        assertFalse(isWaitingForWifi(DownloadStatus.Queued, wifiOnly = true, metered = true, overridden = true))
    }

    @Test
    fun `paused and failed books are not waiting for Wi-Fi`() {
        assertFalse(isWaitingForWifi(DownloadStatus.Paused, wifiOnly = true, metered = true, overridden = false))
        assertFalse(isWaitingForWifi(DownloadStatus.Failed, wifiOnly = true, metered = true, overridden = false))
    }
}
