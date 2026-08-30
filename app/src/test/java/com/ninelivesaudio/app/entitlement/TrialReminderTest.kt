package com.ninelivesaudio.app.entitlement

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

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
    fun `only TRIAL as the winning source may post the reminder`() {
        assertTrue(
            shouldPostTrialReminder(
                EntitlementState(
                    isUnlocked = true,
                    source = EntitlementSource.TRIAL,
                    trialDaysRemaining = 3,
                )
            )
        )
        assertFalse(
            shouldPostTrialReminder(
                EntitlementState(isUnlocked = true, source = EntitlementSource.PLAY_UNLOCK)
            )
        )
        assertFalse(
            shouldPostTrialReminder(
                EntitlementState(isUnlocked = true, source = EntitlementSource.LEGACY_PAID)
            )
        )
        assertFalse(shouldPostTrialReminder(EntitlementState.FREE))
    }
}
