package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.LastSyncRecord
import com.ninelivesaudio.app.domain.model.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codex round 2, finding P2-1: toSyncNowResult() is the single choke point
 * every sync attempt's outcome passes through on its way to a SyncNowResult.
 * Before this fix it could produce SyncAttempt.RAN with record=null whenever
 * PersistedSyncOutcome.persisted was false and nothing had been recorded --
 * and syncNowOutcome() one layer up rendered THAT as a false "Sync completed
 * successfully." These tests pin toSyncNowResult() directly: a RAN result
 * must never carry a null record.
 */
class PersistedSyncOutcomeMappingTest {

    @Test
    fun `a normal recorded and persisted outcome maps to RAN with that record`() {
        val record = LastSyncRecord(result = SyncResult.SUCCESS, libraryCount = 2, bookCount = 40, completedAtMs = 1L)

        val result = PersistedSyncOutcome(recorded = true, persisted = true, record = record).toSyncNowResult()

        assertEquals(SyncAttempt.RAN, result.attempt)
        assertEquals(record, result.record)
        assertTrue(result.persisted)
    }

    @Test
    fun `a discarded server-changed outcome maps to DISCARDED_SERVER_CHANGED with no record`() {
        val result = PersistedSyncOutcome(recorded = false, persisted = true, record = null).toSyncNowResult()

        assertEquals(SyncAttempt.DISCARDED_SERVER_CHANGED, result.attempt)
        assertNull(result.record)
    }

    @Test
    fun `a persistence failure with a record already decided keeps that truthful record`() {
        val record = LastSyncRecord(result = SyncResult.SUCCESS, libraryCount = 1, bookCount = 10, completedAtMs = 1L)

        val result = PersistedSyncOutcome(recorded = true, persisted = false, record = record).toSyncNowResult()

        assertEquals(SyncAttempt.RAN, result.attempt)
        assertEquals(record, result.record)
        assertFalse(result.persisted)
    }

    @Test
    fun `a persistence failure with nothing decided synthesizes a truthful FAILED record, never null`() {
        // The exact shape produced when settingsManager access fails before
        // the transform ever runs (see persistSyncOutcome) or when the sync
        // work itself throws unexpectedly (see runSyncAttempt's catch
        // block). Both used to leave record=null here.
        val result = PersistedSyncOutcome(recorded = false, persisted = false, record = null).toSyncNowResult()

        assertEquals(SyncAttempt.RAN, result.attempt)
        assertNotNull(result.record)
        assertEquals(SyncResult.FAILED, result.record?.result)
        assertFalse(result.persisted)
    }
}
