package com.ninelivesaudio.app.entitlement

import com.ninelivesaudio.app.domain.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slot rules decide whether a free user keeps or loses a book they already
 * downloaded, so every branch is pinned. Two failure modes matter: dropping a
 * real offline book, and quietly letting the free tier keep an unlimited number.
 */
class DownloadSlotResolverTest {

    private fun candidate(
        id: String,
        isLocal: Boolean = false,
        hasRecord: Boolean = true,
        status: DownloadStatus? = DownloadStatus.Completed,
        isDownloaded: Boolean = true,
        hasLocalPath: Boolean = true,
        filesExist: Boolean = true,
        progressUpdatedAt: Long? = null,
        completedAt: Long? = null,
        startedAt: Long? = null,
    ) = SlotCandidate(
        audioBookId = id,
        isLocal = isLocal,
        hasAudioBookRecord = hasRecord,
        downloadStatus = status,
        isDownloaded = isDownloaded,
        hasLocalPath = hasLocalPath,
        filesExist = filesExist,
        progressUpdatedAt = progressUpdatedAt,
        completedAt = completedAt,
        startedAt = startedAt,
    )

    // ─── Local books bypass everything ────────────────────────────────────────

    /**
     * Local-folder playback is a free feature. Scanned local books carry
     * isDownloaded and a localPath but have no DownloadItems row and no stream
     * to fall back to, so charging them against the slot would make a free
     * feature cost the user their one download.
     */
    @Test
    fun `local books never occupy the slot`() {
        val candidates = listOf(
            candidate("local", isLocal = true, status = null),
            candidate("server", status = DownloadStatus.Completed),
        )

        assertEquals(listOf("server"), DownloadSlotResolver.eligible(candidates).map { it.audioBookId })
        assertEquals("server", DownloadSlotResolver.resolveWinner(candidates))
    }

    @Test
    fun `a library of only local books leaves the slot empty`() {
        val candidates = List(5) { candidate("local$it", isLocal = true, status = null) }

        assertNull(DownloadSlotResolver.resolveWinner(candidates))
        assertTrue(DownloadSlotResolver.losersOnDowngrade(candidates).isEmpty())
    }

    // ─── The candidate set is a union, never one source ───────────────────────

    /**
     * clearCompleted() deletes DownloadItems rows while leaving the files on
     * disk. Those books are still offline and still occupy the slot.
     */
    @Test
    fun `retained offline state with no download row still occupies the slot`() {
        val candidates = listOf(candidate("a", status = null, isDownloaded = true, hasLocalPath = true))

        assertEquals("a", DownloadSlotResolver.resolveWinner(candidates))
    }

    /**
     * DownloadEngine writes the offline fields on AudioBooks only AFTER writing
     * Completed, so a mid-flight download has a row and no offline state.
     */
    @Test
    fun `a live download row with no offline state still occupies the slot`() {
        val candidates = listOf(
            candidate("a", status = DownloadStatus.Downloading, isDownloaded = false, hasLocalPath = false, filesExist = false)
        )

        assertEquals("a", DownloadSlotResolver.resolveWinner(candidates))
    }

    // ─── Which states count ───────────────────────────────────────────────────

    @Test
    fun `every pre-completion state occupies the slot`() {
        for (status in listOf(
            DownloadStatus.Preparing,
            DownloadStatus.Queued,
            DownloadStatus.Downloading,
            DownloadStatus.Paused,
        )) {
            val candidates = listOf(
                candidate("a", status = status, isDownloaded = false, hasLocalPath = false, filesExist = false)
            )
            assertEquals("$status should occupy the slot", "a", DownloadSlotResolver.resolveWinner(candidates))
        }
    }

    @Test
    fun `failed and cancelled rows never occupy the slot`() {
        for (status in listOf(DownloadStatus.Failed, DownloadStatus.Cancelled)) {
            val candidates = listOf(
                candidate("a", status = status, isDownloaded = false, hasLocalPath = false, filesExist = false)
            )
            assertNull("$status should not occupy the slot", DownloadSlotResolver.resolveWinner(candidates))
        }
    }

    // ─── File existence applies to terminal states only ───────────────────────

    /**
     * A claim exists before any bytes are fetched, and DownloadsScreen offers
     * Pause on a Queued row. Requiring files here would drop live claims.
     */
    @Test
    fun `pre-completion states are exempt from the file check`() {
        val candidates = listOf(candidate("a", status = DownloadStatus.Queued, filesExist = false, isDownloaded = false, hasLocalPath = false))

        assertEquals("a", DownloadSlotResolver.resolveWinner(candidates))
    }

    @Test
    fun `a completed book whose files are gone does not occupy the slot`() {
        val candidates = listOf(candidate("a", status = DownloadStatus.Completed, filesExist = false))

        assertNull(DownloadSlotResolver.resolveWinner(candidates))
    }

    @Test
    fun `retained offline state whose files are gone does not occupy the slot`() {
        val candidates = listOf(candidate("a", status = null, filesExist = false))

        assertNull(DownloadSlotResolver.resolveWinner(candidates))
    }

    @Test
    fun `a candidate with no AudioBooks record is unusable`() {
        val candidates = listOf(candidate("a", hasRecord = false))

        assertNull(DownloadSlotResolver.resolveWinner(candidates))
    }

    // ─── The winner ladder ────────────────────────────────────────────────────

    @Test
    fun `most recently played wins`() {
        val candidates = listOf(
            candidate("old", progressUpdatedAt = 100, completedAt = 999, startedAt = 999),
            candidate("recent", progressUpdatedAt = 200, completedAt = 1, startedAt = 1),
        )

        assertEquals("recent", DownloadSlotResolver.resolveWinner(candidates))
    }

