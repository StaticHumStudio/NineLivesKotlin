package com.ninelivesaudio.app.entitlement

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class BillingEntitlementSequencerTest {

    @Test
    fun `a purchase grant waiting behind an older empty query lands last`() = runBlocking {
        val sequencer = BillingEntitlementSequencer()
        val finishOldRefresh = CompletableDeferred<Unit>()
        val applied = mutableListOf<Boolean>()

        val oldRefresh = launch(start = CoroutineStart.UNDISPATCHED) {
            sequencer.runRefreshIfIdle {
                finishOldRefresh.await()
                applied += false
                true
            }
        }
        val purchaseGrant = launch(start = CoroutineStart.UNDISPATCHED) {
            sequencer.runPurchaseGrant {
                applied += true
            }
        }

        finishOldRefresh.complete(Unit)
        joinAll(oldRefresh, purchaseGrant)

        assertEquals(listOf(false, true), applied)
    }

    @Test
    fun `a refresh started after the purchase grant may revoke`() = runBlocking {
        val sequencer = BillingEntitlementSequencer()
        var unlocked = false

        sequencer.runPurchaseGrant { unlocked = true }
        sequencer.runRefreshIfIdle {
            unlocked = false
            true
        }

        assertFalse(unlocked)
    }

    @Test
    fun `a purchase callback received during reminder refresh lands before its state read`() =
        runBlocking {
            val sequencer = BillingEntitlementSequencer()
            val finishWorkerRefresh = CompletableDeferred<Unit>()
            val delayedDispatcher = QueueingDispatcher()
            val callbackJob = SupervisorJob()
            val callbackScope = CoroutineScope(callbackJob + delayedDispatcher)
            var current = EntitlementState(
                isUnlocked = true,
                source = EntitlementSource.TRIAL,
                trialDaysRemaining = 3,
            )

            val workerDecision = async(start = CoroutineStart.UNDISPATCHED) {
                val playOwnershipResolved = sequencer.runRefreshIfIdle {
                    finishWorkerRefresh.await()
                    true
                } ?: false
                shouldPostTrialReminder(
                    refreshPlayOwnership = { playOwnershipResolved },
                    currentState = {
                        sequencer.runAfterPendingUpdates { current }
                    },
                )
            }
            val purchaseGrant = sequencer.launchPurchaseGrant(callbackScope) {
                current = EntitlementState(
                    isUnlocked = true,
                    source = EntitlementSource.PLAY_UNLOCK,
                )
            }

            finishWorkerRefresh.complete(Unit)
            yield()
            delayedDispatcher.runAll()

            assertFalse(workerDecision.await())
            purchaseGrant.join()
            callbackJob.cancel()
        }
}

private class QueueingDispatcher : CoroutineDispatcher() {
    private val queued = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queued.addLast(block)
    }

    fun runAll() {
        while (queued.isNotEmpty()) {
            queued.removeFirst().run()
        }
    }
}
