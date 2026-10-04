package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.SyncResult
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerReturnResyncTest {

    // Lets the listener's combine pipeline catch up on the single runBlocking thread.
    private suspend fun settle() = repeat(20) { yield() }

    @Test
    fun `a missed check flagged after the server already turned Connected still runs`() = runBlocking {
        // The entry check read offline, then the status turned Connected and
        // the listener found nothing to do (last sync clean, flag not set yet).
        val live = MutableStateFlow(false)
        val missed = MutableStateFlow(false)
        var checks = 0
        val listener = launch(start = CoroutineStart.UNDISPATCHED) {
            runServerReturnChecks(
                serverLive = live,
                missedCheckWhileNotReady = missed,
                shouldCheck = { flagged ->
                    shouldCheckOnServerReturn(
                        inForeground = true,
                        checkDue = checks == 0,
                        missedCheckWhileNotReady = flagged,
                        lastResult = SyncResult.SUCCESS,
                    )
                },
                check = { checks += 1 },
            )
        }
        live.value = true
        settle()
        assertEquals("nothing to do on the edge itself", 0, checks)
        // The skipped entry check returns and sets the flag.
        missed.value = true
        settle()
        assertEquals(1, checks)
        listener.cancel()
    }

    @Test
    fun `a missed check flagged while offline runs when the server returns`() = runBlocking {
        val live = MutableStateFlow(false)
        val missed = MutableStateFlow(false)
        var checks = 0
        val listener = launch(start = CoroutineStart.UNDISPATCHED) {
            runServerReturnChecks(
                serverLive = live,
                missedCheckWhileNotReady = missed,
                shouldCheck = { flagged -> flagged },
                check = { checks += 1 },
            )
        }
        missed.value = true
        settle()
        assertEquals("offline runs nothing", 0, checks)
        live.value = true
        settle()
        assertEquals(1, checks)
        listener.cancel()
    }

    @Test
    fun `a returning server resyncs after a failed or partial sync`() {
        assertTrue(shouldResyncOnServerReturn(SyncResult.FAILED))
        assertTrue(shouldResyncOnServerReturn(SyncResult.PARTIAL))
    }

    @Test
    fun `a returning server leaves a clean or missing record alone`() {
        assertFalse(shouldResyncOnServerReturn(SyncResult.SUCCESS))
        // No record yet: the startup sync owns the first attempt.
        assertFalse(shouldResyncOnServerReturn(null))
    }

    @Test
    fun `server session is live only when connected or syncing outside local mode`() {
        assertTrue(isServerSessionLive(ConnectionStatus.CONNECTED, AppMode.AUDIOBOOKSHELF))
        assertTrue(isServerSessionLive(ConnectionStatus.SYNCING, AppMode.AUDIOBOOKSHELF))
        assertFalse(isServerSessionLive(ConnectionStatus.SERVER_UNREACHABLE, AppMode.AUDIOBOOKSHELF))
        assertFalse(isServerSessionLive(ConnectionStatus.OFFLINE, AppMode.AUDIOBOOKSHELF))
        assertFalse(isServerSessionLive(ConnectionStatus.CONNECTED, AppMode.LOCAL))
    }

    @Test
    fun `a sync's own status flip is not a server return`() {
        // CONNECTED -> SYNCING -> CONNECTED is what every sync does to the
        // status. If that produced a rising edge, a sync that ended PARTIAL
        // would retrigger itself forever.
        val edges = listOf(
            ConnectionStatus.CONNECTED,
            ConnectionStatus.SYNCING,
            ConnectionStatus.CONNECTED,
        ).map { isServerSessionLive(it, AppMode.AUDIOBOOKSHELF) }.distinctUntilChangedList()
        assertEquals(listOf(true), edges)
    }

    @Test
    fun `an outage and recovery produces exactly one rising edge`() {
        val edges = listOf(
            ConnectionStatus.CONNECTED,
            ConnectionStatus.SERVER_UNREACHABLE,
            ConnectionStatus.OFFLINE,
            ConnectionStatus.CONNECTED,
            ConnectionStatus.SYNCING,
            ConnectionStatus.CONNECTED,
        ).map { isServerSessionLive(it, AppMode.AUDIOBOOKSHELF) }.distinctUntilChangedList()
        assertEquals(listOf(true, false, true), edges)
    }

    private fun <T> List<T>.distinctUntilChangedList(): List<T> =
        fold(mutableListOf()) { acc, v -> if (acc.lastOrNull() != v) acc.add(v); acc }
}