    @Test
    fun `completedAt breaks a tie when nothing was played`() {
        val candidates = listOf(
            candidate("older", completedAt = 100, startedAt = 999),
            candidate("newer", completedAt = 200, startedAt = 1),
        )

        assertEquals("newer", DownloadSlotResolver.resolveWinner(candidates))
    }

    @Test
    fun `startedAt breaks a tie when nothing completed`() {
        val candidates = listOf(
            candidate("older", status = DownloadStatus.Queued, startedAt = 100, isDownloaded = false, hasLocalPath = false, filesExist = false),
            candidate("newer", status = DownloadStatus.Queued, startedAt = 200, isDownloaded = false, hasLocalPath = false, filesExist = false),
        )

        assertEquals("newer", DownloadSlotResolver.resolveWinner(candidates))
    }

    /**
     * The last rung exists so the answer is deterministic with no timestamps at
     * all. A rule that returns different winners for identical state is not a
     * slot, it is a coin flip that deletes books.
     */
    @Test
    fun `lowest id breaks a total tie, deterministically`() {
        val candidates = listOf(candidate("zebra"), candidate("alpha"), candidate("middle"))

        assertEquals("alpha", DownloadSlotResolver.resolveWinner(candidates))
        // Same input, reversed, must give the same answer.
        assertEquals("alpha", DownloadSlotResolver.resolveWinner(candidates.reversed()))
    }

    @Test
    fun `a played book beats a more recently completed one`() {
        val candidates = listOf(
            candidate("played", progressUpdatedAt = 1, completedAt = 1),
            candidate("fresh", progressUpdatedAt = null, completedAt = 9_999),
        )

        assertEquals("played", DownloadSlotResolver.resolveWinner(candidates))
    }

    /**
     * Null means "never happened" and must lose to ANY real timestamp, including
     * zero, a negative one, or the sentinel value a naive implementation would
     * have substituted for null.
     */
    @Test
    fun `null timestamps always lose to real ones`() {
        val extremes = listOf(Long.MIN_VALUE, -1L, 0L)

        for (real in extremes) {
            val candidates = listOf(
                candidate("never", progressUpdatedAt = null, completedAt = null, startedAt = null),
                candidate("played", progressUpdatedAt = real, completedAt = null, startedAt = null),
            )
            assertEquals(
                "a real timestamp of $real should beat null",
                "played",
                DownloadSlotResolver.resolveWinner(candidates),
            )
        }
    }

    // ─── Revalidation ─────────────────────────────────────────────────────────

    @Test
    fun `a persisted winner whose files vanished is no longer valid`() {
        val candidates = listOf(candidate("a", filesExist = false))

        assertFalse(DownloadSlotResolver.isWinnerStillValid("a", candidates))
    }

    @Test
    fun `a persisted winner still present is valid`() {
        assertTrue(DownloadSlotResolver.isWinnerStillValid("a", listOf(candidate("a"))))
    }

    @Test
    fun `no persisted winner is never valid`() {
        assertFalse(DownloadSlotResolver.isWinnerStillValid(null, listOf(candidate("a"))))
    }

    @Test
    fun `a winner that is no longer in the candidate set is not valid`() {
        assertFalse(DownloadSlotResolver.isWinnerStillValid("gone", listOf(candidate("a"))))
    }

    // ─── Downgrade ────────────────────────────────────────────────────────────

    /**
     * Losers are preserved, not deleted. This returns ids to grey out, and the
     * caller must never read it as a delete list.
     */
    @Test
    fun `downgrade keeps the winner and lists every other eligible book`() {
        val candidates = listOf(
            candidate("winner", progressUpdatedAt = 500),
            candidate("loser1", progressUpdatedAt = 100),
            candidate("loser2", progressUpdatedAt = 200),
            candidate("local", isLocal = true, status = null),
            candidate("gone", filesExist = false),
        )

        val losers = DownloadSlotResolver.losersOnDowngrade(candidates)

        assertEquals(setOf("loser1", "loser2"), losers.toSet())
        assertFalse("local books are not slot losers", losers.contains("local"))
        assertFalse("ineligible books are not slot losers", losers.contains("gone"))
    }

    @Test
    fun `a single eligible book has no losers`() {
        assertTrue(DownloadSlotResolver.losersOnDowngrade(listOf(candidate("only"))).isEmpty())
    }

    @Test
    fun `an empty library resolves to no winner and no losers`() {
        assertNull(DownloadSlotResolver.resolveWinner(emptyList()))
        assertTrue(DownloadSlotResolver.losersOnDowngrade(emptyList()).isEmpty())
    }

    // ─── Ordinals ─────────────────────────────────────────────────────────────

    /**
     * Room persists this enum by ordinal. Inserting Preparing anywhere above the
     * existing values would silently rewrite the meaning of every download row
     * on every installed device: every Completed would read back as Failed.
     */
    @Test
    fun `Preparing is appended at ordinal 6 and nothing above it moved`() {
        assertEquals(0, DownloadStatus.Queued.ordinal)
        assertEquals(1, DownloadStatus.Downloading.ordinal)
        assertEquals(2, DownloadStatus.Paused.ordinal)
        assertEquals(3, DownloadStatus.Completed.ordinal)
        assertEquals(4, DownloadStatus.Failed.ordinal)
        assertEquals(5, DownloadStatus.Cancelled.ordinal)
        assertEquals(6, DownloadStatus.Preparing.ordinal)
    }

    /**
     * The drain selects `Status IN (0, 1)`. Preparing must stay outside that set
     * or a provisional claim would start downloading before its metadata fetch
     * has even returned.
     */
    @Test
    fun `Preparing is outside the drain's downloadable ordinals`() {
        assertFalse(DownloadStatus.Preparing.ordinal in setOf(0, 1))
    }
}
