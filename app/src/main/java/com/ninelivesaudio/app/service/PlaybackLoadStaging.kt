package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Chapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The book a load is filling the player with, before it becomes the current
 * book (#42). The current book changes only when a load has its tracks, so a
 * load that is abandoned or fails leaves the previous book on screen with its
 * own position, duration and chapters. Each write is keyed by the load request,
 * so an older load can neither rewrite nor clear a newer load's book.
 */
internal class StagedLoad {
    private val lock = Any()
    private var request: Long? = null
    private val _book = MutableStateFlow<AudioBook?>(null)
    val book: StateFlow<AudioBook?> = _book.asStateFlow()

    /** Stages [book] for [request], replacing any older staging. */
    // The loading book's chapters, sorted. They become the player's chapter
    // cache only when the book publishes, so a failed load never leaves the
    // previous book's chapter taps indexing the loading book's chapters.
    private var chapters: List<Chapter> = emptyList()

    /** Stages [book] and its chapters for [request], replacing any older staging. */
    fun stage(request: Long, book: AudioBook) = synchronized(lock) {
        this.request = request
        chapters = book.chapters.sortedBy { it.start }
        _book.value = book
    }

    /** [request]'s staged chapters, or null when it is not the staged load. */
    fun chapters(request: Long): List<Chapter>? = synchronized(lock) {
        if (this.request != request) null else chapters
    }

    /** Fills in [request]'s chapters when it staged none, as a backfill or session finds them. */
    fun fillChaptersIfEmpty(request: Long, found: List<Chapter>): Boolean = synchronized(lock) {
        if (this.request != request || chapters.isNotEmpty() || found.isEmpty()) return false
        chapters = found.sortedBy { it.start }
        true
    }

    /** Replaces the staged book, only while [request] is the staged one. */
    fun update(request: Long, book: AudioBook): Boolean = synchronized(lock) {
        if (this.request != request) return false
        _book.value = book
        true
    }

    /** Clears the staging, only while [request] is the staged one. */
    fun clear(request: Long): Boolean = synchronized(lock) {
        if (this.request != request) return false
        this.request = null
        chapters = emptyList()
        _book.value = null
        true
    }
}

/**
 * Cancels the running load and clears its staging at once. The cancelled
 * load can sit on a slow server for the whole read timeout before it notices,
 * and until its staging clears the mini player shows that book and play and
 * seek are ignored. Only the cancelled load's own staging is cleared, so a
 * newer load that already staged keeps its book, and the cancelled load's
 * own clear later finds nothing of its own.
 */
internal fun cancelPlaybackLoad(owner: PlaybackLoadOwner, staged: StagedLoad) {
    owner.cancelAll(staged)
}

/**
 * Cancels every load of [bookId] from disk because its download was removed,
 * and clears the staging of any that had staged. A downloaded book's load can
 * still wait on a session open or an online backfill, and until then the mini
 * player would show it and ignore play. A newer load's staging is left alone.
 * True when a load was cancelled.
 */
internal fun cancelDiskLoadsOf(owner: PlaybackLoadOwner, staged: StagedLoad, bookId: String): Boolean =
    owner.cancelLoadsOf(bookId, staged)

/**
 * What a removed download does to the player. The loading book is read
 * before its load is cancelled, because the cancel clears its staging and
 * the decision still has to see it: deleting the loading book's download
 * must stop that load, or the player stays LOADING with nobody to end it.
 * The book being loaded is staged, not current, until it publishes (#42).
 */
internal fun planDownloadRemoval(
    owner: PlaybackLoadOwner,
    staged: StagedLoad,
    current: AudioBook?,
    removedBookId: String,
): DownloadRemovalAction {
    val loading = staged.book.value
    cancelDiskLoadsOf(owner, staged, removedBookId)
    return downloadRemovalTarget(current, loading, removedBookId)
}

/**
 * Runs [publish] with the staged chapters, which makes the loaded book and its
 * chapter cache current together, and only then clears
 * [request]'s staging. An observer reading the staged book first never sees
 * the previous book in between.
 */
internal inline fun publishStagedLoad(staged: StagedLoad, request: Long, publish: (chapters: List<Chapter>) -> Unit): Boolean {
    publish(staged.chapters(request).orEmpty())
    return staged.clear(request)
}

internal enum class LoadedTracks { SUPERSEDED, EMPTY, READY }

/**
 * What a load does once its tracks are built. EMPTY exists because preparing
 * an empty playlist ends at once, and the end of a book writes it as finished
 * (or leaves a book with no audio current at the old book's position). A book
 * with no track list whose session open and full item fetch both failed lands
 * here, so it is a failed load instead.
 */
internal fun loadedTracksOutcome(tracksLoaded: Boolean, stillCurrent: Boolean, mediaItemCount: Int): LoadedTracks = when {
    !tracksLoaded || !stillCurrent -> LoadedTracks.SUPERSEDED
    mediaItemCount == 0 -> LoadedTracks.EMPTY
    else -> LoadedTracks.READY
}

/** The book the shared player belongs to: the loading one while a load runs. */
internal fun playerBookId(loading: AudioBook?, current: AudioBook?): String? = loading?.id ?: current?.id

/** Whether the player holds [bookId], as the current book or the one loading. */
internal fun playerHoldsBook(current: AudioBook?, loading: AudioBook?, bookId: String): Boolean =
    current?.id == bookId || loading?.id == bookId

/** Whether the player holds a server book, current or loading (sign out). */
internal fun playerHoldsRemoteBook(current: AudioBook?, loading: AudioBook?): Boolean =
    current?.isLocal == false || loading?.isLocal == false
