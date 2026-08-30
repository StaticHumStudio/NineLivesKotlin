package com.ninelivesaudio.app.entitlement

import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class TrialReminderTest {

    private val start = TimeUnit.DAYS.toMillis(20_000)

    @Test
    fun `the reminder uses one unique replaceable work name`() {
        val plan = TrialReminderPlan.forStart(startedAtEpochMs = start, nowEpochMs = start)

        assertEquals("trial-three-days-remaining", plan.uniqueWorkName)
        assertEquals(ExistingWorkPolicy.REPLACE, plan.existingWorkPolicy)
    }

    @Test
    fun `the reminder target is eleven days after trial start`() {
        val plan = TrialReminderPlan.forStart(startedAtEpochMs = start, nowEpochMs = start)

        assertEquals(start + TimeUnit.DAYS.toMillis(11), plan.targetAtEpochMs)
        assertEquals(TimeUnit.DAYS.toMillis(11), plan.initialDelayMs)
    }

    @Test
    fun `late scheduling never creates a negative delay`() {
        val now = start + TimeUnit.DAYS.toMillis(12)

        assertEquals(0L, TrialReminderPlan.forStart(start, now).initialDelayMs)
    }

    @Test
    fun `only a freshly resolved active trial may post the reminder`() = runBlocking {
        suspend fun decisionFor(state: EntitlementState): TrialReminderDecision =
            trialReminderDecision(
                refreshPlayOwnership = { RefreshPurchasesResult.SUCCEEDED },
                currentState = { state },
            )

        assertEquals(
            TrialReminderDecision.POST,
            decisionFor(
                EntitlementState(
                    isUnlocked = true,
                    source = EntitlementSource.TRIAL,
                    trialDaysRemaining = 3,
                )
            )
        )
        assertEquals(
            TrialReminderDecision.SKIP,
            decisionFor(
                EntitlementState(isUnlocked = true, source = EntitlementSource.PLAY_UNLOCK)
            )
        )
        assertEquals(
            TrialReminderDecision.SKIP,
            decisionFor(
                EntitlementState(isUnlocked = true, source = EntitlementSource.LEGACY_PAID)
            )
        )
        assertEquals(TrialReminderDecision.SKIP, decisionFor(EntitlementState.FREE))
    }

    @Test
    fun `a cached trial posts nothing when a completed refresh could not confirm ownership`() =
        runBlocking {
            val cachedTrial = EntitlementState(
                isUnlocked = true,
                source = EntitlementSource.TRIAL,
                trialDaysRemaining = 3,
            )
            var stateReads = 0

            assertEquals(
                TrialReminderDecision.SKIP,
                trialReminderDecision(
                    refreshPlayOwnership = { RefreshPurchasesResult.FAILED },
                    currentState = {
                        stateReads += 1
                        cachedTrial
                    },
                )
            )
            assertEquals(0, stateReads)
        }

    /**
     * The exact bug: a refresh that could not even run, because the sequencer
     * was already busy with another one, must not be read as "no ownership" and
     * silently consume the one-shot reminder. It has to come back as something
     * the worker can retry.
     */
    @Test
    fun `a busy sequencer produces a retry decision, not a skip`() = runBlocking {
        var stateReads = 0

        assertEquals(
            TrialReminderDecision.RETRY,
            trialReminderDecision(
                refreshPlayOwnership = { RefreshPurchasesResult.BUSY },
                currentState = {
                    stateReads += 1
                    EntitlementState(isUnlocked = true, source = EntitlementSource.TRIAL)
                },
            )
        )
        // A busy sequencer has no answer at all. Reading current state anyway
        // would just be reading stale entitlement, not a real check.
        assertEquals(0, stateReads)
    }

    @Test
    fun `shouldRetry allows the busy path to retry until its cap`() {
        assertTrue(shouldRetry(TrialReminderDecision.RETRY, runAttemptCount = 0))
        assertTrue(shouldRetry(TrialReminderDecision.RETRY, runAttemptCount = MAX_BUSY_REMINDER_RETRIES - 1))
        assertFalse(shouldRetry(TrialReminderDecision.RETRY, runAttemptCount = MAX_BUSY_REMINDER_RETRIES))
        assertFalse(shouldRetry(TrialReminderDecision.RETRY, runAttemptCount = MAX_BUSY_REMINDER_RETRIES + 5))
    }

    /**
     * A genuine failure (bad response, timed-out query) is a completed answer,
     * not an in-flight one. It must never retry, no matter how many times
     * WorkManager has already tried, or a flaky network turns one missed
     * refresh into an unbounded retry loop.
     */
    @Test
    fun `shouldRetry never retries a skip, at any attempt count`() {
        assertFalse(shouldRetry(TrialReminderDecision.SKIP, runAttemptCount = 0))
        assertFalse(shouldRetry(TrialReminderDecision.SKIP, runAttemptCount = 100))
        assertFalse(shouldRetry(TrialReminderDecision.POST, runAttemptCount = 0))
    }

    /**
     * End to end at the decision layer: the sequencer is busy on the first two
     * attempts and then clears, and only the attempt that actually completes a
     * refresh may ever produce POST. Once POST fires the simulated worker run
     * stops, exactly like a real one-time work request that returns success.
     */
    @Test
    fun `a retry-then-succeed sequence posts exactly once`() = runBlocking {
        val outcomes = listOf(
            RefreshPurchasesResult.BUSY,
            RefreshPurchasesResult.BUSY,
            RefreshPurchasesResult.SUCCEEDED,
        ).iterator()
        val decisions = mutableListOf<TrialReminderDecision>()
        var attempt = 0

        while (true) {
            val decision = trialReminderDecision(
                refreshPlayOwnership = { outcomes.next() },
                currentState = {
                    EntitlementState(isUnlocked = true, source = EntitlementSource.TRIAL)
                },
            )
            decisions += decision
            if (decision != TrialReminderDecision.RETRY || !shouldRetry(decision, attempt)) break
            attempt += 1
        }

        assertEquals(
            listOf(TrialReminderDecision.RETRY, TrialReminderDecision.RETRY, TrialReminderDecision.POST),
            decisions,
        )
        assertEquals(1, decisions.count { it == TrialReminderDecision.POST })
    }

    @Test
    fun `the worker decision refreshes Play before reading current entitlement`() = runBlocking {
        val calls = mutableListOf<String>()
        var current = EntitlementState(
            isUnlocked = true,
            source = EntitlementSource.TRIAL,
            trialDaysRemaining = 3,
        )

        val decision = trialReminderDecision(
            refreshPlayOwnership = {
                calls += "refresh"
                current = EntitlementState(
                    isUnlocked = true,
                    source = EntitlementSource.PLAY_UNLOCK,
                )
                RefreshPurchasesResult.SUCCEEDED
            },
            currentState = {
                calls += "state"
                current
            },
        )

        assertEquals(TrialReminderDecision.SKIP, decision)
        assertEquals(listOf("refresh", "state"), calls)
    }

    @Test
    fun `worker cancellation during purchase cleanup never reaches the post decision`() = runBlocking {
        var stateReads = 0
        var cancellationPropagated = false

        try {
            trialReminderDecision(
                refreshPlayOwnership = { throw CancellationException("unique work cancelled") },
                currentState = {
                    stateReads += 1
                    EntitlementState.FREE
                },
            )
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }

        assertTrue(cancellationPropagated)
        assertEquals(0, stateReads)
    }
}
