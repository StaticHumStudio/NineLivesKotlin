package com.ninelivesaudio.app.ui.settings

import com.ninelivesaudio.app.domain.model.LastSyncRecord
import com.ninelivesaudio.app.domain.model.SyncResult
import com.ninelivesaudio.app.service.SyncAttempt
import com.ninelivesaudio.app.service.SyncNowResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Codex adversarial review, issue #14, findings 2 and 3.
 *
 * Manual "Sync Now" was not guarded by authUiGeneration at all, unlike
 * Connect/Disconnect/Refresh/Test Connection. Starting a slow sync and then
 * disconnecting or reconnecting let the old sync's completion land later and
 * overwrite the newer auth operation's banner with a stale success/error
 * (finding 2).
 *
 * Separately, the manual attempt did not own its own record: syncNow()
 * returned only an enum, and Settings reread the shared lastSync afterward.
 * A periodic invocation could write its own outcome between the manual
 * write and that reread, so the manual tap reported the BACKGROUND
 * attempt's outcome instead of its own (finding 3).
 *
 * runGuardedManualSync() fixes both: it checks currency only AFTER syncNow()
 * completes (catching a generation change mid-flight, not just before the
 * tap started), and it renders the SyncNowResult syncNow() actually
 * returned, never a reread.
 */
class SettingsManualSyncTest {

    private val successResult = SyncNowResult(
        attempt = SyncAttempt.RAN,
        record = LastSyncRecord(result = SyncResult.SUCCESS, libraryCount = 2, bookCount = 40, completedAtMs = 1L),
    )

    @Test
    fun `a still-current tap renders the record syncNow returned`() = runBlocking {
        val outcome = runGuardedManualSync(
            isCurrent = { true },
            syncNow = { successResult },
        )

        assertEquals("Sync completed successfully", outcome?.successMessage)
        assertNull(outcome?.errorMessage)
    }

    @Test
    fun `a tap superseded before the sync even starts writes nothing`() = runBlocking {
        val outcome = runGuardedManualSync(
            isCurrent = { false },
            syncNow = { successResult },
        )

        assertNull(outcome)
    }

    @Test
    fun `a tap superseded while the sync was in flight also writes nothing`() = runBlocking {
        // The guard has to catch a generation change that happens WHILE the
        // sync is running, not just one that already happened before it
        // started -- checking isCurrent only up front would miss exactly
        // this race, which is the actual shape of finding 2 (disconnect or
        // reconnect landing during a slow sync).
        val syncStarted = CompletableDeferred<Unit>()
        val releaseSync = CompletableDeferred<Unit>()
        var stillCurrent = true

        val resultDeferred = async {
            runGuardedManualSync(
                isCurrent = { stillCurrent },
                syncNow = {
                    syncStarted.complete(Unit)
                    releaseSync.await()
                    successResult
                },
            )
        }

        syncStarted.await()
        // A disconnect/reconnect (which advances authUiGeneration) lands
        // while this manual sync is still running.
        stillCurrent = false
        releaseSync.complete(Unit)

        assertNull(resultDeferred.await())
    }

    @Test
    fun `a background sync racing the manual tap never leaks into this tap's outcome`() = runBlocking {
        // syncNow() itself owns and returns its own record now (finding 3) --
        // this pins that runGuardedManualSync never substitutes some OTHER
        // source of truth (e.g. a reread) for what syncNow() actually handed
        // back, even though nothing here reads settings directly. The result
        // rendered must be exactly the one the manual attempt produced.
        val manualAttemptRecord = LastSyncRecord(
            result = SyncResult.FAILED,
            libraryCount = 0,
            bookCount = 0,
            failure = "libraries: HTTP 500",
            completedAtMs = 5L,
        )

        val outcome = runGuardedManualSync(
            isCurrent = { true },
            syncNow = {
                SyncNowResult(attempt = SyncAttempt.RAN, record = manualAttemptRecord)
            },
        )

        assertEquals("Sync failed: libraries: HTTP 500", outcome?.errorMessage)
        assertNull(outcome?.successMessage)
    }
}
