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
        suspend fun decisionFor(state: EntitlementState): Boolean = shouldPostTrialReminder(
            refreshPlayOwnership = { true },
            currentState = { state },
        )

        assertTrue(
            decisionFor(
                EntitlementState(
                    isUnlocked = true,
                    source = EntitlementSource.TRIAL,
                    trialDaysRemaining = 3,
                )
            )
        )
        assertFalse(
            decisionFor(
                EntitlementState(isUnlocked = true, source = EntitlementSource.PLAY_UNLOCK)
            )
        )
        assertFalse(
            decisionFor(
                EntitlementState(isUnlocked = true, source = EntitlementSource.LEGACY_PAID)
            )
        )
        assertFalse(decisionFor(EntitlementState.FREE))
    }

    @Test
    fun `a cached trial posts nothing when Play ownership could not be re-resolved`() = runBlocking {
        val cachedTrial = EntitlementState(
            isUnlocked = true,
            source = EntitlementSource.TRIAL,
            trialDaysRemaining = 3,
        )
        var stateReads = 0

        assertFalse(
            shouldPostTrialReminder(
                refreshPlayOwnership = { false },
                currentState = {
                    stateReads += 1
                    cachedTrial
                },
            )
        )
        assertEquals(0, stateReads)
    }

    @Test
    fun `the worker decision refreshes Play before reading current entitlement`() = runBlocking {
        val calls = mutableListOf<String>()
        var current = EntitlementState(
            isUnlocked = true,
            source = EntitlementSource.TRIAL,
            trialDaysRemaining = 3,
        )

        val shouldPost = shouldPostTrialReminder(
            refreshPlayOwnership = {
                calls += "refresh"
                current = EntitlementState(
                    isUnlocked = true,
                    source = EntitlementSource.PLAY_UNLOCK,
                )
                true
            },
            currentState = {
                calls += "state"
                current
            },
        )

        assertFalse(shouldPost)
        assertEquals(listOf("refresh", "state"), calls)
    }

    @Test
    fun `worker cancellation during purchase cleanup never reaches the post decision`() = runBlocking {
        var stateReads = 0
        var cancellationPropagated = false

        try {
            shouldPostTrialReminder(
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
