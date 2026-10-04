package com.ninelivesaudio.app.ui.dossier

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Switching periods cancels the old load, but cancelling does not stop the
 * CPU work it is already doing. The old load used to finish its totals and
 * publish them over the newer report, with the newer period still selected.
 */
class DossierSupersededLoadTest {

    private val executor = Executors.newSingleThreadExecutor()
    private val aggregation = executor.asCoroutineDispatcher()

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun `a load superseded while aggregating never publishes`() = runBlocking {
        var newestLoad = 1L
        val published = Collections.synchronizedList(mutableListOf<String>())
        val aggregating = CountDownLatch(1)
        val finishAggregating = CountDownLatch(1)

        val oldLoad = launch(start = CoroutineStart.UNDISPATCHED) {
            val mine = 1L
            publishIfStillCurrent(
                dispatcher = aggregation,
                isCurrent = { mine == newestLoad },
                publish = { published += it },
            ) {
                aggregating.countDown()
                finishAggregating.await()
                "30 day totals"
            }
        }
        assertTrue(aggregating.await(10, TimeUnit.SECONDS))

        // What a period switch does: a newer load takes over and the old job
        // is cancelled while its aggregation is still running.
        newestLoad = 2L
        oldLoad.cancel()
        finishAggregating.countDown()
        oldLoad.join()
        withContext(aggregation) {}

        assertEquals(emptyList<String>(), published.toList())
    }

    @Test
    fun `a load superseded without being cancelled still never publishes`() = runBlocking {
        var newestLoad = 1L
        val published = mutableListOf<String>()

        publishIfStillCurrent(
            dispatcher = aggregation,
            isCurrent = { newestLoad == 1L },
            publish = { published += it },
        ) {
            newestLoad = 2L
            "30 day totals"
        }

        assertEquals(emptyList<String>(), published)
    }

    @Test
    fun `the newest load publishes its report`() = runBlocking {
        val published = mutableListOf<String>()

        publishIfStillCurrent(
            dispatcher = aggregation,
            isCurrent = { true },
            publish = { published += it },
        ) { "all time totals" }

        assertEquals(listOf("all time totals"), published)
    }
}
