package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The connection status shown to the user must reflect OFFLINE the instant the
 * device has no network, even if a sync coroutine is still unwinding. Ranking
 * SYNCING above OFFLINE made airplane mode read "Syncing" until the in-flight
 * socket timed out (up to 30s), instead of dropping straight to "Offline".
 */
class ConnectionStatusTest {

    @Test
    fun `offline wins over a lingering sync flag`() {
        // Airplane mode flipped mid-sync: no network, but isSyncing not yet cleared.
        assertEquals(
            ConnectionStatus.OFFLINE,
            computeConnectionStatus(isOnline = false, isSyncing = true, isServerReachable = false),
        )
    }

    @Test
    fun `offline wins even if a stale reachable flag remains`() {
        assertEquals(
            ConnectionStatus.OFFLINE,
            computeConnectionStatus(isOnline = false, isSyncing = false, isServerReachable = true),
        )
    }

    @Test
    fun `online and syncing reports syncing`() {
        assertEquals(
            ConnectionStatus.SYNCING,
            computeConnectionStatus(isOnline = true, isSyncing = true, isServerReachable = false),
        )
    }

    @Test
    fun `online reachable and not syncing reports connected`() {
        assertEquals(
            ConnectionStatus.CONNECTED,
            computeConnectionStatus(isOnline = true, isSyncing = false, isServerReachable = true),
        )
    }

    @Test
    fun `online but server unreachable reports server unreachable`() {
        assertEquals(
            ConnectionStatus.SERVER_UNREACHABLE,
            computeConnectionStatus(isOnline = true, isSyncing = false, isServerReachable = false),
        )
    }
}
