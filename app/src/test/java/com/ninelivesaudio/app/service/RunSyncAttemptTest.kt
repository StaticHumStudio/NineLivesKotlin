package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.LastSyncRecord
import com.ninelivesaudio.app.domain.model.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Codex adversarial review, finding 4: SyncModeReconnectTest exercised
 * isSyncEligibleAfterReachability, but production syncNow() had quietly
 * stopped calling that helper -- the test stayed green while the real gate
 * could regress underneath it. runSyncAttempt() is what syncNow() actually
 * delegates to now, so these tests pin the real control flow directly:
 * exactly one reachability probe per eligible invocation, a probe failure
 * records a FAILED outcome, and the post-probe eligibility recheck never
 * triggers a second probe.
 */
class RunSyncAttemptTest {

    private val successReport = SyncReport(libraryCount = 1, bookCount = 10)
    private val successOutcome = PersistedSyncOutcome(
        recorded = true,
        persisted = true,
        record = LastSyncRecord(result = SyncResult.SUCCESS, libraryCount = 1, bookCount = 10, completedAtMs = 1L),
    )

    @Test
    fun `not ready skips before ever probing the server`() = runBlocking {
        var probeCalls = 0

        val result = runSyncAttempt(
            isSyncReady = { false },
            checkServerReachable = { probeCalls++; true },
            tryLock = { fail("must not lock when not ready"); true },
            unlock = { fail("must not unlock when never locked") },
            runSyncWork = { fail("must not sync when not ready"); successReport },
            persistOutcome = { fail("must not persist when not ready"); successOutcome },
            persistUnreachableOutcome = { fail("must not persist when not ready"); successOutcome },
        )

        assertEquals(SyncAttempt.SKIPPED_NOT_READY, result.attempt)
        assertNull(result.record)
        assertEquals(0, probeCalls)
    }

    @Test
    fun `an eligible attempt probes reachability exactly once`() = runBlocking {
        var probeCalls = 0

        runSyncAttempt(
            isSyncReady = { true },
            checkServerReachable = { probeCalls++; true },
            tryLock = { true },
            unlock = {},
            runSyncWork = { successReport },
            persistOutcome = { successOutcome },
            persistUnreachableOutcome = { fail("server was reachable"); successOutcome },
        )

        assertEquals(1, probeCalls)
    }

    @Test
    fun `a probe failure records a FAILED outcome, not a skip`() = runBlocking {
        val failedOutcome = PersistedSyncOutcome(
            recorded = true,
            persisted = true,
            record = LastSyncRecord(
                result = SyncResult.FAILED,
                libraryCount = 0,
                bookCount = 0,
                failure = "server unreachable",
                completedAtMs = 1L,
            ),
        )

        val result = runSyncAttempt(
            isSyncReady = { true },
            checkServerReachable = { false },
            tryLock = { fail("a failed probe must not attempt the real sync"); true },
            unlock = {},
            runSyncWork = { fail("a failed probe must not run the sync"); successReport },
            persistOutcome = { fail("a failed probe must persist the unreachable outcome, not this one"); successOutcome },
            persistUnreachableOutcome = { failedOutcome },
        )

        assertEquals(SyncAttempt.RAN, result.attempt)
        assertEquals(SyncResult.FAILED, result.record?.result)
        assertTrue(result.persisted)
    }

    @Test
    fun `a probe failure while eligibility was just lost does not persist a failure for a session already left`() = runBlocking {
        // GitHub codex review of PR #30: the failed-probe branch persisted
        // unreachableServerSyncReport() WITHOUT the same post-probe
        // isSyncReady() recheck the successful-probe branch performs. Sign
        // out or switch to Local mode while the probe is in flight, and the
        // now-irrelevant attempt still recorded a FAILED outcome (reported
        // as RAN), overwriting lastSync and later showing a misleading
        // failure warning for a session the user had already left.
        var readyCalls = 0

        val result = runSyncAttempt(
            isSyncReady = {
                readyCalls++
                // Ready for the pre-probe check, not ready by the post-probe
                // recheck -- e.g. sign-out or a mode switch to Local landed
                // while the (failing) probe was in flight.
                readyCalls == 1
            },
            checkServerReachable = { false },
            tryLock = { fail("must not lock once the recheck says not ready"); true },
            unlock = {},
            runSyncWork = { fail("must not sync once the recheck says not ready"); successReport },
            persistOutcome = { fail("must not persist once the recheck says not ready"); successOutcome },
            persistUnreachableOutcome = { fail("no longer eligible -- must not persist the unreachable outcome either"); successOutcome },
        )

        assertEquals(SyncAttempt.SKIPPED_NOT_READY, result.attempt)
        assertNull(result.record)
        assertEquals(2, readyCalls)
    }

    @Test
    fun `eligibility is rechecked after the probe but that recheck never re-probes`() = runBlocking {
        var probeCalls = 0
        var readyCalls = 0

        val result = runSyncAttempt(
            isSyncReady = {
                readyCalls++
                // Ready for the pre-probe check, not ready by the post-probe
                // recheck -- e.g. a mode switch landed while the probe was
                // in flight.
                readyCalls == 1
            },
            checkServerReachable = { probeCalls++; true },
            tryLock = { fail("must not lock once the recheck says not ready"); true },
            unlock = {},
            runSyncWork = { fail("must not sync once the recheck says not ready"); successReport },
            persistOutcome = { fail("must not persist once the recheck says not ready"); successOutcome },
            persistUnreachableOutcome = { fail("the probe succeeded"); successOutcome },
        )

        assertEquals(SyncAttempt.SKIPPED_NOT_READY, result.attempt)
        assertEquals(1, probeCalls)
        assertEquals(2, readyCalls)
    }

