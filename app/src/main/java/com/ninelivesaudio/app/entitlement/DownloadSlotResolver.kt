package com.ninelivesaudio.app.entitlement

import com.ninelivesaudio.app.domain.model.DownloadStatus

/**
 * One book's claim on the free tier's single offline slot, reduced to the facts
 * the rules depend on.
 *
 * Assembled by the caller from three tables, because no single one of them is
 * sufficient. `DownloadEngine` writes the offline fields on `AudioBooks` only
 * AFTER writing `Completed`, and `clearCompleted()` deletes `DownloadItems` rows
 * while leaving the files on disk. Either source alone therefore misses real
 * offline books.
 */
data class SlotCandidate(
    val audioBookId: String,
    /**
     * A scanned local-folder book. These bypass the slot entirely, ahead of
     * every other condition.
     */
    val isLocal: Boolean,
    /** False when the AudioBooks row is missing, which makes the claim unusable. */
    val hasAudioBookRecord: Boolean,
    /** Null when there is no DownloadItems row, which retained offline state has. */
    val downloadStatus: DownloadStatus?,
    /** Retained offline state on the AudioBooks row. */
    val isDownloaded: Boolean,
    val hasLocalPath: Boolean,
    /** Whether the files are actually still on disk. */
    val filesExist: Boolean,
    val progressUpdatedAt: Long?,
    val completedAt: Long?,
    val startedAt: Long?,
)

/**
 * Decides which single book a free install keeps offline.
 *
 * Pure and Android-free, same as the rest of this package, because the rules are
 * fiddly enough that they need exhaustive tests and none of them need a device.
 * Getting this wrong either deletes someone's downloaded book or silently lets
 * the free tier keep an unlimited number.
 *
 * The wiring that takes the slot mutex, writes [DownloadStatus.Preparing], and
 * promotes or deletes lives in `DownloadManager`. This file only answers "who
 * wins" and "is this winner still valid".
 */
object DownloadSlotResolver {

    /** Free installs keep exactly this many offline SERVER books. */
    const val FREE_SLOT_SIZE = 1

    /**
     * States that occupy the slot but have not finished downloading.
     *
     * Exempt from the file-existence check below, because a claim exists before
     * any bytes are fetched and `DownloadsScreen` offers Pause on a `Queued` row.
     */
    private val PRE_COMPLETION = setOf(
        DownloadStatus.Preparing,
        DownloadStatus.Queued,
        DownloadStatus.Downloading,
        DownloadStatus.Paused,
    )

    /**
     * Everything that counts against the slot. The union of live download rows
     * and retained offline state, never one source alone.
     */
    fun eligible(candidates: List<SlotCandidate>): List<SlotCandidate> =
        candidates.filter { it.occupiesSlot() }

    /**
     * The book a free install keeps, or null when nothing qualifies.
     *
     * Ladder, in order: most recently played, then most recently completed, then
     * most recently started, then lowest id. The last rung exists so the answer
     * is deterministic even when a book has no timestamps at all. A rule that
     * can return different winners for identical state is not a slot.
     */
    fun resolveWinner(candidates: List<SlotCandidate>): String? =
        eligible(candidates)
            .sortedWith(WINNER_ORDER)
            .firstOrNull()
            ?.audioBookId

    /**
     * Whether a persisted winner still holds the slot.
     *
     * Checked at every incoming claim AND at every playback source selection.
     * A winner whose files were deleted underneath it is no longer valid, and
     * source selection falls back to streaming rather than failing.
     */
    fun isWinnerStillValid(winnerId: String?, candidates: List<SlotCandidate>): Boolean {
        if (winnerId == null) return false
        return eligible(candidates).any { it.audioBookId == winnerId }
    }

    /**
     * Books to free when entitlement drops, i.e. every eligible book except the
     * winner.
     *
     * These are PRESERVED, not deleted. Files stay on disk, streaming keeps
     * working, and the rows grey out. Deleting down to one book is always
     * allowed, but it is the user's call, never ours.
     */
    fun losersOnDowngrade(candidates: List<SlotCandidate>): List<String> {
        val winner = resolveWinner(candidates)
        return eligible(candidates)
            .map { it.audioBookId }
            .filter { it != winner }
    }

    /**
     * Null means "never happened", which must always lose to any real timestamp.
     *
     * Encoded with `nullsFirst` under a descending comparison rather than by
     * substituting a sentinel. A sentinel makes null indistinguishable from a
     * genuine timestamp of that exact value, and a comparator that cannot tell
     * "never played" from "played at time X" is a comparator that can pick the
     * wrong book to keep.
     */
    private val WINNER_ORDER: Comparator<SlotCandidate> =
        compareByDescending(nullsFirst<Long>()) { it: SlotCandidate -> it.progressUpdatedAt }
            .thenByDescending(nullsFirst<Long>()) { it.completedAt }
            .thenByDescending(nullsFirst<Long>()) { it.startedAt }
            .thenBy { it.audioBookId }

    private fun SlotCandidate.occupiesSlot(): Boolean {
        // First and unconditional. Local-folder books have no DownloadItems row
        // and no stream to fall back to, so charging them against the slot would
        // make local playback, a free feature, cost the user their one download.
        if (isLocal) return false

        // A claim with no book behind it cannot be played or revalidated.
        if (!hasAudioBookRecord) return false

        val status = downloadStatus

        if (status in PRE_COMPLETION) return true

        // Terminal and retained-offline candidates have to actually exist on
        // disk. Failed and Cancelled rows never occupy the slot at all.
        val retainedOffline = isDownloaded && hasLocalPath
        val completed = status == DownloadStatus.Completed

        return (completed || retainedOffline) && filesExist
    }
}
