package com.ninelivesaudio.app.widget

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.service.PlaybackState
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Whose books a widget may show. Any change redraws every widget. */
internal data class WidgetIdentity(
    val appMode: AppMode,
    val serverUrl: String,
    val username: String,
    val activeLibraryId: String?,
    val hasAuthToken: Boolean,
)

/** One snapshot of everything that can change what a widget shows. */
internal data class WidgetTrigger(
    val isUnlocked: Boolean,
    val identity: WidgetIdentity,
    val currentBookId: String?,
    val isPlaying: Boolean,
    val chapterIndex: Int,
    val serverReachable: Boolean,
    val syncCount: Long,
    val scanCount: Long,
    val tick: Long,
)

enum class WidgetTarget { BINDER, HOME }

/**
 * Which widgets need a redraw. Unlock, identity, sync, scan and the current
 * book change what both show. Play state, chapter, reachability and the
 * playing tick only change the large widget's current book card.
 */
internal fun widgetRefreshTargets(prev: WidgetTrigger?, next: WidgetTrigger): Set<WidgetTarget> {
    if (prev == null) return setOf(WidgetTarget.BINDER, WidgetTarget.HOME)
    if (prev.isUnlocked != next.isUnlocked ||
        prev.identity != next.identity ||
        prev.syncCount != next.syncCount ||
        prev.scanCount != next.scanCount ||
        prev.currentBookId != next.currentBookId
    ) return setOf(WidgetTarget.BINDER, WidgetTarget.HOME)
    if (prev.isPlaying != next.isPlaying ||
        prev.chapterIndex != next.chapterIndex ||
        prev.serverReachable != next.serverReachable ||
        prev.tick != next.tick
    ) return setOf(WidgetTarget.HOME)
    return emptySet()
}

/** Bursts of events (a book load flips several flows) become one redraw. */
internal val WIDGET_PUSH_DEBOUNCE = 750.milliseconds

/** While playing, the large widget's progress refreshes this often (J2). Never while paused. */
internal val PLAYING_REFRESH_INTERVAL = 5.minutes

/** Buffering counts as playing, so a buffering blip does not push a redraw. */
internal fun isPlayingForWidget(state: PlaybackState): Boolean =
    state == PlaybackState.PLAYING || state == PlaybackState.BUFFERING

/** How long until the relock job runs: just after the trial ends. Null without a trial end. */
internal fun trialRelockDelayMs(trialEndsAtEpochMs: Long?, nowEpochMs: Long, slackMs: Long = 5_000): Long? =
    trialEndsAtEpochMs?.let { maxOf(0L, it - nowEpochMs) + slackMs }