    @Test
    fun `busy mutex skips without running the sync`() = runBlocking {
        val result = runSyncAttempt(
            isSyncReady = { true },
            checkServerReachable = { true },
            tryLock = { false },
            unlock = { fail("never locked, must never unlock") },
            runSyncWork = { fail("busy must not run the sync"); successReport },
            persistOutcome = { fail("busy must not persist"); successOutcome },
            persistUnreachableOutcome = { fail("the probe succeeded"); successOutcome },
        )

        assertEquals(SyncAttempt.SKIPPED_BUSY, result.attempt)
        assertNull(result.record)
    }

    @Test
    fun `a locked run acquires and releases in order around the sync work`() = runBlocking {
        val order = mutableListOf<String>()

        runSyncAttempt(
            isSyncReady = { true },
            checkServerReachable = { true },
            tryLock = { order += "lock"; true },
            unlock = { order += "unlock" },
            onLockAcquired = { order += "acquired" },
            onLockReleasing = { order += "releasing" },
            runSyncWork = { order += "sync"; successReport },
            persistOutcome = { order += "persist"; successOutcome },
            persistUnreachableOutcome = { fail("the probe succeeded"); successOutcome },
        )

        assertEquals(listOf("lock", "acquired", "sync", "persist", "releasing", "unlock"), order)
    }

    @Test
    fun `an unexpected failure during the sync still unlocks and reports a truthful FAILED record, not null`() = runBlocking {
        // Codex round 2, finding P2-1: RAN + a null record used to render as
        // a false "Sync completed successfully" one layer up in
        // syncNowOutcome(). A null record must be unrepresentable here --
        // an unexpected failure still has to leave behind something honest
        // for the caller to render.
        var unlocked = false

        val result = runSyncAttempt(
            isSyncReady = { true },
            checkServerReachable = { true },
            tryLock = { true },
            unlock = { unlocked = true },
            runSyncWork = { throw IllegalStateException("boom") },
            persistOutcome = { fail("sync never produced a report"); successOutcome },
            persistUnreachableOutcome = { fail("the probe succeeded"); successOutcome },
        )

        assertEquals(SyncAttempt.RAN, result.attempt)
        assertNotNull(result.record)
        assertEquals(SyncResult.FAILED, result.record?.result)
        assertFalse(result.persisted)
        assertTrue(unlocked)
    }

    @Test
    fun `a persistence failure before the transform ever ran still reports a truthful FAILED record, not null`() = runBlocking {
        // The second source named in finding P2-1: settingsManager access
        // itself can fail before the transform even runs, producing a
        // PersistedSyncOutcome with recorded=false, persisted=false,
        // record=null. That must not surface as RAN + null either.
        val nothingRecorded = PersistedSyncOutcome(recorded = false, persisted = false, record = null)

        val result = runSyncAttempt(
            isSyncReady = { true },
            checkServerReachable = { true },
            tryLock = { true },
            unlock = {},
            runSyncWork = { successReport },
            persistOutcome = { nothingRecorded },
            persistUnreachableOutcome = { fail("the probe succeeded"); successOutcome },
        )

        assertEquals(SyncAttempt.RAN, result.attempt)
        assertNotNull(result.record)
        assertEquals(SyncResult.FAILED, result.record?.result)
        assertFalse(result.persisted)
    }

    @Test
    fun `cancellation during the sync is rethrown, not swallowed`() = runBlocking {
        var unlocked = false
        val cancellation = CancellationException("stop")

        try {
            runSyncAttempt(
                isSyncReady = { true },
                checkServerReachable = { true },
                tryLock = { true },
                unlock = { unlocked = true },
                runSyncWork = { throw cancellation },
                persistOutcome = { fail("sync never produced a report"); successOutcome },
                persistUnreachableOutcome = { fail("the probe succeeded"); successOutcome },
            )
            fail("expected cancellation to propagate")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
        assertTrue(unlocked)
    }

    @Test
    fun `a persistence failure during the real sync surfaces honestly, not as a clean success`() = runBlocking {
        val unpersistedOutcome = PersistedSyncOutcome(
            recorded = true,
            persisted = false,
            record = LastSyncRecord(result = SyncResult.SUCCESS, libraryCount = 1, bookCount = 10, completedAtMs = 1L),
        )

        val result = runSyncAttempt(
            isSyncReady = { true },
            checkServerReachable = { true },
            tryLock = { true },
            unlock = {},
            runSyncWork = { successReport },
            persistOutcome = { unpersistedOutcome },
            persistUnreachableOutcome = { fail("the probe succeeded"); successOutcome },
        )

        assertEquals(SyncAttempt.RAN, result.attempt)
        assertFalse(result.persisted)
        assertEquals(SyncResult.SUCCESS, result.record?.result)
    }

    @Test
    fun `a discarded persist reports DISCARDED_SERVER_CHANGED with no record`() = runBlocking {
        val discardedOutcome = PersistedSyncOutcome(recorded = false, persisted = true, record = null)

        val result = runSyncAttempt(
            isSyncReady = { true },
            checkServerReachable = { true },
            tryLock = { true },
            unlock = {},
            runSyncWork = { successReport },
            persistOutcome = { discardedOutcome },
            persistUnreachableOutcome = { fail("the probe succeeded"); successOutcome },
        )

        assertEquals(SyncAttempt.DISCARDED_SERVER_CHANGED, result.attempt)
        assertNull(result.record)
    }
}
