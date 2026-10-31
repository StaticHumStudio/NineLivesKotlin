package com.ninelivesaudio.app.ui.copy.unhinged.catalog

import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.service.PlaybackState
import com.ninelivesaudio.app.service.PlaybackState.BUFFERING
import com.ninelivesaudio.app.service.PlaybackState.LOADING
import com.ninelivesaudio.app.service.PlaybackState.PAUSED
import com.ninelivesaudio.app.service.PlaybackState.PLAYING
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperEventsTest {

    private var now = 0L

    /** Plays [steps] in order, each as (state, clock value when it arrives). */
    private fun timed(vararg steps: Pair<PlaybackState, Long>) = flow {
        for ((state, at) in steps) {
            now = at
            emit(state)
        }
    }

    private fun resumes(vararg steps: Pair<PlaybackState, Long>): Int = runBlocking {
        playbackResumes(timed(*steps)) { now }.toList().size
    }

    @Test fun `pause then play after 30 seconds is a resume`() {
        assertEquals(1, resumes(PLAYING to 0, PAUSED to 1_000, PLAYING to 31_000))
    }

    @Test fun `a pause shorter than 30 seconds is not a resume`() {
        assertEquals(0, resumes(PLAYING to 0, PAUSED to 1_000, PLAYING to 30_999))
    }

    @Test fun `buffering between pause and play still counts`() {
        assertEquals(1, resumes(PAUSED to 0, BUFFERING to 20_000, PLAYING to 40_000))
    }

    @Test fun `loading then playing is not a resume`() {
        assertEquals(0, resumes(PAUSED to 0, LOADING to 1_000, PLAYING to 90_000))
    }

    @Test fun `the first state seen is never a resume`() {
        assertEquals(0, resumes(PLAYING to 0))
    }

    private fun tick(index: Int, pos: Double, end: Double?, playing: Boolean = true, book: String? = "b") =
        ChapterTick(book, index, pos, end, playing)

    private fun chapterEnds(vararg ticks: ChapterTick): Int = runBlocking {
        naturalChapterEnds(flowOf(*ticks)).toList().size
    }

    @Test fun `a chapter that plays to its end is a finished chapter`() {
        assertEquals(1, chapterEnds(tick(2, 599.6, 600.0), tick(3, 600.1, 900.0)))
    }

    @Test fun `skipping ahead from mid chapter is not a finished chapter`() {
        assertEquals(0, chapterEnds(tick(2, 300.0, 600.0), tick(3, 600.0, 900.0)))
    }

    @Test fun `a new book or a paused rollover is not a finished chapter`() {
        assertEquals(0, chapterEnds(tick(2, 599.6, 600.0, book = "a"), tick(3, 0.1, 900.0, book = "b")))
        assertEquals(0, chapterEnds(tick(2, 599.6, 600.0, playing = false), tick(3, 600.1, 900.0, playing = false)))
        assertEquals(0, chapterEnds(tick(2, 599.6, 600.0), tick(4, 900.1, 1200.0)))
    }

    private fun item(completedAt: Long?) = DownloadItem(id = "d", completedAt = completedAt)

    @Test fun `a completion replayed from before the collector started is not a whisper`() = runBlocking {
        val since = 5_000L
        assertEquals(0, completionsSince(flowOf(item(since - 1), item(null)), since).toList().size)
        assertEquals(1, completionsSince(flowOf(item(since)), since).toList().size)
    }

    @Test fun `every event maps to its own context`() = runBlocking {
        now = 0
        val states = flow {
            emit(PAUSED); now = 40_000; emit(PLAYING)
        }
        val out = whisperEvents(
            states = states,
            ticks = flowOf(tick(2, 599.6, 600.0), tick(3, 600.1, 900.0)),
            finishedBooks = flowOf("book"),
            completedDownloads = flowOf(item(10_000L)),
            sinceMillis = 1_000L,
            clock = { now },
        ).toList()
        assertEquals(
            setOf(
                WhisperContext.PLAYBACK_RESUMED,
                WhisperContext.CHAPTER_FINISHED,
                WhisperContext.BOOK_FINISHED,
                WhisperContext.DOWNLOAD_COMPLETED,
            ),
            out.toSet(),
        )
        assertEquals(4, out.size)
    }

    @Test fun `idle waits a full minute from the last touch`() = runBlocking {
        var clock = 0L
        val touched = MutableStateFlow(0L)
        val sleeps = mutableListOf<Long>()
        awaitIdle(startedAt = 0L, lastInteractionAt = touched, clock = { clock }) { ms ->
            sleeps += ms
            if (sleeps.size == 1) touched.value = 20_000L  // a touch during the first sleep
            clock += ms
        }
        assertEquals(60_000L + 20_000L, clock)
        assertEquals(listOf(60_000L, 20_000L), sleeps)
    }

    @Test fun `a touch from before Home opened does not count as idle time`() = runBlocking {
        var clock = 500_000L
        val touched = MutableStateFlow(10L)
        awaitIdle(startedAt = clock, lastInteractionAt = touched, clock = { clock }) { clock += it }
        assertEquals(560_000L, clock)
        assertTrue(true)
    }
}
