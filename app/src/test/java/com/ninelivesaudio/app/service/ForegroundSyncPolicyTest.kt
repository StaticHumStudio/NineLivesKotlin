package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.local.entity.BookProgressState
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.domain.model.Library
import com.ninelivesaudio.app.domain.model.SyncResult
import com.ninelivesaudio.app.domain.model.UserProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

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

    // ─── Progress pull ────────────────────────────────────────────────────

    @Test
    fun `only the most recently listened unknown books are fetched one by one`() {
        val progress = (1..40).map { i -> UserProgress(libraryItemId = "li_$i", lastUpdate = i.toLong()) } +
            UserProgress(libraryItemId = "li_known", lastUpdate = 1_000L)
        val picked = unknownBooksToFetch(progress, knownIds = setOf("li_known"), cap = 3)
        assertEquals(setOf("li_40", "li_39", "li_38"), picked)
    }

    @Test
    fun `a progress record that matches the cache is skipped`() {
        val row = PlaybackProgressEntity(audioBookId = "a", positionSeconds = 120.0, isFinished = 0, updatedAt = "t1")
        val book = BookProgressState(id = "a", currentTimeSeconds = 120.2, progress = 0.25, isFinished = 0)
        assertTrue(serverProgressIsAlreadyLocal(120.0, 0.25, false, "t1", row, book))
        assertTrue(serverProgressIsAlreadyLocal(120.0, 0.25, false, "t1", row, localBook = null))
    }

    @Test
    fun `a progress record that moved anything is imported`() {
        val row = PlaybackProgressEntity(audioBookId = "a", positionSeconds = 120.0, isFinished = 0, updatedAt = "t1")
        val book = BookProgressState(id = "a", currentTimeSeconds = 120.0, progress = 0.25, isFinished = 0)
        assertFalse(serverProgressIsAlreadyLocal(300.0, 0.25, false, "t1", row, book))
        assertFalse(serverProgressIsAlreadyLocal(120.0, 0.25, true, "t1", row, book))
        assertFalse(serverProgressIsAlreadyLocal(120.0, 0.25, false, "t2", row, book))
        assertFalse(serverProgressIsAlreadyLocal(120.0, 0.30, false, "t1", row, book))
        assertFalse(serverProgressIsAlreadyLocal(120.0, 0.25, false, "t1", localRow = null, localBook = book))
    }

    @Test
    fun `unknown ids exclude blanks and repeats`() {
        val progress = listOf(
            UserProgress(libraryItemId = "", currentTime = 1.seconds),
            UserProgress(libraryItemId = "x", lastUpdate = 2L),
            UserProgress(libraryItemId = "x", lastUpdate = 1L),
        )
        assertEquals(setOf("x"), unknownBooksToFetch(progress, knownIds = emptySet()))
    }
}
