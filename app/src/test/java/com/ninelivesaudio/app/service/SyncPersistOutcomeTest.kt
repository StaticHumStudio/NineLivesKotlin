package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Codex adversarial review, finding 1: SyncManager's generic catch swallowed
 * a failed outcome-record write and returned SyncAttempt.RAN anyway, so a
 * caller reporting the outcome to the user could show "success" even though
 * the LastSyncRecord was never actually stored.
 *
 * persistSyncOutcome() is the fix: it isolates the settings write, and a
 * failure to persist is surfaced as [PersistedSyncOutcome.persisted] = false
 * rather than swallowed. The record this attempt actually produced is still
 * returned (truthful in-memory data) so the caller can report what really
 * happened instead of losing the signal entirely.
 */
class SyncPersistOutcomeTest {

    private val report = SyncReport(libraryCount = 2, bookCount = 40)

    @Test
    fun `a normal write records the outcome and reports it persisted`() = runBlocking {
        var settings = AppSettings(serverUrl = "https://server.example")

        val outcome = persistSyncOutcome(
            report = report,
            completedAtMs = 100L,
            serverUrlAtStart = "https://server.example",
            updateSettings = { transform -> settings = transform(settings) },
        )

        assertTrue(outcome.recorded)
        assertTrue(outcome.persisted)
        assertEquals(SyncResult.SUCCESS, outcome.record?.result)
        assertEquals(settings.lastSync, outcome.record)
    }

    @Test
    fun `a server change in flight is discarded, not reported as a failure`() = runBlocking {
        var settings = AppSettings(serverUrl = "https://server.example")

        val outcome = persistSyncOutcome(
            report = report,
            completedAtMs = 100L,
            serverUrlAtStart = "https://a-different-server.example",
            updateSettings = { transform -> settings = transform(settings) },
        )

        assertFalse(outcome.recorded)
        assertTrue(outcome.persisted)
        assertNull(outcome.record)
    }

    @Test
    fun `a persistence failure after the transform ran still returns the truthful record`() = runBlocking {
        // The core of finding 1: the transform decides what SHOULD be
        // recorded before the write is attempted. If the write then fails,
        // that in-memory decision must not be thrown away along with the
        // exception -- the caller needs both "it failed to save" AND "here
        // is what actually happened."
        val settings = AppSettings(serverUrl = "https://server.example")

        val outcome = persistSyncOutcome(
            report = report,
            completedAtMs = 100L,
            serverUrlAtStart = "https://server.example",
            updateSettings = { transform ->
                transform(settings) // the transform runs and decides the record...
                throw IOException("disk full") // ...then the write itself fails
            },
        )

        assertTrue(outcome.recorded)
        assertFalse(outcome.persisted)
        assertEquals(SyncResult.SUCCESS, outcome.record?.result)
        assertEquals(2, outcome.record?.libraryCount)
        assertEquals(40, outcome.record?.bookCount)
    }

    @Test
    fun `a failure before the transform even runs reports no record, not a stale one`() = runBlocking {
        val outcome = persistSyncOutcome(
            report = report,
            completedAtMs = 100L,
            serverUrlAtStart = "https://server.example",
            updateSettings = { throw IOException("could not read settings") },
        )

        assertFalse(outcome.recorded)
        assertFalse(outcome.persisted)
        assertNull(outcome.record)
    }

    @Test
    fun `cancellation is rethrown, not swallowed as a persistence failure`() = runBlocking {
        val cancellation = CancellationException("stop")

        try {
            persistSyncOutcome(
                report = report,
                completedAtMs = 100L,
                serverUrlAtStart = "https://server.example",
                updateSettings = { throw cancellation },
            )
            fail("expected cancellation to propagate")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
    }
}
