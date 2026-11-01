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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One Binder tap: its decision, and the load or resume it starts (done when that returns). */
internal class WidgetTap(val plan: Deferred<WidgetPlayPlan>, val act: Deferred<Unit>)

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

/**
 * The connection status a widget tap judges a streamed book by. A process a
 * widget tap just started still reads OFFLINE (the monitor's starting value)
 * until its first server check, so anything short of connected runs [settle]
 * (that check) once and reads again. Without this, a cold Play with no book
 * retained called every streamed book unplayable and opened its page.
 */
internal suspend fun settledConnectionStatus(
    current: () -> ConnectivityMonitor.ConnectionStatus,
    settle: suspend () -> Unit,
): ConnectivityMonitor.ConnectionStatus {
    val now = current()
    if (now == ConnectivityMonitor.ConnectionStatus.CONNECTED || now == ConnectivityMonitor.ConnectionStatus.SYNCING) return now
    settle()
    return current()
}

/** How long a widget tap waits for that first server check. */
internal val WIDGET_SERVER_CHECK_WAIT: Duration = 8.seconds

/**
 * The longest the handoff screen may stay. It is invisible and lets every
 * touch through to the home screen (see WidgetActionActivity), so a long
 * hold costs the user nothing, but it still never stays past this.
 */
internal val WIDGET_HANDOFF_CAP: Duration = 30.seconds

/**
 * The handoff screen's life. A refusal or Open goes to [onPlan] and the
 * screen closes. A Play (load or resume) keeps it up until [awaitStarted]
 * says the playback service has started, because Android 12 and later only
 * lets a foreground service start while the app has a screen up, and a slow
 * cold start can take far longer than the tap's own grace window. [cap]
 * bounds all of it. A decision still running at [cap] goes to [onLatePlan]
 * and carries on, since closing the screen never cancels [decision].
 */
internal suspend fun handOff(
    decision: Deferred<WidgetPlayPlan>,
    cap: Duration,
    onPlan: suspend (WidgetPlayPlan) -> Unit,
    awaitStarted: suspend () -> Unit,
    onLatePlan: (Deferred<WidgetPlayPlan>) -> Unit,
    finish: () -> Unit,
) {
    try {
        withTimeoutOrNull(cap) {
            val plan = decision.await()
            onPlan(plan)
            if (plan == WidgetPlayPlan.LOAD_BOOK || plan == WidgetPlayPlan.RESUME_CURRENT) awaitStarted()
        }
        if (!decision.isCompleted) onLatePlan(decision)
    } finally {
        finish()
    }
}

/**
 * Returns when the playback service has started ([started]), or [grace]
 * after the load or resume itself ([act]) is done, whichever is first. The
 * grace covers a load that ended without playing (a failure the app reports
 * itself), and Media3 posting its notification just after the load returns.
 */
internal suspend fun awaitServiceStart(started: Deferred<Unit>, act: Deferred<Unit>, grace: Duration) {
    select {
        started.onAwait {}
        act.onAwait { withTimeoutOrNull(grace) { started.await() } }
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
    /**
     * Book Detail's Play gates: not archived, source reachable, remote media
     * not blocked. A streamed book that reads blocked gets one server check
     * first (see [settledConnectionStatus]), since a cold process has not run one.
     */
    internal suspend fun isPlayable(book: AudioBook): Boolean {
        val library = book.libraryId?.let { libraryRepository.getById(it) }
        val access = reconcileLocalBookAccess(
            book,
            localFolderAccess.accessibleLibraryIds(listOfNotNull(library)),
        )
        if (!canPlayBook(access.book.isArchived, access.isPlayable || access.book.isArchived)) return false
        fun decision(status: ConnectivityMonitor.ConnectionStatus) = remoteMediaAccessDecision(
            book = access.book,
            serverUrl = settingsManager.currentSettings.serverUrl,
            connectionStatus = status,
        )
        if (decision(connectivityMonitor.connectionStatus.value) !is RemoteMediaAccessDecision.Blocked) return true
        val status = settledConnectionStatus(current = { connectivityMonitor.connectionStatus.value }) {
            connectivityMonitor.refreshIsOnlineFromSystem()
            withTimeoutOrNull(WIDGET_SERVER_CHECK_WAIT) { connectivityMonitor.checkServerReachable() }
        }
        return decision(status) !is RemoteMediaAccessDecision.Blocked
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
    internal fun binderTap(launch: WidgetLaunch.PlayBook): WidgetTap {
        val act = CompletableDeferred<Unit>()
        val plan = scope.async { playFromBinderOwned(launch.bookId, act) }
        plan.invokeOnCompletion { if (it != null) act.complete(Unit) }
        return WidgetTap(plan, act)
    }

    internal fun binderTap(launch: WidgetLaunch.OpenBook): WidgetTap {
        val act = CompletableDeferred(Unit)
        return WidgetTap(scope.async { openFromBinderOwned(launch.bookId) }, act)
    }

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

    private suspend fun playFromBinderOwned(bookId: String, act: CompletableDeferred<Unit>): WidgetPlayPlan = decideBinderTap(
        isUnlocked = { effectiveSettings.isUnlockedNow },
        awaitReady = { playbackManager.awaitStartupRestore() },
    ) {
        // A tap from a widget not yet redrawn after a mode or account switch
        // carries a book from the old library. It never plays or opens.
        val book = activeBook(bookId) ?: return@decideBinderTap WidgetPlayPlan.IGNORE.also { act.complete(Unit) }
        val plan = binderPlayPlan(
            isUnlocked = true,
            bookId = bookId,
            currentBookId = playbackManager.currentBook.value?.id,
            state = playbackManager.playbackState.value,
            bookLoading = playbackManager.loadingBook.value != null,
            playable = isPlayable(book),
        )
        launchAct(plan, book, act)
        plan
    }.also { if (it == WidgetPlayPlan.OPEN_UNLOCK) act.complete(Unit) }

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

    private fun launchAct(plan: WidgetPlayPlan, book: AudioBook?, done: CompletableDeferred<Unit>? = null) {
        Log.d(TAG, "plan $plan for ${book?.id}")
        scope.launch {
            try {
                act(plan, book)
            } finally {
                done?.complete(Unit)
            }
        }
    }

    private suspend fun act(plan: WidgetPlayPlan, book: AudioBook?) {
        when (plan) {
            WidgetPlayPlan.RESUME_CURRENT -> playbackManager.play()
            WidgetPlayPlan.PAUSE -> playbackManager.pause()
            WidgetPlayPlan.LOAD_BOOK -> book?.let { playbackManager.loadAudioBook(it) }
            else -> Unit
        }
    }

    private companion object {
        const val TAG = "WidgetPlayback"
    }
}
