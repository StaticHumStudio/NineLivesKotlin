package com.ninelivesaudio.app.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Codex adversarial review, issue #14 round 2, finding P2-2.
 *
 * The auth-UI generation guard only protects a banner from a stale
 * completion when EVERY tap that can write one advances the SAME shared
 * counter. Before this fix, only Connect and Disconnect called
 * authUiGeneration.incrementAndGet() -- Refresh and Test Connection
 * captured the current value with .get() and stayed enabled during a sync.
 * So: slow manual sync, tap Refresh, Refresh writes its banner -- and the
 * sync's stale completion later overwrote it anyway, because nothing had
 * actually moved the generation Refresh captured, so the sync's own
 * isCurrent() check still read true.
 *
 * runGenerationGuarded is the shared primitive manual sync, Refresh, and
 * Test Connection now all rely on (manual sync directly via
 * runGuardedManualSync; Refresh and Test Connection through the identical
 * updateAuthUi generation check, now fed by .incrementAndGet() at tap time
 * exactly like this test simulates). These tests pin the race symmetrically:
 * whichever tap is newest wins, no matter which one finishes first.
 */
class AuthUiGenerationRaceTest {

    @Test
    fun `a stale sync completion after a refresh tap writes nothing`() = runBlocking {
        // Manual sync taps first and is slow. Refresh taps second, finishes
        // fast, and writes its banner. The sync's late completion must not
        // overwrite it -- this is the literal scenario from finding P2-2.
        val generation = AtomicLong(0)
        val banner = mutableListOf<String>()

        val syncStarted = CompletableDeferred<Unit>()
        val releaseSync = CompletableDeferred<Unit>()

        val syncGeneration = generation.incrementAndGet()
        val syncResult = async {
            runGenerationGuarded(isCurrent = { generation.get() == syncGeneration }) {
                syncStarted.complete(Unit)
                releaseSync.await()
                "stale sync banner"
            }
        }

        syncStarted.await()

        val refreshGeneration = generation.incrementAndGet()
        val refreshResult = runGenerationGuarded(isCurrent = { generation.get() == refreshGeneration }) {
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
        val generation = AtomicLong(0)
        val banner = mutableListOf<String>()

        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()

        val refreshGeneration = generation.incrementAndGet()
        val refreshResult = async {
            runGenerationGuarded(isCurrent = { generation.get() == refreshGeneration }) {
                refreshStarted.complete(Unit)
                releaseRefresh.await()
                "stale refresh banner"
            }
        }

        refreshStarted.await()

        val syncGeneration = generation.incrementAndGet()
        val syncResult = runGenerationGuarded(isCurrent = { generation.get() == syncGeneration }) {
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
