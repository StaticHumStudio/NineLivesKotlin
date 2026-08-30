package com.ninelivesaudio.app.entitlement

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class EntitlementRepositoryTest {

    private val start = TimeUnit.DAYS.toMillis(20_000)

    @Test
    fun `starting a trial durably consumes it before granting TRIAL`() = runBlocking {
        val durable = FakeDurableEntitlementStore()
        val cache = FakePlayEntitlementCache()
        val reminders = FakeTrialReminderScheduler()
        val repository = repository(durable, cache, reminders, start)

        assertTrue(repository.startTrial())

        assertTrue(durable.trialConsumed)
        assertEquals(start, durable.trialStartedAtEpochMs)
        assertEquals(EntitlementSource.TRIAL, repository.current.source)
        assertEquals(listOf(start), reminders.starts)
    }

    @Test
    fun `a second start cannot move the original clock or schedule again`() = runBlocking {
        val durable = FakeDurableEntitlementStore()
        val reminders = FakeTrialReminderScheduler()
        var now = start
        val repository = repository(durable, FakePlayEntitlementCache(), reminders) { now }
        assertTrue(repository.startTrial())

        now += TimeUnit.DAYS.toMillis(2)

        assertFalse(repository.startTrial())
        assertEquals(start, durable.trialStartedAtEpochMs)
        assertEquals(listOf(start), reminders.starts)
    }

    @Test
    fun `a failed durable write cannot grant or schedule the trial`() = runBlocking {
        val durable = FakeDurableEntitlementStore(commitSucceeds = false)
        val reminders = FakeTrialReminderScheduler()
        val repository = repository(durable, FakePlayEntitlementCache(), reminders, start)

        assertFalse(repository.startTrial())

        assertEquals(EntitlementState.FREE.copy(trialOfferAvailable = true), repository.current)
        assertFalse(durable.trialConsumed)
        assertNull(durable.trialStartedAtEpochMs)
        assertTrue(reminders.starts.isEmpty())
    }

    @Test
    fun `reminder scheduling failure leaves the persisted trial active`() = runBlocking {
        val durable = FakeDurableEntitlementStore()
        val repository = repository(
            durable,
            FakePlayEntitlementCache(),
            TrialReminderScheduler { error("WorkManager unavailable") },
            start,
        )

        assertTrue(repository.startTrial())

        assertTrue(durable.trialConsumed)
        assertEquals(start, durable.trialStartedAtEpochMs)
        assertEquals(EntitlementSource.TRIAL, repository.current.source)
    }

    @Test
    fun `trial start never writes legacy or Play entitlement fields`() = runBlocking {
        val durable = FakeDurableEntitlementStore(legacyPaid = false)
        val cache = FakePlayEntitlementCache(playUnlockCached = false)
        val repository = repository(
            durable,
            cache,
            FakeTrialReminderScheduler(),
            start,
        )

        assertTrue(repository.startTrial())

        assertFalse(durable.legacyPaid)
        assertFalse(cache.playUnlockCached)
        assertEquals(1, durable.consumeCalls)
    }

    @Test
    fun `a purchase during trial wins without touching trial fields`() = runBlocking {
        val durable = FakeDurableEntitlementStore(
            trialStartedAtEpochMs = start,
            trialConsumed = true,
        )
        val cache = FakePlayEntitlementCache()
        val repository = repository(
            durable,
            cache,
            FakeTrialReminderScheduler(),
            start,
        )

        repository.applyPlayUnlock(true)

        assertEquals(EntitlementSource.PLAY_UNLOCK, repository.current.source)
        assertEquals(start, durable.trialStartedAtEpochMs)
        assertTrue(durable.trialConsumed)
        assertEquals(0, durable.consumeCalls)
    }

    @Test
    fun `restore cannot regrant a consumed expired trial`() = runBlocking {
        val durable = FakeDurableEntitlementStore(
            trialStartedAtEpochMs = start,
            trialConsumed = true,
        )
        val repository = repository(
            durable,
            FakePlayEntitlementCache(),
            FakeTrialReminderScheduler(),
            start + TimeUnit.DAYS.toMillis(14),
        )

        repository.applyPlayUnlock(false)

        assertEquals(EntitlementState.FREE, repository.current)
        assertFalse(repository.startTrial())
        assertEquals(start, durable.trialStartedAtEpochMs)
        assertTrue(durable.trialConsumed)
    }

    @Test
    fun `trial expiry emits the same unlocked to free transition as a refund`() = runBlocking {
        var trialNow = start
        val trialRepository = repository(
            FakeDurableEntitlementStore(
                trialStartedAtEpochMs = start,
                trialConsumed = true,
            ),
            FakePlayEntitlementCache(),
            FakeTrialReminderScheduler(),
        ) { trialNow }
        val trialTransition = mutableListOf(trialRepository.current.isUnlocked)

        trialNow += TimeUnit.DAYS.toMillis(14)
        trialRepository.refresh()
        trialTransition += trialRepository.current.isUnlocked

        val refundRepository = repository(
            FakeDurableEntitlementStore(trialConsumed = true),
            FakePlayEntitlementCache(playUnlockCached = true),
            FakeTrialReminderScheduler(),
            start,
        )
        val refundTransition = mutableListOf(refundRepository.current.isUnlocked)
        refundRepository.applyPlayUnlock(false)
        refundTransition += refundRepository.current.isUnlocked

        assertEquals(listOf(true, false), trialTransition)
        assertEquals(refundTransition, trialTransition)
    }

    private fun repository(
        durable: DurableEntitlementStore,
        cache: PlayEntitlementCache,
        reminders: TrialReminderScheduler,
        now: Long,
    ): EntitlementRepository = repository(durable, cache, reminders) { now }

    private fun repository(
        durable: DurableEntitlementStore,
        cache: PlayEntitlementCache,
        reminders: TrialReminderScheduler,
        now: () -> Long,
    ): EntitlementRepository = EntitlementRepository(
        prefs = durable,
        cache = cache,
        reminderScheduler = reminders,
        nowEpochMs = now,
    )
}

private class FakeDurableEntitlementStore(
    override var legacyPaid: Boolean = false,
    override var trialStartedAtEpochMs: Long? = null,
    override var trialConsumed: Boolean = false,
    private val commitSucceeds: Boolean = true,
) : DurableEntitlementStore {
    var consumeCalls = 0

    override fun consumeTrial(startedAtEpochMs: Long): Boolean {
        consumeCalls += 1
        if (!commitSucceeds) return false
        trialConsumed = true
        trialStartedAtEpochMs = startedAtEpochMs
        return true
    }
}

private class FakePlayEntitlementCache(
    override var playUnlockCached: Boolean = false,
    override var forceFree: Boolean = false,
) : PlayEntitlementCache

private class FakeTrialReminderScheduler : TrialReminderScheduler {
    val starts = mutableListOf<Long>()

    override fun schedule(startedAtEpochMs: Long) {
        starts += startedAtEpochMs
    }
}
