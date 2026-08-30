package com.ninelivesaudio.app.ui.home

import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class HomeReconnectPolicyTest {

    @Test
    fun `lost ABS connections can reconnect`() {
        assertTrue(
            isHomeReconnectAvailable(
                isLocalMode = false,
                connectionStatus = ConnectionStatus.SERVER_UNREACHABLE,
            ),
        )
        assertTrue(
            isHomeReconnectAvailable(
                isLocalMode = false,
                connectionStatus = ConnectionStatus.OFFLINE,
            ),
        )
    }

    @Test
    fun `healthy ABS connections cannot reconnect`() {
        assertFalse(
            isHomeReconnectAvailable(
                isLocalMode = false,
                connectionStatus = ConnectionStatus.CONNECTED,
            ),
        )
        assertFalse(
            isHomeReconnectAvailable(
                isLocalMode = false,
                connectionStatus = ConnectionStatus.SYNCING,
            ),
        )
    }

    @Test
    fun `local mode never offers server reconnect`() {
        assertFalse(
            isHomeReconnectAvailable(
                isLocalMode = true,
                connectionStatus = ConnectionStatus.SERVER_UNREACHABLE,
            ),
        )
        assertFalse(
            isHomeReconnectAvailable(
                isLocalMode = true,
                connectionStatus = ConnectionStatus.OFFLINE,
            ),
        )
    }

    @Test
    fun `lost connection action explains how to reconnect`() {
        assertEquals(
            "Connection lost. Tap to reconnect.",
            homeReconnectContentDescription(
                isLocalMode = false,
                connectionStatus = ConnectionStatus.OFFLINE,
            ),
        )
        assertNull(
            homeReconnectContentDescription(
                isLocalMode = true,
                connectionStatus = ConnectionStatus.OFFLINE,
            ),
        )
        assertNull(
            homeReconnectContentDescription(
                isLocalMode = false,
                connectionStatus = ConnectionStatus.CONNECTED,
            ),
        )
    }

    @Test
    fun `reconnect requests reachability before syncing`() = runBlocking {
        val calls = mutableListOf<String>()

        performHomeReconnect(
            requestReachabilityCheck = { calls += "reachability" },
            syncNow = { calls += "sync" },
        )

        assertEquals(listOf("reachability", "sync"), calls)
    }

    @Test
    fun `empty ABS Home keeps the reconnect pill action`() {
        val pillState = homeConnectionPillState(
            HomeViewModel.UiState(
                showEmptyState = true,
                isLocalMode = false,
                connectionStatus = ConnectionStatus.OFFLINE,
            ),
        )

        assertEquals(ConnectionStatus.OFFLINE, pillState.connectionStatus)
        assertEquals("Connection lost. Tap to reconnect.", pillState.reconnectContentDescription)
    }
}
