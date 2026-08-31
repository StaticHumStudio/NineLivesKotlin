package com.ninelivesaudio.app.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codex round 3, the regression this round exists to fix.
 *
 * Round 2 made Refresh, Test Connection, and manual Sync advance the SAME
 * authUiGeneration counter runConfirmedDisconnectOperation's isCurrent check
 * depends on. Scenario: Refresh acquires the auth-UI mutex and is slow
 * (network call); the user confirms Disconnect, which queues behind the
 * same mutex; while Disconnect waits, the user taps Sync (or Test) --
 * neither needs the mutex, so it runs immediately and (under round 2's
 * wiring) bumped the shared counter. By the time Refresh releases the mutex
 * and Disconnect finally runs, its captured generation no longer matches,
 * so runConfirmedDisconnectOperation's isCurrent check silently skips the
 * logout and the token stays active.
 *
 * This test wires the real production primitives together exactly as
 * SettingsViewModel does post-fix: AuthUiGenerationCoordinator for the two
 * counters, runConfirmedDisconnectOperation for Disconnect, and
 * runGenerationGuarded for the observational tap. Confirmed by hand against
 * a throwaway single-shared-AtomicLong version of this same scenario that a
 * one-counter wiring genuinely reproduces the bug at runtime (assertion
 * failure), not just in theory.
 */
class AuthUiGenerationMutexRaceTest {

    @Test
    fun `a confirmed disconnect queued behind Refresh still logs out after a Sync tap`() = runBlocking {
        val coordinator = AuthUiGenerationCoordinator()
        val mutex = Mutex()
        var loggedOut = false

        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()

        // Refresh acquires the auth-UI mutex and holds it on a slow call.
        val refreshJob = launch {
            mutex.withLock {
                coordinator.beginObservational()
                refreshStarted.complete(Unit)
                releaseRefresh.await()
            }
        }
        refreshStarted.await()

        // The user confirms Disconnect. It queues on the same mutex.
        val disconnectGeneration = coordinator.beginMutation()
        val disconnectJob = launch {
            runConfirmedDisconnectOperation(
                authOperationMutex = mutex,
                isCurrent = { coordinator.isMutationCurrent(disconnectGeneration) },
            ) {
                loggedOut = true
            }
        }

        // While Disconnect is still waiting for the mutex, the user taps
        // Sync -- it needs no mutex, so it runs right away. Captures and
        // checks its OWN generation exactly like SettingsViewModel.syncNow()
        // does; what matters for this test is that tapping Sync at all must
        // not touch the auth generation Disconnect is waiting on.
        val syncGeneration = coordinator.beginObservational()
        val syncOutcome = runGenerationGuarded(
            isCurrent = { coordinator.isObservationalCurrent(syncGeneration) },
        ) { "sync banner" }
        assertTrue("the sync tap itself should see itself as current", syncOutcome == "sync banner")

        releaseRefresh.complete(Unit)
        refreshJob.join()
        disconnectJob.join()

        assertTrue("the confirmed disconnect must still log out", loggedOut)
    }

    @Test
    fun `a mutation invalidates an in-flight observational banner write`() = runBlocking {
        // The other half of the two-counter contract: a mutation (Connect
        // or Disconnect) still wins over ANY observational tap already in
        // flight -- unlike the reverse (which this round fixed), this
        // direction must keep working.
        val coordinator = AuthUiGenerationCoordinator()
        var banner: String? = null

        val observationalStarted = CompletableDeferred<Unit>()
        val releaseObservational = CompletableDeferred<Unit>()

        val observationalJob = launch {
            val generation = coordinator.beginObservational() // Refresh taps first, slow
            val result = runGenerationGuarded(isCurrent = { coordinator.isObservationalCurrent(generation) }) {
                observationalStarted.complete(Unit)
                releaseObservational.await()
                "stale refresh banner"
            }
            result?.let { banner = it }
        }

        observationalStarted.await()
        coordinator.beginMutation() // Disconnect fires while Refresh is still running

        releaseObservational.complete(Unit)
        observationalJob.join()

        assertFalse("a stale observational banner must not render", banner == "stale refresh banner")
    }
}
