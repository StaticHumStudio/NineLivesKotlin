package com.ninelivesaudio.app.widget

import com.ninelivesaudio.app.service.PlaybackState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WidgetPlaybackTest {

    @Test fun `a locked binder sends the tap to the unlock screen`() {
        assertEquals(WidgetPlayPlan.OPEN_UNLOCK, binderPlayPlan(false, "b1", null, PlaybackState.STOPPED, bookLoading = false, playable = true))
    }

    @Test fun `a tap while a book loads is ignored`() {
        assertEquals(WidgetPlayPlan.IGNORE, binderPlayPlan(true, "b2", "b1", PlaybackState.PAUSED, bookLoading = true, playable = true))
        assertEquals(WidgetPlayPlan.IGNORE, binderPlayPlan(true, "b2", "b1", PlaybackState.LOADING, bookLoading = false, playable = true))
        assertEquals(WidgetPlayPlan.IGNORE, togglePlan(true, "b1", PlaybackState.PAUSED, bookLoading = true, shownBookId = "b1", shownPlayable = true))
    }

    @Test fun `tapping the cover that is already playing keeps it playing`() {
        assertEquals(WidgetPlayPlan.IGNORE, binderPlayPlan(true, "b1", "b1", PlaybackState.PLAYING, false, true))
        assertEquals(WidgetPlayPlan.RESUME_CURRENT, binderPlayPlan(true, "b1", "b1", PlaybackState.PAUSED, false, true))
    }

    @Test fun `a book that cannot play right now opens its page`() {
        assertEquals(WidgetPlayPlan.OPEN_BOOK_PAGE, binderPlayPlan(true, "b2", "b1", PlaybackState.PAUSED, false, playable = false))
        assertEquals(WidgetPlayPlan.LOAD_BOOK, binderPlayPlan(true, "b2", "b1", PlaybackState.PAUSED, false, playable = true))
    }

    @Test fun `play pause toggles the retained book like the mini player`() {
        assertEquals(WidgetPlayPlan.PAUSE, togglePlan(true, "b1", PlaybackState.BUFFERING, false, "b1", true))
        assertEquals(WidgetPlayPlan.RESUME_CURRENT, togglePlan(true, "b1", PlaybackState.STOPPED, false, "b1", true))
    }

    @Test fun `with nothing retained play loads the book the widget shows`() {
        assertEquals(WidgetPlayPlan.LOAD_BOOK, togglePlan(true, null, PlaybackState.STOPPED, false, shownBookId = "b3", shownPlayable = true))
        assertEquals(WidgetPlayPlan.IGNORE, togglePlan(true, null, PlaybackState.STOPPED, false, shownBookId = "b3", shownPlayable = false))
        assertEquals(WidgetPlayPlan.IGNORE, togglePlan(true, null, PlaybackState.STOPPED, false, shownBookId = null, shownPlayable = true))
    }

    @Test fun `toggle in a cold process waits for the startup restore`() = runBlocking {
        val restored = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val job = launch { awaitThen(restored, 10.seconds) { order += "plan" } }
        yield()
        order += "restore"
        restored.complete(Unit)
        job.join()
        assertEquals(listOf("restore", "plan"), order)
    }

    @Test(timeout = 2_000) fun `a restore that never finishes does not hold the tap forever`() = runBlocking {
        var ran = false
        awaitThen(CompletableDeferred(), 100.milliseconds) { ran = true }
        assertTrue(ran)
    }
}
