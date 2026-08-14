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
                true
            }
            returned.complete(Unit)
        }
        val secondWaiter = async {
            readiness.awaitOrInitialize {
                initializationCount++
                true
            }
        }

        yield()
        assertFalse(returned.isCompleted)

        releaseInitialization.complete(Unit)
        firstWaiter.await()
        secondWaiter.await()
        assertTrue(returned.isCompleted)

        readiness.awaitOrInitialize {
            initializationCount++
            true
        }
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

        readiness.awaitOrInitialize {
            attempts++
            true
        }
        assertEquals(2, attempts)
    }

    @Test
    fun `degraded restoration keeps the gate open until storage recovers`() = runBlocking {
        val readiness = AuthReadiness()
        var attempts = 0

        // Storage unavailable: the initializer completes without throwing but
        // reports a degraded restore. The gate must NOT latch, or the
        // interceptor stays tokenless forever while a valid token sits in
        // recovered storage and the next validation 401s it away.
        readiness.awaitOrInitialize {
            attempts++
            false
        }
        readiness.awaitOrInitialize {
            attempts++
            false
        }
        assertEquals(2, attempts)

        // Storage recovered: a successful restore latches, later calls no-op.
        readiness.awaitOrInitialize {
            attempts++
            true
        }
        readiness.awaitOrInitialize {
            attempts++
            true
        }
        assertEquals(3, attempts)
    }
}
