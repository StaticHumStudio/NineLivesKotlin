package com.ninelivesaudio.app.service.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WorkManager reports a cancelled drain as stopped long before its engine has
 * unwound. A read stuck on a stalled server can hold on for the 60 second read
 * timeout, and its cleanup then deletes the `.part` file a replacement drain is
 * writing. Engine runs are serialized, so a replacement only starts once the
 * old run has fully exited, cleanup included.
 */
class EngineExclusiveRunTest {

    @Test
    fun `a replacement run waits for the cancelled run's cleanup`() = runBlocking {
        val lock = Mutex()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val oldStarted = CompletableDeferred<Unit>()
        val oldCleanedUp = AtomicBoolean(false)

        val old = scope.launch {
            runEngineExclusively(lock, onStart = {}, onStop = {}) {
                oldStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    // The stalled read finally gives up and deletes its .part.
                    withContext(NonCancellable) { delay(200) }
                    oldCleanedUp.set(true)
                }
            }
        }
        oldStarted.await()
        // Cancellation is recorded at once, the old run is still unwinding.
        old.cancel()

        val sawCleanupFirst = withTimeout(5_000) {
            scope.async {
                runEngineExclusively(lock, onStart = {}, onStop = {}) { oldCleanedUp.get() }
            }.await()
        }

        assertTrue("the replacement started while the old run was still cleaning up", sawCleanupFirst)
    }

    @Test
    fun `start and stop bracket the run, even when it throws`() = runBlocking {
        val events = mutableListOf<String>()
        runCatching {
            runEngineExclusively(Mutex(), onStart = { events += "start" }, onStop = { events += "stop" }) {
                events += "run"
                error("boom")
            }
        }
        assertEquals(listOf("start", "run", "stop"), events)
    }
}
