package com.ninelivesaudio.app.ui.home

import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
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
import org.junit.Assert.assertNull
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
    fun `reconnect delegates exactly one probe-owning sync`() = runBlocking {
        var syncCalls = 0

        performHomeReconnect(
            isAudiobookshelfMode = { true },
            syncNow = { syncCalls += 1 },
        )

        assertEquals(1, syncCalls)
    }

    @Test
    fun `mode change to local before reconnect work starts skips the probe`() = runBlocking {
        val calls = mutableListOf<String>()

        performHomeReconnect(
            isAudiobookshelfMode = { false },
            syncNow = { calls += "sync" },
        )

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `reconnect work stays active until the probe-owning sync finishes`() = runBlocking {
        val calls = mutableListOf<String>()
        val syncStarted = CompletableDeferred<Unit>()
        val releaseSync = CompletableDeferred<Unit>()

        val reconnect = async(start = CoroutineStart.UNDISPATCHED) {
            performHomeReconnect(
                isAudiobookshelfMode = { true },
                syncNow = {
                    calls += "sync"
                    syncStarted.complete(Unit)
                    releaseSync.await()
                },
            )
        }

        syncStarted.await()
        assertFalse(reconnect.isCompleted)
        assertEquals(listOf("sync"), calls)

        releaseSync.complete(Unit)
        reconnect.await()

        assertEquals(listOf("sync"), calls)
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
                connectionStatus = ConnectionStatus.OFFLINE,
            ),
        )

        assertEquals(ConnectionStatus.OFFLINE, pillState.connectionStatus)
        assertEquals("Connection lost. Tap to reconnect.", pillState.reconnectContentDescription)
    }
}
