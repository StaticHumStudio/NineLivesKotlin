package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.SyncResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A network change while the app sat in the background (Android blocks its
 * network there) used to leave it stuck "Offline" on return, with a streaming
 * user's Library flipped to Downloaded only, until another network change or
 * a manual reconnect tap.
 */
class ForegroundNetworkRecoveryTest {

    @Test
    fun `an onLost for the old network does not mark the app offline while another is up`() {
        // Backgrounded and network-blocked: the OS default reads null, but the
        // new Wi-Fi the callbacks reported is still there.
        assertTrue(networkStateSaysOnline(defaultHasInternet = false, knownNetworksHaveInternet = listOf(true)))
        assertTrue(networkStateSaysOnline(defaultHasInternet = true, knownNetworksHaveInternet = emptyList()))
    }

    @Test
    fun `no network anywhere is offline`() {
        assertFalse(networkStateSaysOnline(defaultHasInternet = false, knownNetworksHaveInternet = emptyList()))
        assertFalse(networkStateSaysOnline(defaultHasInternet = false, knownNetworksHaveInternet = listOf(false)))
    }

    @Test
    fun `a foreground entry that finds the network again probes at once`() {
        // The stuck case: stale offline flag, the re-read finds Wi-Fi.
        assertTrue(foregroundEntryProbes(wasOnline = false, isOnline = true, isServerReachable = false, backgroundForMs = 70_000L))
        // Even after a quick flip.
        assertTrue(foregroundEntryProbes(wasOnline = false, isOnline = true, isServerReachable = false, backgroundForMs = 1_000L))
    }

    @Test
    fun `a foreground entry probes a server not marked reachable`() {
        assertTrue(foregroundEntryProbes(wasOnline = true, isOnline = true, isServerReachable = false, backgroundForMs = 1_000L))
    }

    @Test
    fun `a foreground entry with no network sends nothing`() {
        assertFalse(foregroundEntryProbes(wasOnline = true, isOnline = false, isServerReachable = false, backgroundForMs = 70_000L))
        assertFalse(foregroundEntryProbes(wasOnline = false, isOnline = false, isServerReachable = false, backgroundForMs = 70_000L))
    }

    @Test
    fun `a connected app re-probes only after a real trip to the background`() {
        assertTrue(foregroundEntryProbes(wasOnline = true, isOnline = true, isServerReachable = true, backgroundForMs = FOREGROUND_REPROBE_AFTER_MS))
        assertFalse(foregroundEntryProbes(wasOnline = true, isOnline = true, isServerReachable = true, backgroundForMs = 1_000L))
        // Cold start: startMonitoring already probes.
        assertFalse(foregroundEntryProbes(wasOnline = true, isOnline = true, isServerReachable = true, backgroundForMs = null))
    }

    @Test
    fun `settle rereads stop at the first one that finds a network`() = runBlocking {
        val waits = mutableListOf<Long>()
        val answers = ArrayDeque(listOf(false, true, true))
        val reads = rereadUntilOnline(FOREGROUND_SETTLE_REREADS_MS, sleep = { waits += it }) { answers.removeFirst() }
        assertEquals(2, reads)
        assertEquals(FOREGROUND_SETTLE_REREADS_MS.take(2), waits)
    }

    @Test
    fun `settle rereads give up after the last delay`() = runBlocking {
        val reads = rereadUntilOnline(FOREGROUND_SETTLE_REREADS_MS, sleep = {}) { false }
        assertEquals(FOREGROUND_SETTLE_REREADS_MS.size, reads)
        assertTrue("bounded, a few seconds at most", FOREGROUND_SETTLE_REREADS_MS.sum() <= 10_000L)
    }

    @Test
    fun `an entry check skipped while offline runs once the server is back`() {
        assertTrue(
            shouldCheckOnServerReturn(
                inForeground = true,
                checkDue = true,
                missedCheckWhileNotReady = true,
                lastResult = SyncResult.SUCCESS,
            ),
        )
    }

    @Test
    fun `a server return after a clean sync and no missed check stays quiet`() {
        assertFalse(
            shouldCheckOnServerReturn(
                inForeground = true,
                checkDue = true,
                missedCheckWhileNotReady = false,
                lastResult = SyncResult.SUCCESS,
            ),
        )
    }

    @Test
    fun `the missed check still honors the foreground rule and the debounce`() {
        assertFalse(
            shouldCheckOnServerReturn(inForeground = false, checkDue = true, missedCheckWhileNotReady = true, lastResult = SyncResult.SUCCESS),
        )
        assertFalse(
            shouldCheckOnServerReturn(inForeground = true, checkDue = false, missedCheckWhileNotReady = true, lastResult = SyncResult.SUCCESS),
        )
    }

    @Test
    fun `a failed last sync still resyncs on server return`() {
        assertTrue(
            shouldCheckOnServerReturn(inForeground = true, checkDue = true, missedCheckWhileNotReady = false, lastResult = SyncResult.FAILED),
        )
    }
}
