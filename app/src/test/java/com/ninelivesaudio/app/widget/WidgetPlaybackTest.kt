package com.ninelivesaudio.app.widget

import com.ninelivesaudio.app.service.ConnectivityMonitor
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

    @Test fun `a locked play button sends the tap to the unlock screen`() {
        assertEquals(WidgetPlayPlan.OPEN_UNLOCK, togglePlan(false, "b1", PlaybackState.PLAYING, false, "b1", true))
    }

    @Test fun `play pause toggles the retained book like the mini player`() {
        assertEquals(WidgetPlayPlan.PAUSE, togglePlan(true, "b1", PlaybackState.BUFFERING, false, "b1", true))
        assertEquals(WidgetPlayPlan.RESUME_CURRENT, togglePlan(true, "b1", PlaybackState.STOPPED, false, "b1", true))
    }

    @Test fun `a stale shown book from another library is ignored even while playing`() {
        assertEquals(WidgetPlayPlan.IGNORE, togglePlan(true, "new", PlaybackState.PLAYING, false, shownBookId = "old", shownPlayable = true, shownInActiveLibrary = false))
        assertEquals(WidgetPlayPlan.IGNORE, togglePlan(true, "new", PlaybackState.PAUSED, false, shownBookId = "old", shownPlayable = true, shownInActiveLibrary = false))
        assertEquals(WidgetPlayPlan.PAUSE, togglePlan(true, "b1", PlaybackState.PLAYING, false, shownBookId = "b1", shownPlayable = true, shownInActiveLibrary = true))
    }

    @Test fun `with nothing retained play loads the book the widget shows`() {
        assertEquals(WidgetPlayPlan.LOAD_BOOK, togglePlan(true, null, PlaybackState.STOPPED, false, shownBookId = "b3", shownPlayable = true))
        // Like a Binder cover: a book that cannot play right now opens its page, which says why.
        assertEquals(WidgetPlayPlan.OPEN_BOOK_PAGE, togglePlan(true, null, PlaybackState.STOPPED, false, shownBookId = "b3", shownPlayable = false))
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

    @Test fun `a refusal is acted on and the handoff closes without waiting for playback`() = runBlocking {
        val order = mutableListOf<String>()
        handOff(
            decision = CompletableDeferred(WidgetPlayPlan.OPEN_UNLOCK),
            cap = 30.seconds,
            onPlan = { order += "plan $it" },
            awaitStarted = { order += "waited"; awaitCancellation() },
            onLatePlan = { order += "late" },
            finish = { order += "finish" },
        )
        assertEquals(listOf("plan OPEN_UNLOCK", "finish"), order)
    }

    @Test(timeout = 2_000) fun `a play holds the handoff until the playback service has started, then closes`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val job = launch {
            handOff(
                decision = CompletableDeferred(WidgetPlayPlan.LOAD_BOOK),
                cap = 30.seconds,
                onPlan = { order += "plan $it" },
                awaitStarted = { started.await(); order += "service started" },
                onLatePlan = { order += "late" },
                finish = { order += "finish" },
            )
        }
        yield()
        assertEquals(listOf("plan LOAD_BOOK"), order)
        started.complete(Unit)
        job.join()
        assertEquals(listOf("plan LOAD_BOOK", "service started", "finish"), order)
    }

    @Test(timeout = 2_000) fun `a decision slower than the cap closes the handoff and carries on`() = runBlocking {
        val decision = CompletableDeferred<WidgetPlayPlan>()
        val order = mutableListOf<String>()
        handOff(
            decision = decision,
            cap = 150.milliseconds,
            onPlan = { order += "plan" },
            awaitStarted = { order += "waited" },
            onLatePlan = { late -> order += "late"; assertTrue(late === decision) },
            finish = { order += "finish" },
        )
        assertEquals(listOf("late", "finish"), order)
        assertTrue(decision.isActive)
    }

    @Test(timeout = 2_000) fun `a service that never starts cannot hold the handoff past the cap`() = runBlocking {
        val order = mutableListOf<String>()
        handOff(
            decision = CompletableDeferred(WidgetPlayPlan.RESUME_CURRENT),
            cap = 150.milliseconds,
            onPlan = { order += "plan" },
            awaitStarted = { awaitCancellation() },
            onLatePlan = { order += "late" },
            finish = { order += "finish" },
        )
        assertEquals(listOf("plan", "finish"), order)
    }

    @Test(timeout = 2_000) fun `the hold ends when the service starts`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val load = CompletableDeferred<Unit>()
        val wait = async { awaitServiceStart(started, load, grace = 10.seconds) }
        yield()
        started.complete(Unit)
        wait.await()
    }

    @Test(timeout = 2_000) fun `a load that ends without playing ends the hold after a short grace`() = runBlocking {
        val load = CompletableDeferred<Unit>()
        val wait = async { awaitServiceStart(CompletableDeferred(), load, grace = 100.milliseconds) }
        yield()
        assertFalse(wait.isCompleted)
        load.complete(Unit)
        wait.await()
    }

    @Test fun `the handoff can never stay up for long`() {
        assertTrue(WIDGET_HANDOFF_CAP <= 30.seconds)
    }

    @Test fun `a cold tap waits for the first server check before calling a streamed book unplayable`() = runBlocking {
        // A process just started: the monitor still says OFFLINE until its first probe.
        var status = ConnectivityMonitor.ConnectionStatus.OFFLINE
        var settled = 0
        val result = settledConnectionStatus(current = { status }) {
            settled++
            status = ConnectivityMonitor.ConnectionStatus.CONNECTED
        }
        assertEquals(ConnectivityMonitor.ConnectionStatus.CONNECTED, result)
        assertEquals(1, settled)
    }

    @Test fun `a connected app plays without waiting on another check`() = runBlocking {
        var settled = 0
        val result = settledConnectionStatus(current = { ConnectivityMonitor.ConnectionStatus.CONNECTED }) { settled++ }
        assertEquals(ConnectivityMonitor.ConnectionStatus.CONNECTED, result)
        assertEquals(0, settled)
    }

    @Test fun `a server that is really down is still reported after the check`() = runBlocking {
        val result = settledConnectionStatus(current = { ConnectivityMonitor.ConnectionStatus.SERVER_UNREACHABLE }) {}
        assertEquals(ConnectivityMonitor.ConnectionStatus.SERVER_UNREACHABLE, result)
    }

    @Test(timeout = 3_000) fun `a resume that reloads the book holds until the reload clears`() = runBlocking {
        val busy = kotlinx.coroutines.flow.MutableStateFlow(false)
        val order = mutableListOf<String>()
        val wait = launch { awaitResumeSettled(busy, kotlinx.coroutines.flow.MutableStateFlow(false), startWindow = 1.seconds); order += "settled" }
        kotlinx.coroutines.delay(50)
        busy.value = true // play() launched the reload
        kotlinx.coroutines.delay(200)
        assertTrue(wait.isActive)
        order += "reload done"
        busy.value = false
        wait.join()
        assertEquals(listOf("reload done", "settled"), order)
    }

    @Test(timeout = 2_000) fun `a resume that plays straight away settles at once`() = runBlocking {
        awaitResumeSettled(kotlinx.coroutines.flow.MutableStateFlow(false), kotlinx.coroutines.flow.MutableStateFlow(true), startWindow = 10.seconds)
    }

    @Test(timeout = 2_000) fun `a resume that starts nothing settles after the start window`() = runBlocking {
        val started = System.nanoTime()
        awaitResumeSettled(kotlinx.coroutines.flow.MutableStateFlow(false), kotlinx.coroutines.flow.MutableStateFlow(false), startWindow = 100.milliseconds)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }
}
