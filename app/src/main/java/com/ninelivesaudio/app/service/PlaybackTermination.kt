package com.ninelivesaudio.app.service

import androidx.media3.common.Player
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal enum class PlaybackTermination { STOP, COMPLETED, ERROR }

internal fun needsPlaybackPreparation(state: Int): Boolean =
    state == Player.STATE_IDLE || state == Player.STATE_ENDED

internal data class PlaybackResumePoint(val mediaItemIndex: Int, val positionMs: Long)

internal fun playbackResumePoint(state: Int, mediaItemIndex: Int, positionMs: Long): PlaybackResumePoint =
    if (state == Player.STATE_ENDED) PlaybackResumePoint(0, 0)
    else PlaybackResumePoint(mediaItemIndex, positionMs)

internal fun finishedAtTermination(reason: PlaybackTermination, position: Duration, duration: Duration): Boolean =
    reason == PlaybackTermination.COMPLETED ||
        (reason == PlaybackTermination.STOP && duration > Duration.ZERO &&
            position >= (duration - 1.seconds).coerceAtLeast(Duration.ZERO))
