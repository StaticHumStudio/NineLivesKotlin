package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AudioBook
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
    fun stage(request: Long, book: AudioBook) = synchronized(lock) {
        this.request = request
        _book.value = book
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
        _book.value = null
        true
    }
}

/**
 * Runs [publish], which makes the loaded book current, and only then clears
 * [request]'s staging. An observer reading the staged book first never sees
 * the previous book in between.
 */
internal inline fun publishStagedLoad(staged: StagedLoad, request: Long, publish: () -> Unit): Boolean {
    publish()
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
