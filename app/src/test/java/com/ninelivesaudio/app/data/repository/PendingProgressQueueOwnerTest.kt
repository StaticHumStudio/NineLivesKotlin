package com.ninelivesaudio.app.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingProgressQueueOwnerTest {

    @Test
    fun `activating next book does not wait for previous book ownership`() = runBlocking {
        val owner = PendingProgressQueueOwner()
        owner.setActiveItem("book-a")
        val previousEntered = CompletableDeferred<Unit>()
        val releasePrevious = CompletableDeferred<Unit>()
        val activationCompleted = CompletableDeferred<Unit>()

        val previousWork = launch {
            owner.withItemLock("book-a") {
                previousEntered.complete(Unit)
                releasePrevious.await()
            }
        }
        previousEntered.await()

        val activation = launch {
            owner.setActiveItem("book-b")
            activationCompleted.complete(Unit)
        }

        try {
            withTimeout(1_000) { activationCompleted.await() }
        } finally {
            releasePrevious.complete(Unit)
        }
        previousWork.join()
        activation.join()
    }

    @Test
    fun `stale activation is rejected after waiting for next book ownership`() = runBlocking {
        val owner = PendingProgressQueueOwner()
        val nextEntered = CompletableDeferred<Unit>()
        val releaseNext = CompletableDeferred<Unit>()
        var current = true
        var activated: Boolean? = null

        val nextWork = launch {
            owner.withItemLock("book-b") {
                nextEntered.complete(Unit)
                releaseNext.await()
            }
        }
        nextEntered.await()

        val activation = launch {
            activated = owner.setActiveItem("book-b") { current }
        }
        yield()
        current = false
        releaseNext.complete(Unit)
        nextWork.join()
        activation.join()

        assertEquals(false, activated)
    }

    @Test
    fun `committed row becomes stale when its playback lifetime invalidates`() {
        val owner = PendingProgressQueueOwner()
        val token = owner.token("book-a")

        owner.trackRow(42L, token)
        assertTrue(owner.rowIsCurrent(42L))

        owner.invalidate("book-a")

        assertFalse(owner.rowIsCurrent(42L))
    }

    @Test
    fun `new terminal write waits behind an in-flight older queue push`() = runBlocking {
        val owner = PendingProgressQueueOwner()
        val token = owner.token("book-a")
        val pushEntered = CompletableDeferred<Unit>()
        val releasePush = CompletableDeferred<Unit>()
        val terminalEntered = CompletableDeferred<Unit>()

        owner.trackRow(42L, token)
        val push = launch {
            owner.withItemLock("book-a") {
                assertTrue(owner.rowIsCurrent(42L))
                pushEntered.complete(Unit)
                releasePush.await()
            }
        }

        pushEntered.await()
        owner.invalidate("book-a")
        val terminal = launch {
            owner.withItemLock("book-a") { terminalEntered.complete(Unit) }
        }
        yield()
        assertFalse(terminalEntered.isCompleted)

        releasePush.complete(Unit)
        push.join()
        terminal.join()
        assertTrue(terminalEntered.isCompleted)
    }

    @Test
    fun `direct heartbeat revalidates after waiting for item ownership`() = runBlocking {
        val owner = PendingProgressQueueOwner()
        val ownerEntered = CompletableDeferred<Unit>()
        val releaseOwner = CompletableDeferred<Unit>()
        var current = true
        var pushed = false

        val blocker = launch {
            owner.withItemLock("book-a") {
                ownerEntered.complete(Unit)
                releaseOwner.await()
            }
        }
        ownerEntered.await()

        val heartbeat = launch {
            owner.withItemLockIfCurrent("book-a", isCurrent = { current }) {
                pushed = true
            }
        }
        current = false
        releaseOwner.complete(Unit)
        blocker.join()
        heartbeat.join()

        assertFalse(pushed)
    }

    @Test
    fun `server import rechecks active ownership inside item lock`() = runBlocking {
        val owner = PendingProgressQueueOwner()
        val blockerEntered = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        var imported = false

        val blocker = launch {
            owner.withItemLock("book-a") {
                blockerEntered.complete(Unit)
                releaseBlocker.await()
            }
        }
        blockerEntered.await()

        val activation = launch { owner.setActiveItem("book-a") }
        yield()
        val import = launch {
            owner.withItemLockIfInactive("book-a") { imported = true }
        }
        releaseBlocker.complete(Unit)
        blocker.join()
        activation.join()
        import.join()

        assertFalse(imported)
    }

    @Test
    fun `terminal lease blocks server import while another book becomes active`() = runBlocking {
        val owner = PendingProgressQueueOwner()
        val terminalEntered = CompletableDeferred<Unit>()
        val releaseTerminal = CompletableDeferred<Unit>()
        var imported = false

        val terminal = launch {
            owner.withTerminalImportLease("book-a") {
                terminalEntered.complete(Unit)
                releaseTerminal.await()
            }
        }
        terminalEntered.await()
        owner.setActiveItem("book-b")

        owner.withItemLockIfInactive("book-a") { imported = true }

        assertFalse(imported)
        releaseTerminal.complete(Unit)
        terminal.join()
    }

    @Test
    fun `conditional clear cannot remove a newer active book`() = runBlocking {
        val owner = PendingProgressQueueOwner()
        var newerBookImported = false
        owner.setActiveItem("book-a")
        owner.setActiveItem("book-b")

        assertFalse(owner.clearActiveItemIf("book-a"))
        owner.withItemLockIfInactive("book-b") { newerBookImported = true }

        assertFalse(newerBookImported)
    }

    @Test
    fun `failed session sync retains its pending fallback`() = runBlocking {
        var acknowledged = false

        val delivered = acknowledgePendingFallbackOnSuccess(
            deliver = { false },
            acknowledge = { acknowledged = true },
        )

        assertFalse(delivered)
        assertFalse(acknowledged)
    }

    @Test
    fun `successful session sync acknowledges its pending fallback`() = runBlocking {
        var acknowledged = false

        val delivered = acknowledgePendingFallbackOnSuccess(
            deliver = { true },
            acknowledge = { acknowledged = true },
        )

        assertTrue(delivered)
        assertTrue(acknowledged)
    }

    @Test
    fun `local write invalidates a server response fetched earlier`() {
        val owner = PendingProgressQueueOwner()
        val importToken = owner.importToken()

        owner.localWriteOccurred("book-a")

        assertFalse(owner.importTokenIsCurrent("book-a", importToken))
    }

    @Test
    fun `write for one book does not invalidate another books server response`() {
        val owner = PendingProgressQueueOwner()
        val importToken = owner.importToken()

        owner.localWriteOccurred("book-a")

        assertTrue(owner.importTokenIsCurrent("book-b", importToken))
    }

    @Test
    fun `cancellation after insert still removes the stale exact row`() = runBlocking {
        val insertEntered = CompletableDeferred<Unit>()
        val releaseInsert = CompletableDeferred<Unit>()
        var current = true
        var deletedId: Long? = null

        val job = launch {
            insertOwnedPendingProgress(
                enqueue = {
                    insertEntered.complete(Unit)
                    releaseInsert.await()
                    42L
                },
                isCurrent = { current },
                delete = { deletedId = it },
            )
        }

        insertEntered.await()
        current = false
        job.cancel()
        releaseInsert.complete(Unit)
        job.join()

        assertEquals(42L, deletedId)
    }

    @Test
    fun `queue consumer waits until stale insert rollback completes`() = runBlocking {
        val owner = PendingProgressQueueOwner()
        val insertEntered = CompletableDeferred<Unit>()
        val releaseInsert = CompletableDeferred<Unit>()
        val deleteEntered = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        val consumerEntered = CompletableDeferred<Unit>()

        val insertion = launch {
            owner.withLock {
                insertOwnedPendingProgress(
                    enqueue = {
                        insertEntered.complete(Unit)
                        releaseInsert.await()
                        42L
                    },
                    isCurrent = { false },
                    delete = {
                        deleteEntered.complete(Unit)
                        releaseDelete.await()
                    },
                )
            }
        }

        insertEntered.await()
        val consumer = launch {
            owner.withLock { consumerEntered.complete(Unit) }
        }

        releaseInsert.complete(Unit)
        deleteEntered.await()
        yield()
        assertFalse(consumerEntered.isCompleted)

        releaseDelete.complete(Unit)
        insertion.join()
        consumer.join()
        assertTrue(consumerEntered.isCompleted)
    }
}
