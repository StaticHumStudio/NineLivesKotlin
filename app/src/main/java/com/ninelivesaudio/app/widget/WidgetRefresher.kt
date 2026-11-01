package com.ninelivesaudio.app.widget

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ninelivesaudio.app.entitlement.EffectiveSettingsRepository
import com.ninelivesaudio.app.entitlement.EntitlementRepository
import com.ninelivesaudio.app.service.ConnectivityMonitor
import com.ninelivesaudio.app.service.PlaybackManager
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.service.SyncManager
import com.ninelivesaudio.app.service.cover.CoverPicker
import com.ninelivesaudio.app.service.local.LocalScanCoordinator
import com.ninelivesaudio.app.service.local.LocalScanState
import com.ninelivesaudio.app.widget.binder.BinderWidgetUpdater
import com.ninelivesaudio.app.widget.home.HomeWidgetStore
import com.ninelivesaudio.app.widget.home.NineLivesHomeWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes widget redraws when something a widget shows has changed: unlock,
 * mode, server, account or library, sync or scan finished, a cover picked or
 * undone, the current book,
 * and (for the large widget) play state, chapter, and a slow tick while
 * playing. Nothing polls while paused, and a burst of events is one redraw,
 * which keeps the design's battery anti goal.
 */
@Singleton
class WidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val effectiveSettings: EffectiveSettingsRepository,
    private val entitlementRepository: EntitlementRepository,
    private val settingsManager: SettingsManager,
    private val playbackManager: PlaybackManager,
    private val syncManager: SyncManager,
    private val localScanCoordinator: LocalScanCoordinator,
    private val connectivityMonitor: ConnectivityMonitor,
    private val binderUpdater: BinderWidgetUpdater,
    private val coverPicker: CoverPicker,
    private val homeStore: HomeWidgetStore,
) {
    private var started = false
    private val pushLock = Mutex()
    private val syncCount = MutableStateFlow(0L)
    private val scanCount = MutableStateFlow(0L)
    private val tick = MutableStateFlow(0L)

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true

        scope.launch { syncManager.syncCompleted.collect { syncCount.value++ } }
        scope.launch {
            var wasRunning = false
            localScanCoordinator.state.collect { state ->
                val running = state is LocalScanState.Running
                if (wasRunning && !running) scanCount.value++
                wasRunning = running
            }
        }
        // The playing tick: only while audible, so a paused app never wakes.
        scope.launch {
            var ticker: Job? = null
            playbackManager.playbackState.map { isPlayingForWidget(it) }.distinctUntilChanged().collect { playing ->
                ticker?.cancel()
                ticker = if (playing) {
                    launch {
                        while (true) {
                            delay(PLAYING_REFRESH_INTERVAL)
                            tick.value++
                        }
                    }
                } else {
                    null
                }
            }
        }

        val identity = combine(settingsManager.settings, settingsManager.hasAuthToken) { s, token ->
            WidgetIdentity(s.appMode, s.serverUrl, s.username, s.activeLibraryId, token)
        }
        val playback = combine(
            playbackManager.currentBook.map { it?.id },
            playbackManager.playbackState.map { isPlayingForWidget(it) },
            playbackManager.currentChapterIndex,
            playbackManager.loadingBook.map { it != null },
        ) { id, playing, chapter, loading -> PlaybackTrigger(id, playing, chapter, loading) }
        val counters = combine(syncCount, scanCount, tick, coverPicker.changeCount) { a, b, c, d -> listOf(a, b, c, d) }
        val reachable = connectivityMonitor.connectionStatus.map { it == ConnectivityMonitor.ConnectionStatus.CONNECTED }

        scope.launch {
            var last: WidgetTrigger? = null
            combine(effectiveSettings.isUnlocked, identity, playback, reachable, counters) { unlocked, id, play, reach, count ->
                WidgetTrigger(
                    isUnlocked = unlocked,
                    identity = id,
                    currentBookId = play.bookId,
                    isPlaying = play.isPlaying,
                    chapterIndex = play.chapterIndex,
                    isLoading = play.isLoading,
                    serverReachable = reach,
                    syncCount = count[0],
                    scanCount = count[1],
                    tick = count[2],
                    coverCount = count[3],
                )
            }
                .distinctUntilChanged()
                .debounce(WIDGET_PUSH_DEBOUNCE)
                .collect { next ->
                    val targets = widgetRefreshTargets(last, next)
                    last = next
                    if (targets.isNotEmpty()) pushNow(targets)
                }
        }

        // Relock after a trial even if the app is closed when it ends.
        scope.launch {
            entitlementRepository.state
                .map { if (it.isUnlocked) it.trialEndsAtEpochMs else null }
                .distinctUntilChanged()
                .collect { endsAt -> scheduleTrialRelock(endsAt) }
        }
    }

    /** Redraws [targets] now. A widget kind with none on the home screen reads nothing. */
    suspend fun pushNow(targets: Set<WidgetTarget>) = withContext(Dispatchers.IO) {
        pushLock.withLock {
            Log.d(TAG, "push $targets")
            if (WidgetTarget.BINDER in targets && binderUpdater.widgetIds().isNotEmpty()) {
                runCatching { binderUpdater.updateAll() }
                    .onFailure { Log.w(TAG, "binder update failed: ${it.message}") }
            }
            if (WidgetTarget.HOME in targets) {
                runCatching {
                    val placed = GlanceAppWidgetManager(context).getGlanceIds(NineLivesHomeWidget::class.java)
                    if (placed.isNotEmpty()) {
                        homeStore.refresh()
                        NineLivesHomeWidget().updateAll(context)
                    }
                }.onFailure { Log.w(TAG, "home update failed: ${it.message}") }
            }
        }
    }

    private fun scheduleTrialRelock(trialEndsAtEpochMs: Long?) {
        val workManager = WorkManager.getInstance(context)
        val delayMs = trialRelockDelayMs(trialEndsAtEpochMs, System.currentTimeMillis())
        if (delayMs == null) {
            workManager.cancelUniqueWork(WidgetTrialRelockWorker.UNIQUE_WORK_NAME)
            return
        }
        workManager.enqueueUniqueWork(
            WidgetTrialRelockWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WidgetTrialRelockWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    private companion object {
        const val TAG = "WidgetRefresher"
    }
}
