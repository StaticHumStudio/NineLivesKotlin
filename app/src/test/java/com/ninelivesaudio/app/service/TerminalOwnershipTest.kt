package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.repository.PendingProgressQueueOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR #121 review. A stop with nothing new to save still hands the book back
 * to the progress import, and a Play still asking the server never starts a
 * player that a stop emptied.
 */
class TerminalOwnershipTest {

    @Test(timeout = 5_000)
    fun `a stop after a pause that saved lets another device's spot reach the book`() = runBlocking {
        val owner = PendingProgressQueueOwner()
        val pending = PendingTerminalOwner()
        val spot = PlayedSpot()
        owner.setActiveItem("book")
        spot.bookPlaced()
        spot.playbackStarted()
        // Pause: its save lands, so the stop has nothing new to write.
        spot.saveLanded(spot.saveStarting())
        val writes = spot.takeForTerminalWrite(hasRetainedBook = true, alreadyFinalized = false)
        assertFalse(writes)

        launchTerminalWork(this, pending, "book", write = null) { owner.clearActiveItemIf(it) }
        pending.await("book")

        // The progress import from another device lands on the stopped book.
        val imported = owner.withItemLockIfInactive("book") { "imported" }
        assertEquals("imported", imported)
    }

    @Test(timeout = 5_000)
    fun `a terminal write that fails still releases the book`() = runBlocking {
        val owner = PendingProgressQueueOwner()
        val pending = PendingTerminalOwner()
        owner.setActiveItem("book")

        // The manager's scope is supervised, so a failed write does not take it down.
        val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
        launchTerminalWork(scope, pending, "book", write = { error("disk full") }) { owner.clearActiveItemIf(it) }
        pending.await("book")

        assertTrue(owner.withItemLockIfInactive("book") { true } == true)
    }

    @Test(timeout = 5_000)
    fun `a reload of the same book waits for the release, so it keeps its own claim`() = runBlocking {
        val owner = PendingProgressQueueOwner()
        val pending = PendingTerminalOwner()
        owner.setActiveItem("book")
        val releaseGate = CompletableDeferred<Unit>()

        launchTerminalWork(this, pending, "book", write = null) {
            releaseGate.await()
            owner.clearActiveItemIf(it)
        }
        releaseGate.complete(Unit)
        // The load awaits the book's terminal work before it claims.
        pending.await("book")
        owner.setActiveItem("book")

        assertEquals(null, owner.withItemLockIfInactive("book") { "imported" })
    }

    @Test(timeout = 5_000)
    fun `a stop while Play asks the server never resumes the player`() = runBlocking {
        var generation = 1L
        val resume = PendingResume { generation }
        val answer = CompletableDeferred<Unit>()
        var resumed = false

        resume.start(this, settle = { answer.await(); true }, resume = { resumed = true })
        yield()
        assertTrue(resume.isPending)
        // Stop: teardown cancels the wait and moves the generation.
        resume.cancel()
        generation++
        answer.complete(Unit)
        yield()
        yield()

        assertFalse(resumed)
        assertFalse(resume.isPending)
    }

    @Test(timeout = 5_000)
    fun `a server answer that lands after a stop does not resume, even if the wait was not cancelled`() = runBlocking {
        var generation = 1L
        val resume = PendingResume { generation }
        var resumed = false

        resume.start(
            this,
            settle = {
                // The stop lands while the answer is on its way back.
                generation++
                true
            },
            resume = { resumed = true },
        )
        yield()
        yield()

        assertFalse(resumed)
    }

    @Test(timeout = 5_000)
    fun `Play resumes once the server answers when nothing stopped it`() = runBlocking {
        val resume = PendingResume { 7L }
        var resumed = false

        resume.start(this, settle = { true }, resume = { resumed = true })
        yield()
        yield()

        assertTrue(resumed)
    }
}
