package com.ninelivesaudio.app.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Codex adversarial review, issue #14 round 2, finding P2-2 (updated for
 * round 3's two-counter design).
 *
 * The auth-UI generation guard only protects a banner from a stale
 * completion when EVERY tap that can write one advances a SHARED counter.
 * Round 2 made Refresh, Test Connection, and manual Sync all advance
 * authUiGeneration directly to fix this -- but that counter is ALSO what a
 * confirmed Disconnect depends on, and an observational tap advancing it
 * could cancel a queued logout (the round-3 regression, pinned in
 * AuthUiGenerationMutexRaceTest). Round 3's fix: Refresh, Test Connection,
 * and manual Sync now share a SEPARATE banner generation
 * (AuthUiGenerationCoordinator.beginObservational /
 * isObservationalCurrent), leaving the auth generation untouched by any of
 * them. These tests re-pin the original round-2 race in that two-counter
 * world: whichever OBSERVATIONAL tap is newest still wins, no matter which
 * one finishes first, exactly as before -- just via the banner counter
 * instead of the auth counter.
 */
class AuthUiGenerationRaceTest {

    @Test
    fun `a stale sync completion after a refresh tap writes nothing`() = runBlocking {
        // Manual sync taps first and is slow. Refresh taps second, finishes
        // fast, and writes its banner. The sync's late completion must not
        // overwrite it -- this is the literal scenario from finding P2-2.
        val coordinator = AuthUiGenerationCoordinator()
        val banner = mutableListOf<String>()

        val syncStarted = CompletableDeferred<Unit>()
        val releaseSync = CompletableDeferred<Unit>()

        val syncGeneration = coordinator.beginObservational()
        val syncResult = async {
            runGenerationGuarded(isCurrent = { coordinator.isObservationalCurrent(syncGeneration) }) {
                syncStarted.complete(Unit)
                releaseSync.await()
                "stale sync banner"
            }
        }

        syncStarted.await()

        val refreshGeneration = coordinator.beginObservational()
        val refreshResult = runGenerationGuarded(isCurrent = { coordinator.isObservationalCurrent(refreshGeneration) }) {
            "fresh refresh banner"
        }
        refreshResult?.let { banner += it }

        releaseSync.complete(Unit)
        val staleSyncResult = syncResult.await()
        staleSyncResult?.let { banner += it }

        assertNull("the stale sync completion must be suppressed", staleSyncResult)
        assertEquals(listOf("fresh refresh banner"), banner)
    }

    @Test
    fun `a stale refresh completion after a sync tap writes nothing`() = runBlocking {
        // The mirror case, pinning the fix isn't accidentally
        // one-directional: Refresh taps first and is slow, manual sync taps
        // second and finishes fast. Refresh's late completion must not
        // overwrite the sync's fresher banner.
        val coordinator = AuthUiGenerationCoordinator()
        val banner = mutableListOf<String>()

        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()

        val refreshGeneration = coordinator.beginObservational()
        val refreshResult = async {
            runGenerationGuarded(isCurrent = { coordinator.isObservationalCurrent(refreshGeneration) }) {
                refreshStarted.complete(Unit)
                releaseRefresh.await()
                "stale refresh banner"
            }
        }

        refreshStarted.await()

        val syncGeneration = coordinator.beginObservational()
        val syncResult = runGenerationGuarded(isCurrent = { coordinator.isObservationalCurrent(syncGeneration) }) {
            "fresh sync banner"
        }
        syncResult?.let { banner += it }

        releaseRefresh.complete(Unit)
        val staleRefreshResult = refreshResult.await()
        staleRefreshResult?.let { banner += it }

        assertNull("the stale refresh completion must be suppressed", staleRefreshResult)
        assertEquals(listOf("fresh sync banner"), banner)
    }
}
