package com.ninelivesaudio.app.ui.settings

import com.ninelivesaudio.app.data.remote.AuthSessionIdentity
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.service.DisconnectBarrier
import com.ninelivesaudio.app.service.disconnectBarrierBlocksLoad
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SettingsDisconnectFlowTest {

    @Test
    fun `ABS disconnect resets now playing before logout`() = runBlocking {
        val effects = mutableListOf<String>()

        disconnectSession(
            appMode = AppMode.AUDIOBOOKSHELF,
            resetNowPlaying = { effects += "reset" },
            logout = { effects += "logout" },
        )

        assertEquals(listOf("reset", "logout"), effects)
    }

    @Test
    fun `LOCAL disconnect leaves playback alone`() = runBlocking {
        val effects = mutableListOf<String>()

        disconnectSession(
            appMode = AppMode.LOCAL,
            resetNowPlaying = { effects += "reset" },
            logout = { effects += "logout" },
        )

        assertEquals(listOf("logout"), effects)
    }

    @Test
    fun `LOCAL disconnect still clears a lingering remote book`() = runBlocking {
        // Switch an active server session to Local, then disconnect: the mini
        // player still holds the remote book, and the UI mode alone must not
        // let it survive the logout.
        val effects = mutableListOf<String>()

        disconnectSession(
            appMode = AppMode.LOCAL,
            holdsRemoteBook = true,
            resetNowPlaying = { effects += "reset" },
            logout = { effects += "logout" },
        )

        assertEquals(listOf("reset", "logout"), effects)
    }

    @Test
    fun `confirmed disconnect finishes logout after caller cancellation`() = runBlocking {
        val resetStarted = CompletableDeferred<Unit>()
        val releaseReset = CompletableDeferred<Unit>()
        val authOperationMutex = Mutex()
        val effects = mutableListOf<String>()
        val disconnectJob = launch {
            runConfirmedDisconnectOperation(
                authOperationMutex = authOperationMutex,
                isCurrent = { true },
            ) {
                disconnectSession(
                    appMode = AppMode.AUDIOBOOKSHELF,
                    resetNowPlaying = {
                        effects += "reset"
                        resetStarted.complete(Unit)
                        releaseReset.await()
                        effects += "reset complete"
                    },
                    logout = { effects += "logout" },
                )
            }
        }

        resetStarted.await()
        disconnectJob.cancel()
        releaseReset.complete(Unit)
        disconnectJob.join()

        assertEquals(listOf("reset", "reset complete", "logout"), effects)
    }

    @Test
    fun `confirmed disconnect survives cancellation while waiting for auth operation`() = runBlocking {
        val authOperationMutex = Mutex(locked = true)
        val effects = mutableListOf<String>()
        val disconnectJob = launch(start = CoroutineStart.UNDISPATCHED) {
            runConfirmedDisconnectOperation(
                authOperationMutex = authOperationMutex,
                isCurrent = { true },
                operation = { effects += "disconnect" },
            )
        }

        disconnectJob.cancel()
        authOperationMutex.unlock()
        disconnectJob.join()

        assertEquals(listOf("disconnect"), effects)
    }

    // ─── Session-identity guard (confirmed disconnect races a fresh login) ────
    //
    // runConfirmedDisconnectOperation's isCurrent() check only sees THIS
    // ViewModel instance's own authUiGeneration. Opening Settings again builds
    // a brand new instance with its own mutex and generation counter, so a
    // login on that new instance is invisible to an old instance's disconnect
    // still waiting on resetNowPlayingForDisconnect() to finish. The only
    // thing both instances share is ApiService's own session identity, so the
    // guard has to be keyed on that instead.

    private val sessionA = AuthSessionIdentity(generation = 0, token = "token-a", serverUrl = "https://a.example")
    private val sessionB = AuthSessionIdentity(generation = 1, token = "token-b", serverUrl = "https://a.example")

    @Test
    fun `guarded disconnect does not log out a session that changed during the reset wait`() = runBlocking {
        var liveSession: AuthSessionIdentity? = sessionA
        val effects = mutableListOf<String>()

        disconnectSessionGuarded(
            appMode = AppMode.AUDIOBOOKSHELF,
            captureSession = { liveSession },
            resetNowPlaying = {
                effects += "reset"
                // A second Settings screen (its own ViewModel, own mutex, own
                // generation counter) logs in on session B while this
                // instance's reset is still awaiting pending terminal progress.
                liveSession = sessionB
            },
            logoutIfCurrent = { captured ->
                if (captured == liveSession) {
                    effects += "logout"
                    liveSession = null
                }
            },
        )

        assertEquals(listOf("reset"), effects)
        assertEquals(sessionB, liveSession)
    }

    @Test
    fun `guarded disconnect logs out when the session did not change`() = runBlocking {
        var liveSession: AuthSessionIdentity? = sessionA
        val effects = mutableListOf<String>()

        disconnectSessionGuarded(
            appMode = AppMode.AUDIOBOOKSHELF,
            captureSession = { liveSession },
            resetNowPlaying = { effects += "reset" },
            logoutIfCurrent = { captured ->
                if (captured == liveSession) {
                    effects += "logout"
                    liveSession = null
                }
            },
        )

        assertEquals(listOf("reset", "logout"), effects)
        assertNull(liveSession)
    }

    @Test
    fun `guarded disconnect completes logout after caller cancellation of the same session`() = runBlocking {
        val resetStarted = CompletableDeferred<Unit>()
        val releaseReset = CompletableDeferred<Unit>()
        val authOperationMutex = Mutex()
        val effects = mutableListOf<String>()
        val disconnectJob = launch {
            runConfirmedDisconnectOperation(
                authOperationMutex = authOperationMutex,
                isCurrent = { true },
            ) {
                disconnectSessionGuarded(
                    appMode = AppMode.AUDIOBOOKSHELF,
                    captureSession = { sessionA },
                    resetNowPlaying = {
                        effects += "reset"
                        resetStarted.complete(Unit)
                        releaseReset.await()
                        effects += "reset complete"
                    },
                    logoutIfCurrent = { captured ->
                        if (captured == sessionA) effects += "logout"
                    },
                )
            }
        }

        resetStarted.await()
        disconnectJob.cancel()
        releaseReset.complete(Unit)
        disconnectJob.join()

        assertEquals(listOf("reset", "reset complete", "logout"), effects)
    }

    @Test
    fun `guarded disconnect skips logout when there was no session to capture`() = runBlocking {
        val effects = mutableListOf<String>()

        disconnectSessionGuarded(
            appMode = AppMode.AUDIOBOOKSHELF,
            captureSession = { null },
            resetNowPlaying = { effects += "reset" },
            logoutIfCurrent = { effects += "logout" },
        )

        assertEquals(listOf("reset"), effects)
    }

    // ─── Disconnect barrier (raised before the reset, lowered after logout) ──
    //
    // resetNowPlayingForDisconnect only invalidates the loads that existed
    // when it started. A load kicked off while this confirmed disconnect is
    // still blocked awaiting terminal progress would otherwise get a fresh
    // request id, claim it, and outlive the logout it raced. The barrier has
    // to be up for that entire window and it must come back down on every
    // path out of this function, or every later remote load is refused
    // forever.

    @Test
    fun `guarded disconnect raises the barrier before resetting and lowers it after logout`() = runBlocking {
        val effects = mutableListOf<String>()

        disconnectSessionGuarded(
            appMode = AppMode.AUDIOBOOKSHELF,
            captureSession = { sessionA },
            resetNowPlaying = { effects += "reset" },
            logoutIfCurrent = { effects += "logout" },
            raiseDisconnectBarrier = { effects += "barrier-up" },
            lowerDisconnectBarrier = { effects += "barrier-down" },
        )

        assertEquals(listOf("barrier-up", "reset", "logout", "barrier-down"), effects)
    }

    @Test
    fun `guarded disconnect lowers the barrier even with no session to capture`() = runBlocking {
        val effects = mutableListOf<String>()

        disconnectSessionGuarded(
            appMode = AppMode.AUDIOBOOKSHELF,
            captureSession = { null },
            resetNowPlaying = { effects += "reset" },
            logoutIfCurrent = { effects += "logout" },
            raiseDisconnectBarrier = { effects += "barrier-up" },
            lowerDisconnectBarrier = { effects += "barrier-down" },
        )

        assertEquals(listOf("barrier-up", "reset", "barrier-down"), effects)
    }

    @Test
    fun `guarded disconnect lowers the barrier when the reset throws`() = runBlocking {
        val effects = mutableListOf<String>()

        try {
            disconnectSessionGuarded(
                appMode = AppMode.AUDIOBOOKSHELF,
                captureSession = { sessionA },
                resetNowPlaying = {
                    effects += "reset"
                    throw IllegalStateException("reset blew up")
                },
                logoutIfCurrent = { effects += "logout" },
                raiseDisconnectBarrier = { effects += "barrier-up" },
                lowerDisconnectBarrier = { effects += "barrier-down" },
            )
            fail("expected the reset failure to propagate")
        } catch (_: IllegalStateException) {
        }

        assertEquals(listOf("barrier-up", "reset", "barrier-down"), effects)
    }

    @Test
    fun `guarded disconnect lowers the barrier when logout throws`() = runBlocking {
        val effects = mutableListOf<String>()

        try {
            disconnectSessionGuarded(
                appMode = AppMode.AUDIOBOOKSHELF,
                captureSession = { sessionA },
                resetNowPlaying = { effects += "reset" },
                logoutIfCurrent = {
                    effects += "logout"
                    throw IllegalStateException("logout blew up")
                },
                raiseDisconnectBarrier = { effects += "barrier-up" },
                lowerDisconnectBarrier = { effects += "barrier-down" },
            )
            fail("expected the logout failure to propagate")
        } catch (_: IllegalStateException) {
        }

        assertEquals(listOf("barrier-up", "reset", "logout", "barrier-down"), effects)
    }

    // ─── Overlapping disconnects (two SettingsViewModel instances) ────────
    //
    // Each SettingsViewModel instance owns its own authUiOperationMutex, so
    // it cannot serialize against a DIFFERENT instance's in-flight
    // disconnect. A user can confirm disconnect, leave Settings while the
    // terminal-progress flush is still pending, come back to a freshly
    // built Settings screen, and confirm again. Both raise the SAME
    // PlaybackManager-owned barrier. Whichever operation lowers it first
    // must not let the barrier drop while the other is still tearing down.

    @Test
    fun `overlapping guarded disconnects keep the barrier up until the last one finishes`() = runBlocking {
        val barrier = DisconnectBarrier()
        val firstResetStarted = CompletableDeferred<Unit>()
        val releaseFirstReset = CompletableDeferred<Unit>()

        val firstDisconnect = launch(start = CoroutineStart.UNDISPATCHED) {
            disconnectSessionGuarded(
                appMode = AppMode.AUDIOBOOKSHELF,
                captureSession = { sessionA },
                resetNowPlaying = {
                    firstResetStarted.complete(Unit)
                    releaseFirstReset.await()
                },
                logoutIfCurrent = {},
                raiseDisconnectBarrier = barrier::raise,
                lowerDisconnectBarrier = barrier::lower,
            )
        }

        // First disconnect is now blocked awaiting its terminal-progress
        // flush. User left Settings and came back to a fresh screen,
        // confirming disconnect again before the first one finishes.
        firstResetStarted.await()
        disconnectSessionGuarded(
            appMode = AppMode.AUDIOBOOKSHELF,
            captureSession = { sessionB },
            resetNowPlaying = {},
            logoutIfCurrent = {},
            raiseDisconnectBarrier = barrier::raise,
            lowerDisconnectBarrier = barrier::lower,
        )

        // The second disconnect fully finished (raised and lowered its own),
        // but the first is still tearing down. A remote load must still be
        // refused.
        assertTrue(disconnectBarrierBlocksLoad(barrier.isUp, bookIsLocal = false))

        releaseFirstReset.complete(Unit)
        firstDisconnect.join()

        // Only now, with both operations finished, may a remote load proceed.
        assertFalse(disconnectBarrierBlocksLoad(barrier.isUp, bookIsLocal = false))
    }

    @Test
    fun `request opens confirmation without disconnecting`() {
        val decision = disconnectDialogDecision(DisconnectDialogAction.REQUEST)

        assertTrue(decision.showDialog)
        assertFalse(decision.disconnect)
    }

    @Test
    fun `cancel closes confirmation without disconnecting`() {
        val decision = disconnectDialogDecision(DisconnectDialogAction.CANCEL)

        assertFalse(decision.showDialog)
        assertFalse(decision.disconnect)
    }

    @Test
    fun `confirm closes confirmation and disconnects`() {
        val decision = disconnectDialogDecision(DisconnectDialogAction.CONFIRM)

        assertFalse(decision.showDialog)
        assertTrue(decision.disconnect)
    }
}
