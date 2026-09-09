package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.remote.RemoteIdCodec
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

    private val bookA = ProgressIdentity(LOCAL_PROGRESS_OWNER_KEY, "book-a")
    private val bookB = ProgressIdentity(LOCAL_PROGRESS_OWNER_KEY, "book-b")

    @Test
    fun `activating next book does not wait for previous book ownership`() = runBlocking {
        val owner = PendingProgressQueueOwner()
        owner.setActiveItem(bookA)
        val previousEntered = CompletableDeferred<Unit>()
        val releasePrevious = CompletableDeferred<Unit>()
        val activationCompleted = CompletableDeferred<Unit>()

        val previousWork = launch {
            owner.withItemLock(bookA) {
                previousEntered.complete(Unit)
                releasePrevious.await()
            }
        }
        previousEntered.await()

        val activation = launch {
            owner.setActiveItem(bookB)
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
            owner.withItemLock(bookB) {
                nextEntered.complete(Unit)
                releaseNext.await()
            }
        }
        nextEntered.await()

        val activation = launch {
            activated = owner.setActiveItem(bookB) { current }
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
        val token = owner.token(bookA)

        owner.trackRow(42L, token)
        assertTrue(owner.rowIsCurrent(42L))

        owner.invalidate(bookA)

        assertFalse(owner.rowIsCurrent(42L))
    }

    @Test
    fun `new terminal write waits behind an in-flight older queue push`() = runBlocking {
        val owner = PendingProgressQueueOwner()
        val token = owner.token(bookA)
        val pushEntered = CompletableDeferred<Unit>()
        val releasePush = CompletableDeferred<Unit>()
        val terminalEntered = CompletableDeferred<Unit>()

        owner.trackRow(42L, token)
        val push = launch {
            owner.withItemLock(bookA) {
                assertTrue(owner.rowIsCurrent(42L))
                pushEntered.complete(Unit)
                releasePush.await()
            }
        }

        pushEntered.await()
        owner.invalidate(bookA)
        val terminal = launch {
            owner.withItemLock(bookA) { terminalEntered.complete(Unit) }
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
            owner.withItemLock(bookA) {
                ownerEntered.complete(Unit)
                releaseOwner.await()
            }
        }
        ownerEntered.await()

        val heartbeat = launch {
            owner.withItemLockIfCurrent(bookA, isCurrent = { current }) {
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
            owner.withItemLock(bookA) {
                blockerEntered.complete(Unit)
                releaseBlocker.await()
            }
        }
        blockerEntered.await()

        val activation = launch { owner.setActiveItem(bookA) }
        yield()
        val import = launch {
            owner.withItemLockIfInactive(bookA) { imported = true }
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
            owner.withTerminalImportLease(bookA) {
                terminalEntered.complete(Unit)
                releaseTerminal.await()
            }
        }
        terminalEntered.await()
        owner.setActiveItem(bookB)

        owner.withItemLockIfInactive(bookA) { imported = true }

        assertFalse(imported)
        releaseTerminal.complete(Unit)
        terminal.join()
    }

    @Test
    fun `conditional clear cannot remove a newer active book`() = runBlocking {
        val owner = PendingProgressQueueOwner()
        var newerBookImported = false
        owner.setActiveItem(bookA)
        owner.setActiveItem(bookB)

        assertFalse(owner.clearActiveItemIf(bookA))
        owner.withItemLockIfInactive(bookB) { newerBookImported = true }

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

        owner.localWriteOccurred(bookA)

        assertFalse(owner.importTokenIsCurrent(bookA, importToken))
    }

    @Test
    fun `write for one book does not invalidate another books server response`() {
        val owner = PendingProgressQueueOwner()
        val importToken = owner.importToken()

        owner.localWriteOccurred(bookA)

        assertTrue(owner.importTokenIsCurrent(bookB, importToken))
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

    @Test
    fun `same raw ABS item remains isolated across owner locks tokens active state and terminal leases`() = runBlocking {
        val owner = PendingProgressQueueOwner()
        val rawItemId = "shared-abs-item"
        val a = ProgressIdentity("owner-a", RemoteIdCodec.encode("owner-a", rawItemId))
        val b = ProgressIdentity("owner-b", RemoteIdCodec.encode("owner-b", rawItemId))
        val aLockEntered = CompletableDeferred<Unit>()
        val releaseALock = CompletableDeferred<Unit>()
        val bLockEntered = CompletableDeferred<Unit>()

        val aLock = launch {
            owner.withItemLock(a) {
                aLockEntered.complete(Unit)
                releaseALock.await()
            }
        }
        try {
            aLockEntered.await()
            withTimeout(1_000) {
                owner.withItemLock(b) { bLockEntered.complete(Unit) }
            }
            assertTrue(bLockEntered.isCompleted)

            val aToken = owner.token(a)
            val bToken = owner.token(b)
            owner.trackRow(1L, aToken)
            owner.trackRow(2L, bToken)
            val importToken = owner.importToken()
            owner.invalidate(a)
            owner.localWriteOccurred(a)

            assertFalse(owner.rowIsCurrent(1L))
            assertTrue(owner.rowIsCurrent(2L))
            assertFalse(owner.importTokenIsCurrent(a, importToken))
            assertTrue(owner.importTokenIsCurrent(b, importToken))

            releaseALock.complete(Unit)
            aLock.join()
            owner.setActiveItem(a)
            assertTrue(owner.withItemLockIfInactive(b) { true } ?: false)
            owner.withTerminalImportLease(a) {
                assertTrue(owner.withItemLockIfInactive(b) { true } ?: false)
            }
        } finally {
            releaseALock.complete(Unit)
            aLock.join()
        }
    }

}
