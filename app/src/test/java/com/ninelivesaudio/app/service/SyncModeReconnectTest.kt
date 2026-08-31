package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AppMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncModeReconnectTest {

    @Test
    fun `local to Audiobookshelf requests reconnect`() {
        assertTrue(
            shouldReconnectForModeTransition(
                previousMode = AppMode.LOCAL,
                newMode = AppMode.AUDIOBOOKSHELF,
            ),
        )
    }

    @Test
    fun `leaving Audiobookshelf does not request reconnect`() {
        assertFalse(
            shouldReconnectForModeTransition(
                previousMode = AppMode.AUDIOBOOKSHELF,
                newMode = AppMode.LOCAL,
            ),
        )
    }

    @Test
    fun `unchanged mode does not request reconnect`() {
        assertFalse(
            shouldReconnectForModeTransition(
                previousMode = AppMode.AUDIOBOOKSHELF,
                newMode = AppMode.AUDIOBOOKSHELF,
            ),
        )
        assertFalse(
            shouldReconnectForModeTransition(
                previousMode = AppMode.LOCAL,
                newMode = AppMode.LOCAL,
            ),
        )
    }

    // Mode-change-during-reachability-check coverage lives in
    // RunSyncAttemptTest now (`eligibility is rechecked after the probe...`),
    // pinned against runSyncAttempt() -- the function syncNow() actually
    // calls. It used to live here against isSyncEligibleAfterReachability, a
    // stand-in helper production code had quietly stopped calling (issue #14's
    // adversarial review, finding 4): this test stayed green while the real
    // gate could have regressed underneath it.

    @Test
    fun `mode switch reconnect refreshes OS connectivity before anything probes`() = runBlocking {
        // The cached isOnline flag can lag a returned connection. The refresh
        // must come first or both the reachability check and the sync
        // short-circuit on stale state and the reconnect does nothing.
        val order = mutableListOf<String>()

        performModeSwitchReconnect(
            refreshIsOnline = { order += "refresh" },
            syncNow = { order += "sync" },
        )

        org.junit.Assert.assertEquals(listOf("refresh", "sync"), order)
    }
}
