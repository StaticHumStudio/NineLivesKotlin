package com.ninelivesaudio.app.ui.copy.unhinged.catalog

import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.service.PlaybackManager
import com.ninelivesaudio.app.service.PlaybackState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlin.time.DurationUnit

/**
 * Pure detectors that turn playback and download flows into whisper moments.
 * They hold no shared state and never touch Android, so the rules are JVM tested.
 * The budgets (how rarely a whisper may show) live in [WhisperService].
 */

/** A pause shorter than this is a tap on the wrong button, not a return. */
internal const val MIN_PAUSE_FOR_RESUME_WHISPER_MS = 30_000L

/** How close to the chapter's end the last sample must be for a rollover to count as finished. */
internal const val CHAPTER_END_SLACK_SECONDS = 5.0

/** Time on Home with no touch before the idle whisper. */
internal const val IDLE_WHISPER_AFTER_MS = 60_000L

/**
 * Emits when playback goes PAUSED to PLAYING after a pause of at least 30 seconds by [clock].
 * BUFFERING is ignored. The first state seen never emits.
 */
internal fun playbackResumes(states: Flow<PlaybackState>, clock: () -> Long): Flow<Unit> = flow {
    var last: PlaybackState? = null
    var pausedAt = 0L
    states.collect { state ->
        if (state == PlaybackState.BUFFERING) return@collect
        if (state == PlaybackState.PAUSED && last != PlaybackState.PAUSED) pausedAt = clock()
        if (state == PlaybackState.PLAYING &&
            last == PlaybackState.PAUSED &&
            clock() - pausedAt >= MIN_PAUSE_FOR_RESUME_WHISPER_MS
        ) {
            emit(Unit)
        }
        last = state
    }
}

internal data class ChapterTick(
    val bookId: String?,
    val chapterIndex: Int,
    val positionSeconds: Double,
    val chapterEndSeconds: Double?,
    val playing: Boolean,
)

/**
 * Emits when the chapter index rises by exactly one while playing the same book, and the previous
 * sample was within [CHAPTER_END_SLACK_SECONDS] of that chapter's end. A negative gap counts,
 * because the poll writes the position before the index. A skip from mid chapter does not.
 */
internal fun naturalChapterEnds(ticks: Flow<ChapterTick>): Flow<Unit> = flow {
    var prev: ChapterTick? = null
    ticks.collect { tick ->
        val p = prev
        if (p != null &&
            p.bookId != null && p.bookId == tick.bookId &&
            p.playing && tick.playing &&
            tick.chapterIndex == p.chapterIndex + 1 &&
            p.chapterEndSeconds != null &&
            p.chapterEndSeconds - p.positionSeconds <= CHAPTER_END_SLACK_SECONDS
        ) {
            emit(Unit)
        }
        prev = tick
    }
}

/**
 * Only completions that happened since the collector started. `DownloadManager.downloadCompleted`
 * replays its last item to a new collector, which must not whisper about something old.
 */
internal fun completionsSince(completed: Flow<DownloadItem>, sinceMillis: Long): Flow<Unit> =
    completed
        .filter { item -> item.completedAt.let { it != null && it >= sinceMillis } }
        .map { }

internal fun whisperEvents(
    states: Flow<PlaybackState>,
    ticks: Flow<ChapterTick>,
    finishedBooks: Flow<String>,
    completedDownloads: Flow<DownloadItem>,
    sinceMillis: Long,
    clock: () -> Long,
): Flow<WhisperContext> = merge(
    playbackResumes(states, clock).map { WhisperContext.PLAYBACK_RESUMED },
    naturalChapterEnds(ticks).map { WhisperContext.CHAPTER_FINISHED },
    finishedBooks.map { WhisperContext.BOOK_FINISHED },
    completionsSince(completedDownloads, sinceMillis).map { WhisperContext.DOWNLOAD_COMPLETED },
)

/**
 * Returns once [idleMs] has passed since the later of [startedAt] and the last touch,
 * sleeping only the remainder each round.
 */
internal suspend fun awaitIdle(
    startedAt: Long,
    lastInteractionAt: StateFlow<Long>,
    clock: () -> Long,
    idleMs: Long = IDLE_WHISPER_AFTER_MS,
    sleep: suspend (Long) -> Unit = { delay(it) },
) {
    while (true) {
        val idleFor = clock() - maxOf(startedAt, lastInteractionAt.value)
        if (idleFor >= idleMs) return
        sleep(idleMs - idleFor)
    }
}

internal fun PlaybackManager.chapterTicks(): Flow<ChapterTick> =
    combine(
        currentBook.map { it?.id },
        currentChapterIndex,
        position,
        currentChapter,
        playbackState,
    ) { bookId, index, position, chapter, state ->
        ChapterTick(
            bookId = bookId,
            chapterIndex = index,
            positionSeconds = position.toDouble(DurationUnit.SECONDS),
            chapterEndSeconds = chapter?.end,
            playing = state == PlaybackState.PLAYING || state == PlaybackState.BUFFERING,
        )
    }
