package com.ninelivesaudio.app.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PlaybackProgressOwnerTest {

    @Test
    fun `load re-reads durable progress after an in-flight report`() = runBlocking {
        val owner = PlaybackProgressOwner()
        val reportEntered = CompletableDeferred<Unit>()
        val releaseReport = CompletableDeferred<Unit>()
        var durablePosition: Duration = 100.seconds

        val report = launch {
            owner.report("book-a") {
                reportEntered.complete(Unit)
                releaseReport.await()
                durablePosition = 110.seconds
            }
        }
        reportEntered.await()

        val load = async {
            owner.resolveAndSavePlaybackPosition(
                bookId = "book-a",
                candidate = 100.seconds,
                readDurable = { durablePosition },
                save = { durablePosition = it },
            )
        }
        yield()
        assertFalse(load.isCompleted)

        releaseReport.complete(Unit)
        report.join()
        assertTrue(load.await() == 110.seconds)
        assertTrue(durablePosition == 110.seconds)
    }

    @Test
    fun `pause flush waits for a canceled polling report to leave the sync gate`() = runBlocking {
        val owner = PlaybackProgressOwner()
        val firstEntered = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val pollingReport = launch {
            owner.report("book-a") {
                firstEntered.complete(Unit)
                try {
                    awaitCancellation()
                } catch (cancellation: CancellationException) {
                    cancellationObserved.complete(Unit)
                    withContext(NonCancellable) { releaseFirst.await() }
                    throw cancellation
                }
            }
        }
        firstEntered.await()
        pollingReport.cancel()
        cancellationObserved.await()

        val finalFlush = launch {
            owner.finalFlushSnapshot(
                token = owner.snapshotToken("book-a"),
                syncTerminal = { secondEntered.complete(Unit) },
                flushProgress = {},
            )
        }
        yield()
        assertFalse(secondEntered.isCompleted)

        releaseFirst.complete(Unit)
        pollingReport.join()
        finalFlush.join()
        assertTrue(secondEntered.isCompleted)
    }

    @Test
    fun `terminal durable final flush precedes suspendable session sync`() = runBlocking {
        val owner = PlaybackProgressOwner()
        val effects = mutableListOf<String>()

        owner.finalFlushSnapshot(
            token = owner.snapshotToken("book-a"),
            syncTerminal = { effects += "session" },
            flushProgress = { effects += "progress" },
        )

        assertTrue(effects == listOf("progress", "session"))
    }

    @Test
    fun `terminal invalidation rejects a pause snapshot that arrives late`() = runBlocking {
        val owner = PlaybackProgressOwner()
        val pauseToken = owner.snapshotToken("book-a")
        val effects = mutableListOf<String>()

        owner.invalidateSnapshots("book-a")
        owner.finalFlushSnapshot(
            token = owner.snapshotToken("book-a"),
            syncTerminal = { effects += "terminal-session" },
            flushProgress = { effects += "terminal-progress" },
        )
        owner.syncSnapshot(pauseToken) { effects += "stale-pause" }

        assertTrue(effects == listOf("terminal-progress", "terminal-session"))
    }

    @Test
    fun `newer pause snapshot supersedes an older pause snapshot`() = runBlocking {
        val owner = PlaybackProgressOwner()
        val olderPauseToken = owner.snapshotToken("book-a")
        val newerPauseToken = owner.snapshotToken("book-a")
        val effects = mutableListOf<String>()

        owner.syncSnapshot(newerPauseToken) { effects += "newer-pause" }
        owner.syncSnapshot(olderPauseToken) { effects += "older-pause" }

        assertTrue(effects == listOf("newer-pause"))
    }

    @Test
    fun `new playback lifetime rejects an older terminal flush`() = runBlocking {
        val owner = PlaybackProgressOwner()
        val oldTerminalToken = owner.snapshotToken("book-a")
        val effects = mutableListOf<String>()

        owner.invalidateSnapshots("book-a")
        owner.report("book-a") { effects += "new-playback" }
        owner.finalFlushSnapshot(
            token = oldTerminalToken,
            syncTerminal = { effects += "old-terminal-session" },
            flushProgress = { effects += "old-terminal-progress" },
        )

        assertTrue(effects == listOf("new-playback"))
    }

    @Test
    fun `starting a different book preserves the older books terminal flush`() = runBlocking {
        val owner = PlaybackProgressOwner()
        val bookATerminalToken = owner.snapshotToken("book-a")
        val effects = mutableListOf<String>()

        owner.invalidateSnapshots("book-b")
        owner.report("book-b") { effects += "book-b-playback" }
        owner.finalFlushSnapshot(
            token = bookATerminalToken,
            syncTerminal = { effects += "book-a-terminal-session" },
            flushProgress = { effects += "book-a-terminal-progress" },
        )

        assertTrue(
            effects == listOf(
                "book-b-playback",
                "book-a-terminal-progress",
                "book-a-terminal-session",
            ),
        )
    }

    @Test
    fun `terminal flush for one book does not block another books progress`() = runBlocking {
        val owner = PlaybackProgressOwner()
        val terminalEntered = CompletableDeferred<Unit>()
        val releaseTerminal = CompletableDeferred<Unit>()
        val bookBEntered = CompletableDeferred<Unit>()

        val terminal = launch {
            owner.finalFlushSnapshot(
                token = owner.snapshotToken("book-a"),
                syncTerminal = {
                    terminalEntered.complete(Unit)
                    releaseTerminal.await()
                },
                flushProgress = {},
            )
        }
        terminalEntered.await()
        val bookB = launch {
            owner.report("book-b") { bookBEntered.complete(Unit) }
        }

        val bookBProceeded = withTimeoutOrNull(1_000) {
            bookBEntered.await()
            true
        } ?: false
        assertTrue(bookBProceeded)

        releaseTerminal.complete(Unit)
        terminal.join()
        bookB.join()
    }
}

