package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.Library
import com.ninelivesaudio.app.domain.model.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync timer runs only in the foreground: a check on every entry (unless
 * one just ran) and every 15 minutes after. A check that found nothing writes
 * nothing, and one that failed never claims success.
 */
class ForegroundSyncPolicyTest {

    private val minute = 60_000L

    // ─── Foreground timer ─────────────────────────────────────────────────

    @Test
    fun `the first foreground entry checks right away`() {
        assertEquals(0L, delayBeforeEntryCheck(lastCheckAtMs = null, nowMs = 5_000L))
    }

    @Test
    fun `an entry within two minutes of the last check waits out the interval`() {
        assertEquals(14 * minute, delayBeforeEntryCheck(lastCheckAtMs = 0L, nowMs = 1 * minute))
        assertEquals(13 * minute + 1, delayBeforeEntryCheck(lastCheckAtMs = 0L, nowMs = 2 * minute - 1))
    }

    @Test
    fun `an entry two minutes or more after the last check checks right away`() {
        assertEquals(0L, delayBeforeEntryCheck(lastCheckAtMs = 0L, nowMs = 2 * minute))
        assertEquals(0L, delayBeforeEntryCheck(lastCheckAtMs = 0L, nowMs = 90 * minute))
    }

    @Test
    fun `a clock reading before the last check checks right away`() {
        assertEquals(0L, delayBeforeEntryCheck(lastCheckAtMs = 10 * minute, nowMs = 5 * minute))
        assertTrue(isCheckDue(lastCheckAtMs = 10 * minute, nowMs = 5 * minute))
    }

    @Test
    fun `a tick or a server return skips a check when a sync just ran`() {
        assertTrue(isCheckDue(lastCheckAtMs = null, nowMs = 0L))
        assertFalse(isCheckDue(lastCheckAtMs = 0L, nowMs = 2 * minute - 1))
        assertTrue(isCheckDue(lastCheckAtMs = 0L, nowMs = 2 * minute))
    }

    // ─── Check records ────────────────────────────────────────────────────

    @Test
    fun `a check that found nothing writes no record over a success`() {
        assertFalse(shouldPersistCheckReport(SyncResult.SUCCESS, CheckTally(), previousResult = SyncResult.SUCCESS))
    }

    @Test
    fun `a clean check heals a failure banner or a missing record`() {
        assertTrue(shouldPersistCheckReport(SyncResult.SUCCESS, CheckTally(), previousResult = SyncResult.FAILED))
        assertTrue(shouldPersistCheckReport(SyncResult.SUCCESS, CheckTally(), previousResult = null))
    }

    @Test
    fun `a failed check is written as the failure it is`() {
        assertTrue(shouldPersistCheckReport(SyncResult.FAILED, CheckTally(), previousResult = SyncResult.SUCCESS))
        assertTrue(shouldPersistCheckReport(SyncResult.PARTIAL, CheckTally(anyDeferred = true), previousResult = SyncResult.SUCCESS))
    }

    @Test
    fun `a deferred full download never claims success`() {
        assertFalse(shouldPersistCheckReport(SyncResult.SUCCESS, CheckTally(anyDeferred = true), previousResult = SyncResult.FAILED))
        assertFalse(
            shouldPersistCheckReport(SyncResult.SUCCESS, CheckTally(anyDeferred = true, wroteBooks = true), SyncResult.SUCCESS),
        )
    }

    @Test
    fun `saved books or a changed library list are written so the Library re-reads`() {
        assertTrue(shouldPersistCheckReport(SyncResult.SUCCESS, CheckTally(wroteBooks = true), SyncResult.SUCCESS))
        assertTrue(shouldPersistCheckReport(SyncResult.SUCCESS, CheckTally(libraryListChanged = true), SyncResult.SUCCESS))
    }

    @Test
    fun `a library list change means an added, removed, or renamed library`() {
        val books = Library(id = "a", name = "Books")
        assertFalse(libraryListChanged(listOf(books), listOf(books.copy(displayOrder = 9))))
        assertTrue(libraryListChanged(listOf(books), listOf(books.copy(name = "Audiobooks"))))
        assertTrue(libraryListChanged(listOf(books), listOf(books, Library(id = "b", name = "More"))))
        assertTrue(libraryListChanged(listOf(books), emptyList()))
    }

    @Test
    fun `background syncs skip podcast libraries`() {
        assertTrue(isSyncedLibrary(Library(id = "a", mediaType = "book")))
        assertFalse(isSyncedLibrary(Library(id = "p", mediaType = "podcast")))
    }
}
