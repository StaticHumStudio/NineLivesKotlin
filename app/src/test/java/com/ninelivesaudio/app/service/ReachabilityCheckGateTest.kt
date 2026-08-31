package com.ninelivesaudio.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReachabilityCheckGateTest {

    @Test
    fun `concurrent reachability checks run one at a time`() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        var invocation = 0
        val gate = ReachabilityCheckGate {
            invocation += 1
            if (invocation == 1) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            } else {
                secondEntered.complete(Unit)
            }
            true
        }

        val first = async(start = CoroutineStart.UNDISPATCHED) { gate.run() }
        firstEntered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { gate.run() }

        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        assertTrue(first.await())
        assertTrue(second.await())
        assertTrue(secondEntered.isCompleted)
    }
}
