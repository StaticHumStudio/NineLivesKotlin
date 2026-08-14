package com.ninelivesaudio.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SyncManager.syncNow must do an internet check before attempting a server
 * sync. Without it, the periodic timer fired sync calls in airplane mode,
 * flipping isSyncing=true (UI: "Syncing") and hanging on doomed sockets
 * instead of dropping the app straight to offline behavior.
 */
class SyncGateTest {

    @Test
    fun `shelf write revalidates ownership after suspended read`() = runBlocking {
        val readEntered = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        var current = true
        var written = false

        val update = async {
            readAndWriteShelfProgressIfCurrent(
                isCurrent = { current },
                read = {
                    readEntered.complete(Unit)
                    releaseRead.await()
                    0.25
                },
                write = { written = true },
            )
        }
        readEntered.await()
        current = false
        releaseRead.complete(Unit)

        assertFalse(update.await())
        assertFalse(written)
    }

    @Test
    fun `syncs when online, remote mode, authenticated`() {
        assertTrue(shouldRunSync(isOnline = true, isLocalMode = false, hasAuth = true))
    }

    @Test
    fun `does not sync when offline`() {
        // Airplane mode: the bug. No network means no sync attempt.
        assertFalse(shouldRunSync(isOnline = false, isLocalMode = false, hasAuth = true))
    }

    @Test
    fun `does not sync in local mode`() {
        assertFalse(shouldRunSync(isOnline = true, isLocalMode = true, hasAuth = true))
    }

    @Test
    fun `does not sync without an auth token`() {
        assertFalse(shouldRunSync(isOnline = true, isLocalMode = false, hasAuth = false))
    }
}

class PlaybackThrottleOwnerTest {

    @Test
    fun `unknown duration is durable locally but not pushed as zero percent`() {
        assertFalse(
            shouldPushPlaybackPosition(
                throttle = PlaybackThrottleSnapshot(),
                currentTime = 120.0,
                duration = 0.0,
                isFinished = false,
                now = 100_000L,
            ),
        )
        assertTrue(
            shouldPushPlaybackPosition(
                throttle = PlaybackThrottleSnapshot(),
                currentTime = 120.0,
                duration = 0.0,
                isFinished = true,
                now = 100_000L,
            ),
        )
    }

    @Test
    fun `unknown duration preserves existing shelf fraction`() {
        assertEquals(
            0.42,
            shelfProgress(
                currentTime = 120.0,
                duration = 0.0,
                isFinished = false,
                existingProgress = 0.42,
            ),
            0.0,
        )
        assertEquals(1.0, shelfProgress(120.0, 0.0, true, 0.42), 0.0)
    }

    @Test
    fun `successful push for one book does not throttle another book`() {
        val owner = PlaybackThrottleOwner()
        owner.recordSuccess("book-a", currentTime = 120.0, timestamp = 100_000L)

        val bookA = owner.snapshot("book-a")
        val bookB = owner.snapshot("book-b")

        assertFalse(
            shouldPushPlaybackPosition(
                throttle = bookA,
                currentTime = 121.0,
                duration = 1_000.0,
                isFinished = false,
                now = 100_001L,
            ),
        )
        assertTrue(
            shouldPushPlaybackPosition(
                throttle = bookB,
                currentTime = 20.0,
                duration = 1_000.0,
                isFinished = false,
                now = 100_001L,
            ),
        )
    }
}
