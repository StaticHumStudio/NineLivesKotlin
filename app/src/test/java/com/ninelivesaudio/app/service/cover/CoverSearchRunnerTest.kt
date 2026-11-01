package com.ninelivesaudio.app.service.cover

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CoverSearchRunnerTest {

    private fun cand(key: String) = CoverCandidate(CoverSourceId.OPEN_LIBRARY, key, "t/$key", "u/$key", null, null)

    private fun fake(block: suspend (String) -> List<CoverCandidate>) = object : CoverSearchSource {
        override val id = CoverSourceId.OPEN_LIBRARY
        override suspend fun search(query: String) = block(query)
    }

    private val thumbOk: suspend (CoverCandidate) -> CoverThumb? = { CoverThumb(ByteArray(1), 318, 500) }

    @Test fun `a source that throws drops out silently`() = runBlocking {
        val out = runCoverSearch("q", listOf(fake { throw IOException("down") }, fake { listOf(cand("a"), cand("b")) }), thumbOk)
        assertEquals(CoverSearchPhase.RESULTS, out.phase)
        assertEquals(listOf("a", "b"), out.results.map { it.candidate.key })
    }

    @Test(timeout = 5_000) fun `a source that never answers is dropped at its timeout`() = runBlocking {
        val never = CompletableDeferred<List<CoverCandidate>>()
        val start = System.nanoTime()
        val out = runCoverSearch("q", listOf(fake { never.await() }, fake { listOf(cand("a")) }), thumbOk, sourceTimeout = 100.milliseconds)
        assertTrue((System.nanoTime() - start) < 1_000_000_000L)
        assertEquals(CoverSearchPhase.RESULTS, out.phase)
        assertEquals(listOf("a"), out.results.map { it.candidate.key })
    }

    @Test fun `every source failing reads as unreachable`() = runBlocking {
        val out = runCoverSearch("q", listOf(fake { throw IOException("down") }), thumbOk)
        assertEquals(CoverSearchPhase.UNREACHABLE, out.phase)
        assertTrue(out.results.isEmpty())
    }

    @Test fun `a source that answers with nothing reads as no matches`() = runBlocking {
        assertEquals(CoverSearchPhase.NO_MATCHES, runCoverSearch("q", listOf(fake { emptyList() }), thumbOk).phase)
    }

    @Test fun `a thumbnail that fails drops only that cover`() = runBlocking {
        val out = runCoverSearch("q", listOf(fake { listOf(cand("a"), cand("b"), cand("c")) }), { c ->
            when (c.key) {
                "b" -> throw IOException("404")
                "c" -> null
                else -> CoverThumb(ByteArray(1), 318, 500)
            }
        })
        assertEquals(listOf("a"), out.results.map { it.candidate.key })
    }

    @Test(timeout = 5_000) fun `a thumbnail that never loads is dropped at its timeout`() = runBlocking {
        val never = CompletableDeferred<CoverThumb?>()
        val out = runCoverSearch("q", listOf(fake { listOf(cand("a"), cand("b")) }), { c ->
            if (c.key == "b") never.await() else CoverThumb(ByteArray(1), 318, 500)
        }, thumbnailTimeout = 100.milliseconds)
        assertEquals(listOf("a"), out.results.map { it.candidate.key })
    }

    @Test fun `square thumbnails come first`() = runBlocking {
        val out = runCoverSearch("q", listOf(fake { listOf(cand("a"), cand("b")) }), { c ->
            if (c.key == "a") CoverThumb(ByteArray(1), 318, 500) else CoverThumb(ByteArray(1), 500, 500)
        })
        assertEquals(listOf("b", "a"), out.results.map { it.candidate.key })
    }

    @Test fun `cancelling the search cancels the requests`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val sawCancel = CompletableDeferred<Boolean>()
        val never = CompletableDeferred<List<CoverCandidate>>()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            runCoverSearch("q", listOf(fake {
                started.complete(Unit)
                try {
                    never.await()
                } catch (e: CancellationException) {
                    sawCancel.complete(true)
                    throw e
                }
            }), thumbOk, sourceTimeout = 30.seconds)
        }
        started.await()
        job.cancel()
        assertTrue(withTimeout(2_000) { sawCancel.await() })
    }
}
