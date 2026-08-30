package com.ninelivesaudio.app.ui.home

import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import com.ninelivesaudio.app.ui.components.ConnectionStatusPresentation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HomeReconnectPolicyTest {

    @Test
    fun `lost ABS connections can reconnect`() {
        assertTrue(
            isHomeReconnectAvailable(
                isLocalMode = false,
                hasAuthToken = true,
                connectionStatus = ConnectionStatus.SERVER_UNREACHABLE,
            ),
        )
        assertTrue(
            isHomeReconnectAvailable(
                isLocalMode = false,
                hasAuthToken = true,
                connectionStatus = ConnectionStatus.OFFLINE,
            ),
        )
    }

    @Test
    fun `healthy ABS connections cannot reconnect`() {
        assertFalse(
            isHomeReconnectAvailable(
                isLocalMode = false,
                hasAuthToken = true,
                connectionStatus = ConnectionStatus.CONNECTED,
            ),
        )
        assertFalse(
            isHomeReconnectAvailable(
                isLocalMode = false,
                hasAuthToken = true,
                connectionStatus = ConnectionStatus.SYNCING,
            ),
        )
    }

    @Test
    fun `local mode never offers server reconnect`() {
        assertFalse(
            isHomeReconnectAvailable(
                isLocalMode = true,
                hasAuthToken = true,
                connectionStatus = ConnectionStatus.SERVER_UNREACHABLE,
            ),
        )
        assertFalse(
            isHomeReconnectAvailable(
                isLocalMode = true,
                hasAuthToken = true,
                connectionStatus = ConnectionStatus.OFFLINE,
            ),
        )
    }

    @Test
    fun `signed-out ABS session cannot start reconnect work`() {
        assertFalse(
            isHomeReconnectAvailable(
                isLocalMode = false,
                hasAuthToken = false,
                connectionStatus = ConnectionStatus.OFFLINE,
            ),
        )
        assertFalse(
            isHomeReconnectAvailable(
                isLocalMode = false,
                hasAuthToken = false,
                connectionStatus = ConnectionStatus.SERVER_UNREACHABLE,
            ),
        )
    }

    @Test
    fun `reconnect refreshes isOnline once and delegates to sync exactly once`() = runBlocking {
        var refreshCalls = 0
        var syncCalls = 0

        performHomeReconnect(
            isAudiobookshelfMode = { true },
            refreshIsOnline = { refreshCalls += 1 },
            syncNow = { syncCalls += 1 },
        )

        assertEquals(1, refreshCalls)
        assertEquals(1, syncCalls)
    }

    @Test
    fun `mode change to local before reconnect work starts skips the refresh and sync`() = runBlocking {
        val calls = mutableListOf<String>()

        performHomeReconnect(
            isAudiobookshelfMode = { false },
            refreshIsOnline = { calls += "refresh" },
            syncNow = { calls += "sync" },
        )

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `reconnect work stays active until sync finishes`() = runBlocking {
        val calls = mutableListOf<String>()
        val syncStarted = CompletableDeferred<Unit>()
        val releaseSync = CompletableDeferred<Unit>()

        val reconnect = async(start = CoroutineStart.UNDISPATCHED) {
            performHomeReconnect(
                isAudiobookshelfMode = { true },
                refreshIsOnline = { calls += "refresh" },
                syncNow = {
                    calls += "sync"
                    syncStarted.complete(Unit)
                    releaseSync.await()
                },
            )
        }

        syncStarted.await()
        assertFalse(reconnect.isCompleted)
        assertEquals(listOf("refresh", "sync"), calls)

        releaseSync.complete(Unit)
        reconnect.await()

        assertEquals(listOf("refresh", "sync"), calls)
    }

    // ─── One probe per tap, not two ────────────────────────────────────────
    //
    // The pill offers RECONNECT off ConnectionStatus, which is itself derived
    // from the cached isOnline flag. Right after real connectivity returns
    // (before the OS NetworkCallback lands, or when it never lands at all),
    // that cached flag can still read false even though the network is up.
    // syncNow()'s own shouldRunSync pre-check trusts that same flag, so a
    // stale-false reading would make it silently no-op.
    //
    // The old fix forced its own probe before syncNow() ran, but syncNow()
    // already probes internally once its pre-check passes, so a tap sent two
    // sequential pings and let a transient failure of the redundant second
    // one suppress a sync the first one already proved was reachable. The
    // tap must instead only refresh the cached flag (no network request) and
    // let syncNow() own the single probe, exactly as it does for every other
    // caller.

    @Test
    fun `stale flag refreshed to online leads to a sync attempt with exactly one probe`() = runBlocking {
        // Models syncNow()'s own two gates: a cheap isOnline pre-check, then
        // the real network probe that becomes this action's one and only
        // ping. refreshIsOnline must run before this fake reads the flag.
        var isOnline = false
        var probeCalls = 0

        performHomeReconnect(
            isAudiobookshelfMode = { true },
            refreshIsOnline = { isOnline = true },
            syncNow = {
                if (isOnline) probeCalls += 1
            },
        )

        assertEquals(1, probeCalls)
    }

    @Test
    fun `refresh finding no network skips the sync without pinging`() = runBlocking {
        // The cached flag starts stale-true. The refresh is what corrects it.
        var isOnline = true
        var probeCalls = 0

        performHomeReconnect(
            isAudiobookshelfMode = { true },
            refreshIsOnline = { isOnline = false },
            syncNow = {
                if (isOnline) probeCalls += 1
            },
        )

        assertEquals(0, probeCalls)
    }

    @Test
    fun `reconnect taps share one active job and allow a later retry`() = runBlocking {
        val owner = HomeReconnectJobOwner(this)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var reconnectRuns = 0

        val first = owner.joinOrStart {
            reconnectRuns += 1
            firstStarted.complete(Unit)
            releaseFirst.await()
        }
        firstStarted.await()

        val joined = owner.joinOrStart { reconnectRuns += 1 }

        assertSame(first, joined)
        assertEquals(1, reconnectRuns)

        releaseFirst.complete(Unit)
        first.join()

        val retry = owner.joinOrStart { reconnectRuns += 1 }
        retry.join()

        assertNotSame(first, retry)
        assertEquals(2, reconnectRuns)
    }

    @Test
    fun `simultaneous reconnect taps receive the same active job`() = runBlocking {
        val callerCount = 16
        val barrier = CyclicBarrier(callerCount)
        val executor = Executors.newFixedThreadPool(callerCount)
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = HomeReconnectJobOwner(testScope)
        val releaseReconnect = CompletableDeferred<Unit>()
        val reconnectRuns = AtomicInteger()

        try {
            val futures = List(callerCount) {
                executor.submit<Job> {
                    barrier.await(5, TimeUnit.SECONDS)
                    owner.joinOrStart {
                        reconnectRuns.incrementAndGet()
                        releaseReconnect.await()
                    }
                }
            }
            val jobs = futures.map { it.get(5, TimeUnit.SECONDS) }

            withTimeout(1_000) {
                while (reconnectRuns.get() == 0) yield()
            }
            assertTrue(jobs.all { it === jobs.first() })
            assertEquals(1, reconnectRuns.get())

            releaseReconnect.complete(Unit)
            jobs.first().join()
        } finally {
            releaseReconnect.complete(Unit)
            testScope.cancel()
            executor.shutdownNow()
        }
    }

    @Test
    fun `empty ABS Home keeps the reconnect pill action`() {
        val pillState = homeConnectionPillState(
            HomeViewModel.UiState(
                showEmptyState = true,
                isLocalMode = false,
                hasAuthToken = true,
                connectionStatus = ConnectionStatus.OFFLINE,
            ),
        )

        assertEquals(ConnectionStatusPresentation.OFFLINE, pillState.presentation)
        assertEquals(HomeConnectionPillAction.RECONNECT, pillState.action)
        assertEquals("Connection lost. Tap to reconnect.", pillState.actionContentDescription)
    }

    @Test
    fun `signed-out ABS Home opens Settings instead of reconnecting`() {
        val pillState = homeConnectionPillState(
            HomeViewModel.UiState(
                isLocalMode = false,
                hasAuthToken = false,
                connectionStatus = ConnectionStatus.CONNECTED,
            ),
        )

        assertEquals(ConnectionStatusPresentation.SIGNED_OUT, pillState.presentation)
        assertEquals(HomeConnectionPillAction.OPEN_SETTINGS, pillState.action)
        assertEquals("Signed out. Tap to open Settings.", pillState.actionContentDescription)
    }

    @Test
    fun `unresolved ABS session keeps the old presentation without an action`() {
        val pillState = homeConnectionPillState(
            HomeViewModel.UiState(
                isLocalMode = false,
                hasAuthToken = null,
                connectionStatus = ConnectionStatus.OFFLINE,
            ),
        )

        assertEquals(ConnectionStatusPresentation.OFFLINE, pillState.presentation)
        assertEquals(HomeConnectionPillAction.NONE, pillState.action)
        assertEquals(null, pillState.actionContentDescription)
    }
}
