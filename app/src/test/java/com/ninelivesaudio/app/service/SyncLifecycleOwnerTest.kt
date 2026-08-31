package com.ninelivesaudio.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

class SyncLifecycleOwnerTest {

    @Test
    fun `restart and stop cancel every child owned by the prior start`() = runBlocking {
        val owner = SyncLifecycleOwner(this)
        val firstStarted = List(3) { CompletableDeferred<Unit>() }
        val firstCancelled = List(3) { CompletableDeferred<Unit>() }

        owner.restart {
            firstStarted.indices.forEach { index ->
                launch {
                    try {
                        firstStarted[index].complete(Unit)
                        awaitCancellation()
                    } finally {
                        firstCancelled[index].complete(Unit)
                    }
                }
            }
        }
        firstStarted.forEach { it.await() }

        val secondStarted = List(3) { CompletableDeferred<Unit>() }
        val secondCancelled = List(3) { CompletableDeferred<Unit>() }
        owner.restart {
            secondStarted.indices.forEach { index ->
                launch {
                    try {
                        secondStarted[index].complete(Unit)
                        awaitCancellation()
                    } finally {
                        secondCancelled[index].complete(Unit)
                    }
                }
            }
        }

        withTimeout(1_000) {
            firstCancelled.forEach { it.await() }
            secondStarted.forEach { it.await() }
        }
        assertTrue(firstCancelled.all { it.isCompleted })

        owner.stop()

        withTimeout(1_000) {
            secondCancelled.forEach { it.await() }
        }
        assertTrue(secondCancelled.all { it.isCompleted })
    }

    @Test
    fun `one lifecycle child failure does not cancel its siblings`() = runBlocking {
        val failure = CompletableDeferred<Throwable>()
        val testScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
                failure.complete(throwable)
            },
        )
        val owner = SyncLifecycleOwner(testScope)
        val siblingStarted = CompletableDeferred<Unit>()
        val siblingCancelled = CompletableDeferred<Unit>()

        owner.restart {
            launch {
                try {
                    siblingStarted.complete(Unit)
                    awaitCancellation()
                } finally {
                    siblingCancelled.complete(Unit)
                }
            }
            launch {
                siblingStarted.await()
                error("expected child failure")
            }
        }

        withTimeout(1_000) { failure.await() }
        assertFalse(siblingCancelled.isCompleted)

        owner.stop()
        withTimeout(1_000) { siblingCancelled.await() }
        testScope.cancel()
    }

    @Test
    fun `simultaneous restarts are all cancelled by the later stop`() = runBlocking {
        val callerCount = 16
        val barrier = CyclicBarrier(callerCount)
        val executor = Executors.newFixedThreadPool(callerCount)
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = SyncLifecycleOwner(testScope)

        try {
            val futures = List(callerCount) {
                executor.submit<Job> {
                    barrier.await(5, TimeUnit.SECONDS)
                    owner.restart { awaitCancellation() }
                }
            }
            val jobs = futures.map { it.get(5, TimeUnit.SECONDS) }

            owner.stop()

            withTimeout(1_000) { jobs.joinAll() }
            assertTrue(jobs.all { it.isCompleted })
        } finally {
            owner.stop()
            testScope.cancel()
            executor.shutdownNow()
        }
    }

    @Test
    fun `stop overlapping restart waits and cancels the new owner`() = runBlocking {
        val restartInsideLock = CountDownLatch(1)
        val releaseRestart = CountDownLatch(1)
        val stopCalled = CountDownLatch(1)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                restartInsideLock.countDown()
                check(releaseRestart.await(5, TimeUnit.SECONDS))
                Dispatchers.Default.dispatch(context, block)
            }
        }
        val testScope = CoroutineScope(SupervisorJob() + dispatcher)
        val owner = SyncLifecycleOwner(testScope)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val restartFuture = executor.submit<Job> {
                owner.restart { awaitCancellation() }
            }
            assertTrue(restartInsideLock.await(5, TimeUnit.SECONDS))

            val stopFuture = executor.submit<Unit> {
                stopCalled.countDown()
                owner.stop()
            }
            assertTrue(stopCalled.await(5, TimeUnit.SECONDS))
            assertFalse(stopFuture.isDone)

            releaseRestart.countDown()
            val job = restartFuture.get(5, TimeUnit.SECONDS)
            stopFuture.get(5, TimeUnit.SECONDS)

            withTimeout(1_000) { job.join() }
            assertTrue(job.isCompleted)
        } finally {
            releaseRestart.countDown()
            owner.stop()
            testScope.cancel()
            executor.shutdownNow()
        }
    }
}
