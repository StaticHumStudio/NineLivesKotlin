package com.ninelivesaudio.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SyncManager.syncNow must do an internet check before attempting a server
 * sync. Without it, the periodic timer fired sync calls in airplane mode,
 * flipping isSyncing=true (UI: "Syncing") and hanging on doomed sockets
 * instead of dropping the app straight to offline behavior.
 */
class SyncGateTest {

    @Test
    fun `syncs when online, remote mode, authenticated`() {
        assertTrue(shouldRunSync(isOnline = true, isLocalMode = false, hasAuth = true))
    }

    @Test
    fun `does not sync when offline`() {
        // Airplane mode: the bug. No network means no sync attempt.
        assertFalse(shouldRunSync(isOnline = false, isLocalMode = false, hasAuth = true))
    }

    @Test
    fun `does not sync in local mode`() {
        assertFalse(shouldRunSync(isOnline = true, isLocalMode = true, hasAuth = true))
    }

    @Test
    fun `does not sync without an auth token`() {
        assertFalse(shouldRunSync(isOnline = true, isLocalMode = false, hasAuth = false))
    }
}
