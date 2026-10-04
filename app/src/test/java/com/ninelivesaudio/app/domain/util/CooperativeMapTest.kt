package com.ninelivesaudio.app.domain.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.coroutineContext

/** A cancelled shelf build stops decoding instead of running to the end. */
class CooperativeMapTest {

    @Test
    fun `maps every item when nothing cancels`() = runBlocking {
        val out = (1..1_000).toList().mapCooperatively { it * 2 }
        assertEquals((1..1_000).map { it * 2 }, out)
    }

    @Test
    fun `a cancel mid-loop stops at the next check`() = runBlocking {
        var mapped = 0
        val build = async(Dispatchers.Default) {
            (0 until 10_000).toList().mapCooperatively { item ->
                if (item == 10) coroutineContext.job.cancel()
                mapped++
                item
            }
        }
        val error = runCatching { build.await() }.exceptionOrNull()

        assertTrue(error is CancellationException)
        // Items 0 to 255 run, the check before item 256 throws.
        assertEquals(COOPERATIVE_CHECK_EVERY, mapped)
    }
}