class PendingTerminalOwnerTest {

    @Test
    fun `ended restart runs only after terminal flush completes`() = runBlocking {
        val owner = PendingTerminalOwner()
        val releaseTerminal = CompletableDeferred<Unit>()
        var restarted = false

        owner.launch(this, "book-a") { releaseTerminal.await() }
        val restart = launch(start = CoroutineStart.UNDISPATCHED) {
            owner.runAfterPending("book-a", isCurrent = { true }) { restarted = true }
        }

        yield()
        assertFalse(restarted)
        releaseTerminal.complete(Unit)
        restart.join()
        assertTrue(restarted)
    }

    @Test
    fun `ended restart is discarded when playback changed during terminal flush`() = runBlocking {
        val owner = PendingTerminalOwner()
        val releaseTerminal = CompletableDeferred<Unit>()
        var current = true
        var restarted = false

        owner.launch(this, "book-a") { releaseTerminal.await() }
        val restart = launch(start = CoroutineStart.UNDISPATCHED) {
            owner.runAfterPending("book-a", isCurrent = { current }) { restarted = true }
        }

        current = false
        releaseTerminal.complete(Unit)
        restart.join()
        assertFalse(restarted)
    }

    @Test
    fun `same book load waits for its pending terminal flush`() = runBlocking {
        val owner = PendingTerminalOwner()
        val terminalEntered = CompletableDeferred<Unit>()
        val releaseTerminal = CompletableDeferred<Unit>()
        val loadContinued = CompletableDeferred<Unit>()

        owner.launch(this, "book-a") {
            terminalEntered.complete(Unit)
            releaseTerminal.await()
        }
        terminalEntered.await()
        val load = launch(start = CoroutineStart.UNDISPATCHED) {
            owner.await("book-a")
            loadContinued.complete(Unit)
        }

        yield()
        assertFalse(loadContinued.isCompleted)
        releaseTerminal.complete(Unit)
        load.join()
        assertTrue(loadContinued.isCompleted)
    }

    @Test
    fun `different book load does not wait for another books terminal flush`() = runBlocking {
        val owner = PendingTerminalOwner()
        val releaseTerminal = CompletableDeferred<Unit>()

        val terminal = owner.launch(this, "book-a") { releaseTerminal.await() }
        owner.await("book-b")

        assertTrue(terminal.isActive)
        releaseTerminal.complete(Unit)
        terminal.join()
    }

    @Test
    fun `same book load waits for every pending terminal flush`() = runBlocking {
        val owner = PendingTerminalOwner()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val loadContinued = CompletableDeferred<Unit>()

        val first = owner.launch(this, "book-a") { releaseFirst.await() }
        val load = launch(start = CoroutineStart.UNDISPATCHED) {
            owner.await("book-a")
            loadContinued.complete(Unit)
        }
        owner.launch(this, "book-a") { releaseSecond.await() }

        releaseFirst.complete(Unit)
        first.join()
        val continuedBeforeSecond = withTimeoutOrNull(1_000) {
            loadContinued.await()
            true
        } ?: false
        assertFalse(continuedBeforeSecond)
        releaseSecond.complete(Unit)
        load.join()
        assertTrue(loadContinued.isCompleted)
    }
}
