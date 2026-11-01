package com.ninelivesaudio.app.widget

import android.util.Log
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.data.repository.LibraryRepository
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.isInActiveLibrary
import com.ninelivesaudio.app.entitlement.EffectiveSettingsRepository
import com.ninelivesaudio.app.service.ConnectivityMonitor
import com.ninelivesaudio.app.service.PlaybackManager
import com.ninelivesaudio.app.service.PlaybackState
import com.ninelivesaudio.app.service.RemoteMediaAccessDecision
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.service.local.LocalFolderAccess
import com.ninelivesaudio.app.service.local.reconcileLocalBookAccess
import com.ninelivesaudio.app.service.remoteMediaAccessDecision
import com.ninelivesaudio.app.ui.bookdetail.canPlayBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/** What a widget play tap turns into. */
internal enum class WidgetPlayPlan { IGNORE, PAUSE, RESUME_CURRENT, LOAD_BOOK, OPEN_BOOK_PAGE, OPEN_UNLOCK }

private fun isLoading(state: PlaybackState, bookLoading: Boolean) =
    bookLoading || state == PlaybackState.LOADING

private fun isAudible(state: PlaybackState) =
    state == PlaybackState.PLAYING || state == PlaybackState.BUFFERING

/**
 * A Binder cover tap set to Play it. A tap while a book loads does nothing,
 * the same as the app. A book that cannot play right now opens its page,
 * where the app already explains why (plan Design delta 5).
 */
internal fun binderPlayPlan(
    isUnlocked: Boolean,
    bookId: String,
    currentBookId: String?,
    state: PlaybackState,
    bookLoading: Boolean,
    playable: Boolean,
): WidgetPlayPlan = when {
    !isUnlocked -> WidgetPlayPlan.OPEN_UNLOCK
    isLoading(state, bookLoading) -> WidgetPlayPlan.IGNORE
    bookId == currentBookId && isAudible(state) -> WidgetPlayPlan.IGNORE
    bookId == currentBookId -> WidgetPlayPlan.RESUME_CURRENT
    !playable -> WidgetPlayPlan.OPEN_BOOK_PAGE
    else -> WidgetPlayPlan.LOAD_BOOK
}

/** A play or pause button on a widget, acting like the mini player's. */
internal fun togglePlan(
    isUnlocked: Boolean,
    currentBookId: String?,
    state: PlaybackState,
    bookLoading: Boolean,
    shownBookId: String?,
    shownPlayable: Boolean,
): WidgetPlayPlan = when {
    !isUnlocked -> WidgetPlayPlan.IGNORE
    isLoading(state, bookLoading) -> WidgetPlayPlan.IGNORE
    isAudible(state) -> WidgetPlayPlan.PAUSE
    currentBookId != null -> WidgetPlayPlan.RESUME_CURRENT
    shownBookId == null -> WidgetPlayPlan.IGNORE
    !shownPlayable -> WidgetPlayPlan.IGNORE
    else -> WidgetPlayPlan.LOAD_BOOK
}

/** Waits for [gate] for at most [timeout], then runs [block] either way. */
internal suspend fun awaitThen(gate: Deferred<Unit>, timeout: Duration, block: suspend () -> Unit) {
    withTimeoutOrNull(timeout) { gate.await() }
    block()
}

/**
 * Play from a widget. Goes through [PlaybackManager.play], [PlaybackManager.pause]
 * and [PlaybackManager.loadAudioBook], the path the mini player and Book
 * Detail use, so every in app playback rule holds (plan Design delta 3).
 */
@Singleton
class WidgetPlayback @Inject constructor(
    private val playbackManager: PlaybackManager,
    private val audioBookRepository: AudioBookRepository,
    private val libraryRepository: LibraryRepository,
    private val localFolderAccess: LocalFolderAccess,
    private val settingsManager: SettingsManager,
    private val connectivityMonitor: ConnectivityMonitor,
    private val effectiveSettings: EffectiveSettingsRepository,
) {
    /** Book Detail's Play gates: not archived, source reachable, remote media not blocked. */
    internal suspend fun isPlayable(book: AudioBook): Boolean {
        val library = book.libraryId?.let { libraryRepository.getById(it) }
        val access = reconcileLocalBookAccess(
            book,
            localFolderAccess.accessibleLibraryIds(listOfNotNull(library)),
        )
        if (!canPlayBook(access.book.isArchived, access.isPlayable || access.book.isArchived)) return false
        return remoteMediaAccessDecision(
            book = access.book,
            serverUrl = settingsManager.currentSettings.serverUrl,
            connectionStatus = connectivityMonitor.connectionStatus.value,
        ) !is RemoteMediaAccessDecision.Blocked
    }

    /**
     * The work runs on this class's own scope, so the trampoline finishing or
     * being destroyed never cancels a book load halfway.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    internal suspend fun playFromBinder(bookId: String): WidgetPlayPlan =
        scope.async { playFromBinderOwned(bookId) }.await()

    internal suspend fun toggle(shownBookId: String?): WidgetPlayPlan =
        scope.async { toggleOwned(shownBookId) }.await()

    private suspend fun playFromBinderOwned(bookId: String): WidgetPlayPlan {
        playbackManager.awaitStartupRestore()
        val unlocked = effectiveSettings.isUnlockedNow
        // A tap from a widget not yet redrawn after a mode or account switch
        // carries a book from the old library. It never plays or opens.
        val book = if (unlocked) {
            withContext(Dispatchers.IO) { audioBookRepository.getById(bookId) }
                ?.takeIf { it.isInActiveLibrary(settingsManager.currentSettings) }
                ?: return WidgetPlayPlan.IGNORE
        } else {
            null
        }
        val plan = binderPlayPlan(
            isUnlocked = unlocked,
            bookId = bookId,
            currentBookId = playbackManager.currentBook.value?.id,
            state = playbackManager.playbackState.value,
            bookLoading = playbackManager.loadingBook.value != null,
            playable = book != null && isPlayable(book),
        )
        act(plan, book)
        return plan
    }

    private suspend fun toggleOwned(shownBookId: String?): WidgetPlayPlan {
        playbackManager.awaitStartupRestore()
        val unlocked = effectiveSettings.isUnlockedNow
        val shown = if (unlocked && shownBookId != null) {
            withContext(Dispatchers.IO) { audioBookRepository.getById(shownBookId) }
                ?.takeIf { it.isInActiveLibrary(settingsManager.currentSettings) }
        } else {
            null
        }
        val plan = togglePlan(
            isUnlocked = unlocked,
            currentBookId = playbackManager.currentBook.value?.id,
            state = playbackManager.playbackState.value,
            bookLoading = playbackManager.loadingBook.value != null,
            shownBookId = shown?.id,
            shownPlayable = shown != null && isPlayable(shown),
        )
        act(plan, shown)
        return plan
    }

    private suspend fun act(plan: WidgetPlayPlan, book: AudioBook?) {
        Log.d(TAG, "plan $plan for ${book?.id}")
        withContext(Dispatchers.Main) {
            when (plan) {
                WidgetPlayPlan.RESUME_CURRENT -> playbackManager.play()
                WidgetPlayPlan.PAUSE -> playbackManager.pause()
                WidgetPlayPlan.LOAD_BOOK -> book?.let { playbackManager.loadAudioBook(it) }
                else -> Unit
            }
        }
    }

    private companion object {
        const val TAG = "WidgetPlayback"
    }
}
