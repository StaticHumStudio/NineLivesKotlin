package com.ninelivesaudio.app.entitlement

import com.android.billingclient.api.BillingClient.BillingResponseCode as Code
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class PurchaseAcknowledgerTest {

    private class Script(private val codes: List<Int?>) {
        var calls = 0
        val sleeps = mutableListOf<Long>()
        suspend fun once(): Int? = codes[minOf(calls++, codes.size - 1)]
        suspend fun sleep(ms: Long) { sleeps += ms }
    }

    private fun run(vararg codes: Int?): Pair<Int?, Script> {
        val s = Script(codes.toList())
        val r = runBlocking { acknowledgeWithRetry(s::once, sleep = s::sleep) }
        return r to s
    }

    @Test fun `OK on the first attempt never waits`() {
        val (r, s) = run(Code.OK)
        assertEquals(Code.OK, r); assertEquals(1, s.calls); assertEquals(emptyList<Long>(), s.sleeps)
    }

    @Test fun `a retryable failure is tried again after each delay until OK`() {
        val (r, s) = run(Code.ERROR, Code.NETWORK_ERROR, Code.OK)
        assertEquals(Code.OK, r); assertEquals(3, s.calls); assertEquals(listOf(5_000L, 30_000L), s.sleeps)
    }

    @Test fun `retries stop after the last delay`() {
        val (r, s) = run(Code.SERVICE_UNAVAILABLE)
        assertEquals(Code.SERVICE_UNAVAILABLE, r); assertEquals(4, s.calls); assertEquals(ACK_RETRY_DELAYS_MS, s.sleeps)
    }

    @Test fun `a call that threw or timed out is retried`() {
        val (r, s) = run(null, Code.OK)
        assertEquals(Code.OK, r); assertEquals(2, s.calls)
    }

    @Test fun `a final answer from Play is not retried`() {
        for (code in listOf(Code.DEVELOPER_ERROR, Code.ITEM_NOT_OWNED, Code.ITEM_ALREADY_OWNED)) {
            val (r, s) = run(code)
            assertEquals(code, r); assertEquals(1, s.calls)
        }
    }

    @Test fun `cancellation is never retried`() {
        var calls = 0
        var cancelled = false
        try {
            runBlocking {
                acknowledgeWithRetry({ calls++; throw CancellationException("stop") }, sleep = { })
            }
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled); assertEquals(1, calls)
    }

    private fun scope() = CoroutineScope(Dispatchers.Unconfined + Job())

    @Test fun `a token already in flight is not started twice`() {
        val gate = CompletableDeferred<Int?>()
        var calls = 0
        val ack = PurchaseAcknowledger(scope(), { calls++; gate.await() }, sleep = { })
        val first = ack.acknowledge(listOf("t"))
        val second = ack.acknowledge(listOf("t"))
        assertEquals(1, first.size); assertEquals(0, second.size); assertEquals(1, calls)
        gate.complete(Code.OK)
        runBlocking { first.joinAll() }
    }

    @Test fun `the same token can be acknowledged again once its chain ends`() {
        var calls = 0
        val ack = PurchaseAcknowledger(scope(), { calls++; Code.OK }, sleep = { })
        runBlocking { ack.acknowledge(listOf("t")).joinAll() }
        runBlocking { ack.acknowledge(listOf("t")).joinAll() }
        assertEquals(2, calls)
    }

    @Test fun `two tokens are acknowledged independently`() {
        val seen = mutableListOf<String>()
        val ack = PurchaseAcknowledger(scope(), { seen += it; Code.OK }, sleep = { })
        val jobs = ack.acknowledge(listOf("a", "b"))
        runBlocking { jobs.joinAll() }
        assertEquals(2, jobs.size); assertEquals(listOf("a", "b"), seen.sorted())
    }
}
