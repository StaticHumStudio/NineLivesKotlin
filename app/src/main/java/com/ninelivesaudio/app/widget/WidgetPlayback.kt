package com.ninelivesaudio.app.widget

import android.content.Context
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

/**
 * A Binder cover tap set to Open its page. Runs the same checks Play runs: a
 * locked widget goes to Unlock, and a tap carrying a book from another mode,
 * server or library (a widget not yet redrawn after a switch) is ignored.
 */
internal fun binderOpenPlan(isUnlocked: Boolean, inActiveLibrary: Boolean): WidgetPlayPlan = when {
    !isUnlocked -> WidgetPlayPlan.OPEN_UNLOCK
    !inActiveLibrary -> WidgetPlayPlan.IGNORE
    else -> WidgetPlayPlan.OPEN_BOOK_PAGE
}

/** The screen a plan opens for the tapped book. Only a refusal or an Open opens one. */
internal fun refusalLaunch(plan: WidgetPlayPlan, bookId: String): WidgetLaunch? = when (plan) {
    WidgetPlayPlan.OPEN_BOOK_PAGE -> WidgetLaunch.OpenBook(bookId)
    WidgetPlayPlan.OPEN_UNLOCK -> WidgetLaunch.OpenUnlock
    else -> null
}

/**
 * The lock is read first, from entitlement state that is ready the moment the
 * process starts, so a locked tap never waits on anything. Only an unlocked
 * tap waits for [awaitReady] (settings, or the startup restore) before
 * [decideUnlocked] reads the book.
 */
internal suspend fun decideBinderTap(
    isUnlocked: () -> Boolean,
    awaitReady: suspend () -> Unit,
    decideUnlocked: suspend () -> WidgetPlayPlan,
): WidgetPlayPlan {
    if (!isUnlocked()) return WidgetPlayPlan.OPEN_UNLOCK
    awaitReady()
    return decideUnlocked()
}

/** The longest the invisible handoff screen may sit over the home screen. */
internal val WIDGET_HANDOFF_CAP: Duration = 2.seconds

/**
 * The handoff screen's wait. A plan decided within [cap] goes to [onPlan]
 * while the screen is still up (so a refusal can open a page). A slower one
 * (a cold start with a slow restore) closes the screen at [cap] and goes to
 * [onLatePlan] still running. [finish] always runs, and closing the screen
 * never cancels [decision], so a play carries on in the background.
 */
internal suspend fun handOff(
    decision: Deferred<WidgetPlayPlan>,
    cap: Duration,
    onPlan: suspend (WidgetPlayPlan) -> Unit,
    onLatePlan: (Deferred<WidgetPlayPlan>) -> Unit,
    finish: () -> Unit,
) {
    try {
        val plan = withTimeoutOrNull(cap) { decision.await() }
        if (plan != null) onPlan(plan) else onLatePlan(decision)
    } finally {
        finish()
    }
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
     * Decisions and loads run on this class's own scope. The caller gets the
     * plan as soon as it is decided, and a book load carries on here, so the
     * invisible trampoline can close at once instead of covering the home
     * screen (and swallowing taps) for the whole load. Its finishing never
     * cancels a load halfway.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Starts a Binder tap's decision on this class's scope and hands it back
     * at once, so the handoff screen can stop waiting without stopping it.
     */
    internal fun binderTap(launch: WidgetLaunch.PlayBook): Deferred<WidgetPlayPlan> =
        scope.async { playFromBinderOwned(launch.bookId) }

    internal fun binderTap(launch: WidgetLaunch.OpenBook): Deferred<WidgetPlayPlan> =
        scope.async { openFromBinderOwned(launch.bookId) }

    /**
     * A refusal or Open decided after the handoff screen closed. Opens its
     * screen from the app. The tap just happened, so Android normally allows
     * it. If it does not, the home screen already has its touch back and the
     * next tap acts at once.
     */
    internal fun openWhenDecided(context: Context, bookId: String, decision: Deferred<WidgetPlayPlan>) {
        val app = context.applicationContext
        scope.launch {
            val launch = refusalLaunch(decision.await(), bookId) ?: return@launch
            runCatching { app.startActivity(widgetMainIntent(app, launch)) }
                .onFailure { Log.w(TAG, "late open refused: ${it.message}") }
        }
    }

    internal suspend fun toggle(shownBookId: String?): WidgetPlayPlan =
        scope.async { toggleOwned(shownBookId) }.await()

    /** The tapped book, only if it belongs to the library the app shows now. */
    private suspend fun activeBook(bookId: String): AudioBook? =
        withContext(Dispatchers.IO) { audioBookRepository.getById(bookId) }
            ?.takeIf { it.isInActiveLibrary(settingsManager.currentSettings) }

    private suspend fun playFromBinderOwned(bookId: String): WidgetPlayPlan = decideBinderTap(
        isUnlocked = { effectiveSettings.isUnlockedNow },
        awaitReady = { playbackManager.awaitStartupRestore() },
    ) {
        // A tap from a widget not yet redrawn after a mode or account switch
        // carries a book from the old library. It never plays or opens.
        val book = activeBook(bookId) ?: return@decideBinderTap WidgetPlayPlan.IGNORE
        val plan = binderPlayPlan(
            isUnlocked = true,
            bookId = bookId,
            currentBookId = playbackManager.currentBook.value?.id,
            state = playbackManager.playbackState.value,
            bookLoading = playbackManager.loadingBook.value != null,
            playable = isPlayable(book),
        )
        launchAct(plan, book)
        plan
    }

    private suspend fun openFromBinderOwned(bookId: String): WidgetPlayPlan = decideBinderTap(
        isUnlocked = { effectiveSettings.isUnlockedNow },
        // The active library is a setting, so a cold tap waits for settings
        // (not the playback restore, which Open does not need).
        awaitReady = { settingsManager.isLoaded.first { it } },
    ) {
        binderOpenPlan(isUnlocked = true, inActiveLibrary = activeBook(bookId) != null)
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
        launchAct(plan, shown)
        return plan
    }

    private fun launchAct(plan: WidgetPlayPlan, book: AudioBook?) {
        Log.d(TAG, "plan $plan for ${book?.id}")
        scope.launch {
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
