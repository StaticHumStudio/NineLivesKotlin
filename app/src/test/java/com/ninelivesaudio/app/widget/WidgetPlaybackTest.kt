package com.ninelivesaudio.app.widget

import com.ninelivesaudio.app.service.PlaybackState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test(timeout = 2_000) fun `a locked play tap opens unlock without waiting for the startup restore`() = runBlocking {
        var decided = false
        val plan = decideBinderTap(
            isUnlocked = { false },
            awaitReady = { awaitCancellation() },
            decideUnlocked = { decided = true; WidgetPlayPlan.LOAD_BOOK },
        )
        assertEquals(WidgetPlayPlan.OPEN_UNLOCK, plan)
        assertFalse(decided)
    }

    @Test fun `an unlocked tap decides only after the app is ready`() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val tap = async {
            decideBinderTap(
                isUnlocked = { true },
                awaitReady = { ready.await() },
                decideUnlocked = { order += "decide"; WidgetPlayPlan.LOAD_BOOK },
            )
        }
        yield()
        order += "ready"
        ready.complete(Unit)
        assertEquals(WidgetPlayPlan.LOAD_BOOK, tap.await())
        assertEquals(listOf("ready", "decide"), order)
    }

    @Test fun `open its page runs the same lock and active library checks as play`() {
        assertEquals(WidgetPlayPlan.OPEN_UNLOCK, binderOpenPlan(isUnlocked = false, inActiveLibrary = true))
        assertEquals(WidgetPlayPlan.OPEN_UNLOCK, binderOpenPlan(isUnlocked = false, inActiveLibrary = false))
        assertEquals(WidgetPlayPlan.IGNORE, binderOpenPlan(isUnlocked = true, inActiveLibrary = false))
        assertEquals(WidgetPlayPlan.OPEN_BOOK_PAGE, binderOpenPlan(isUnlocked = true, inActiveLibrary = true))
    }

    @Test fun `only a refusal opens a screen, and it names the tapped book`() {
        assertEquals(WidgetLaunch.OpenBook("b1"), refusalLaunch(WidgetPlayPlan.OPEN_BOOK_PAGE, "b1"))
        assertEquals(WidgetLaunch.OpenUnlock, refusalLaunch(WidgetPlayPlan.OPEN_UNLOCK, "b1"))
        listOf(WidgetPlayPlan.IGNORE, WidgetPlayPlan.PAUSE, WidgetPlayPlan.RESUME_CURRENT, WidgetPlayPlan.LOAD_BOOK)
            .forEach { assertNull(refusalLaunch(it, "b1")) }
    }

    @Test fun `a quick decision is acted on before the handoff screen closes`() = runBlocking {
        val order = mutableListOf<String>()
        handOff(
            decision = CompletableDeferred(WidgetPlayPlan.OPEN_UNLOCK),
            cap = 2.seconds,
            onPlan = { order += "plan $it" },
            onLatePlan = { order += "late" },
            finish = { order += "finish" },
        )
        assertEquals(listOf("plan OPEN_UNLOCK", "finish"), order)
    }

    @Test(timeout = 2_000) fun `a slow cold start closes the handoff screen at the cap and the tap carries on`() = runBlocking {
        val decision = CompletableDeferred<WidgetPlayPlan>()
        val order = mutableListOf<String>()
        val started = System.nanoTime()
        handOff(
            decision = decision,
            cap = 150.milliseconds,
            onPlan = { order += "plan" },
            onLatePlan = { late -> order += "late"; assertTrue(late === decision) },
            finish = { order += "finish" },
        )
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(listOf("late", "finish"), order)
        assertTrue("waited $waitedMs ms", waitedMs < 1_000)
        // The decision itself is never cancelled by the screen closing.
        assertTrue(decision.isActive)
        decision.complete(WidgetPlayPlan.LOAD_BOOK)
        assertEquals(WidgetPlayPlan.LOAD_BOOK, decision.await())
    }

    @Test fun `the handoff screen waits two seconds at most`() {
        assertTrue(WIDGET_HANDOFF_CAP <= 2.seconds)
    }
}
