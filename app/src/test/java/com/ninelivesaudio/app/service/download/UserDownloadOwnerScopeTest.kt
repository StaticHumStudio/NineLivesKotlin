package com.ninelivesaudio.app.service.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pause, cancel and delete from the Downloads screen run in the screen's
 * ViewModel coroutine. Leaving the screen cancels it, and if that landed after
 * the worker was stopped but before the row write, the book stayed Downloading
 * with the queue stopped. The operation now runs in the manager's own scope,
 * so the caller can leave without taking the write and restart with it.
 */
class UserDownloadOwnerScopeTest {

    @Test
    fun `leaving the screen mid-operation still finishes the write`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stopped = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<String>()

        val caller = launch {
            finishInOwnerScope(owner) {
                stopped.complete(Unit)
                release.await()
                finished.complete("written and restarted")
            }
        }
        stopped.await()
        caller.cancelAndJoin()
        release.complete(Unit)

        assertEquals("written and restarted", withTimeout(2_000) { finished.await() })
    }

    @Test
    fun `a caller that stays gets the result`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        assertEquals(7, finishInOwnerScope(owner) { 7 })
    }
}
