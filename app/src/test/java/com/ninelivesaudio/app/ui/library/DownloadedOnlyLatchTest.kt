package com.ninelivesaudio.app.ui.library

import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadedOnlyLatchTest {

    @Test
    fun `auto-set filter reverts after reconnect`() {
        val latched = decideDownloadedOnlyFilter(
            previousStatus = ConnectionStatus.CONNECTED,
            newStatus = ConnectionStatus.SERVER_UNREACHABLE,
            current = DownloadedOnlyFilterState(
                showDownloadedOnly = false,
                autoDownloadedOnly = false,
            ),
        )

        assertEquals(
            DownloadedOnlyFilterState(
                showDownloadedOnly = true,
                autoDownloadedOnly = true,
            ),
            latched,
        )

        val reconnected = decideDownloadedOnlyFilter(
            previousStatus = ConnectionStatus.SERVER_UNREACHABLE,
            newStatus = ConnectionStatus.CONNECTED,
            current = latched,
        )

        assertEquals(
            DownloadedOnlyFilterState(
                showDownloadedOnly = false,
                autoDownloadedOnly = false,
            ),
            reconnected,
        )
    }

    @Test
    fun `manual filter remains enabled after reconnect`() {
        val reconnected = decideDownloadedOnlyFilter(
            previousStatus = ConnectionStatus.OFFLINE,
            newStatus = ConnectionStatus.CONNECTED,
            current = DownloadedOnlyFilterState(
                showDownloadedOnly = true,
                autoDownloadedOnly = false,
            ),
        )

        assertEquals(
            DownloadedOnlyFilterState(
                showDownloadedOnly = true,
                autoDownloadedOnly = false,
            ),
            reconnected,
        )
    }

    @Test
    fun `manual clear after auto-set survives status blip and reconnect`() {
        val latched = decideDownloadedOnlyFilter(
            previousStatus = ConnectionStatus.CONNECTED,
            newStatus = ConnectionStatus.OFFLINE,
            current = DownloadedOnlyFilterState(
                showDownloadedOnly = false,
                autoDownloadedOnly = false,
            ),
        )
        assertEquals(true, latched.autoDownloadedOnly)

        val manuallyCleared = DownloadedOnlyFilterState(
            showDownloadedOnly = false,
            autoDownloadedOnly = false,
        )
        val changedLostStatus = decideDownloadedOnlyFilter(
            previousStatus = ConnectionStatus.OFFLINE,
            newStatus = ConnectionStatus.SERVER_UNREACHABLE,
            current = manuallyCleared,
        )
        val reconnected = decideDownloadedOnlyFilter(
            previousStatus = ConnectionStatus.SERVER_UNREACHABLE,
            newStatus = ConnectionStatus.CONNECTED,
            current = changedLostStatus,
        )

        assertEquals(manuallyCleared, changedLostStatus)
        assertEquals(manuallyCleared, reconnected)
    }
}
