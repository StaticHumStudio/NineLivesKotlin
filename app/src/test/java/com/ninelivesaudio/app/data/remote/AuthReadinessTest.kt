package com.ninelivesaudio.app.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AuthReadinessTest {

    @Test
    fun `Auto waits until secure token restoration finishes`() = runBlocking {
        val readiness = AuthReadiness()
        val releaseInitialization = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        var initializationCount = 0
        val firstWaiter = async {
            readiness.awaitOrInitialize {
                initializationCount++
                releaseInitialization.await()
            }
            returned.complete(Unit)
        }
        val secondWaiter = async {
            readiness.awaitOrInitialize { initializationCount++ }
        }

        yield()
        assertFalse(returned.isCompleted)

        releaseInitialization.complete(Unit)
        firstWaiter.await()
        secondWaiter.await()
        assertTrue(returned.isCompleted)

        readiness.awaitOrInitialize { initializationCount++ }
        assertEquals(1, initializationCount)
    }

    @Test
    fun `failed secure token restoration can retry`() = runBlocking {
        val readiness = AuthReadiness()
        var attempts = 0

        try {
            readiness.awaitOrInitialize {
                attempts++
                error("secure storage unavailable")
            }
            fail("Expected initialization failure")
        } catch (_: IllegalStateException) {
            // Expected. The readiness gate must remain open for a retry.
        }

        readiness.awaitOrInitialize { attempts++ }
        assertEquals(2, attempts)
    }
}
