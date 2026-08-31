package com.ninelivesaudio.app.ui.components

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStatusPresentationTest {

    private val reachabilityStates = listOf(
        ConnectionStatus.CONNECTED,
        ConnectionStatus.SYNCING,
        ConnectionStatus.OFFLINE,
        ConnectionStatus.SERVER_UNREACHABLE,
    )

    @Test
    fun `ABS session preserves every reachability presentation`() {
        assertEquals(
            listOf(
                ConnectionStatusPresentation.CONNECTED,
                ConnectionStatusPresentation.SYNCING,
                ConnectionStatusPresentation.OFFLINE,
                ConnectionStatusPresentation.SERVER_UNREACHABLE,
            ),
            reachabilityStates.map { status ->
                connectionStatusPresentation(
                    appMode = AppMode.AUDIOBOOKSHELF,
                    hasAuthToken = true,
                    connectionStatus = status,
                )
            },
        )
    }

    @Test
    fun `ABS without a session is signed out for every reachability state`() {
        assertEquals(
            listOf(
                ConnectionStatusPresentation.SIGNED_OUT,
                ConnectionStatusPresentation.SIGNED_OUT,
                ConnectionStatusPresentation.SIGNED_OUT,
                ConnectionStatusPresentation.SIGNED_OUT,
            ),
            reachabilityStates.map { status ->
                connectionStatusPresentation(
                    appMode = AppMode.AUDIOBOOKSHELF,
                    hasAuthToken = false,
                    connectionStatus = status,
                )
            },
        )
    }

    @Test
    fun `ABS with unresolved session state preserves reachability presentation`() {
        assertEquals(
            listOf(
                ConnectionStatusPresentation.CONNECTED,
                ConnectionStatusPresentation.SYNCING,
                ConnectionStatusPresentation.OFFLINE,
                ConnectionStatusPresentation.SERVER_UNREACHABLE,
            ),
            reachabilityStates.map { status ->
                connectionStatusPresentation(
                    appMode = AppMode.AUDIOBOOKSHELF,
                    hasAuthToken = null,
                    connectionStatus = status,
                )
            },
        )
    }

    @Test
    fun `local mode stays local for every session state`() {
        assertEquals(
            List(12) { ConnectionStatusPresentation.LOCAL },
            listOf<Boolean?>(null, false, true).flatMap { hasAuthToken ->
                reachabilityStates.map { status ->
                    connectionStatusPresentation(
                        appMode = AppMode.LOCAL,
                        hasAuthToken = hasAuthToken,
                        connectionStatus = status,
                    )
                }
            },
        )
    }
}
