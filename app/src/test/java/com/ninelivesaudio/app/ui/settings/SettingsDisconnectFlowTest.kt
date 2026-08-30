package com.ninelivesaudio.app.ui.settings

import com.ninelivesaudio.app.domain.model.AppMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
