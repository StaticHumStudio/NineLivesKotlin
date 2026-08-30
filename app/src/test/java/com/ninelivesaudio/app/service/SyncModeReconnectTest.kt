package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AppMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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

    @Test
    fun `mode change during reachability check blocks remote sync`() = runBlocking {
        val checkStarted = CompletableDeferred<Unit>()
        val releaseCheck = CompletableDeferred<Unit>()
        var stillEligible = true
        val result = async {
            isSyncEligibleAfterReachability(
                checkServerReachable = {
                    checkStarted.complete(Unit)
                    releaseCheck.await()
                    true
                },
                isStillEligible = { stillEligible },
            )
        }

        checkStarted.await()
        stillEligible = false
        releaseCheck.complete(Unit)

        assertFalse(result.await())
    }

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
