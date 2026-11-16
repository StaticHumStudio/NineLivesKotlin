package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.local.entity.PendingProgressEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.domain.util.toEpochMillis
import com.ninelivesaudio.app.domain.util.toIso8601
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * #118 review: a book opened a server session online, the phone went into
 * airplane mode and kept playing, the app was killed, then relaunched online.
 * Every save during that play writes the saved row and the queue together
 * (the poll's saves and, now, the heartbeat's), and only a sync the server
 * took clears the queue. These replay that on the queue rules and check the
 * relaunch delivers the last spot played, dated when it was played.
 */
class AirplaneListeningTest {

    private val evening = 1_794_693_600_000L // 2026-11-14 22:00 UTC

    /** The saved row and queue of one book, as the DAO keeps them. */
    private class Store {
        var durable: PlaybackProgressEntity? = null
        var rows: List<PendingProgressEntity> = emptyList()
        private var nextId = 1L

        /** saveProgressAndEnqueue: the row replaces the book's queue. */
        fun saveAndQueue(position: Double, atMs: Long) {
            val ts = atMs.toIso8601()
            durable = PlaybackProgressEntity("item-1", position, 0, ts)
            rows = listOf(
                PendingProgressEntity(
                    id = nextId++,
                    itemId = "item-1",
                    currentTime = position,
                    duration = 36_000.0,
                    isAtomic = 1,
                    timestamp = ts,
                )
            )
        }

        /** A save that does not queue (what the heartbeat used to do). */
        fun saveOnly(position: Double, atMs: Long) {
            durable = PlaybackProgressEntity("item-1", position, 0, atMs.toIso8601())
        }

        /** The open session's sync of [position]: true when the server answered. */
        fun sessionSync(position: Double, serverAnswered: Boolean) {
            if (serverAnswered && sessionSyncConfirmsQueuedSpot(rows, position)) rows = emptyList()
        }
    }

    /** What the relaunch's delivery sends for the book. */
    private fun relaunchDelivery(store: Store): OfflineDeliveryPlan {
        val promoted = queuedSpotFromDurable(store.rows, store.durable)
        val position = promoted?.currentTime ?: store.rows.maxByOrNull { it.id }!!.currentTime
        val at = if (promoted != null) {
            legacyPromotionTimestampMs(store.rows, store.durable, nowMs = Long.MAX_VALUE)
        } else {
            store.rows.maxByOrNull { it.id }!!.timestamp.toEpochMillis()!!
        }
        val latest = store.rows.maxByOrNull { it.id }!!
        return offlineDelivery(
            itemId = "item-1",
            queued = QueuedSpot(latest.id, latest.timestamp, position, false, latest.duration, at),
            stored = emptyList(),
        )
    }

    @Test
    fun `listening in airplane mode with a session open survives a killed app`() {
        val store = Store()
        // Online: the heartbeat's sync lands and confirms its spot.
        store.saveAndQueue(100.0, evening)
        store.sessionSync(100.0, serverAnswered = true)
        assertEquals(emptyList<PendingProgressEntity>(), store.rows)

        // Airplane mode: the poll and the heartbeat keep saving, no sync lands.
        store.saveAndQueue(110.0, evening + 10_000)
        store.saveAndQueue(112.0, evening + 12_000)
        store.sessionSync(112.0, serverAnswered = false)
        store.saveAndQueue(1_300.0, evening + 1_200_000)
        store.sessionSync(1_300.0, serverAnswered = false)
        // The app is killed here. Nothing in memory survives.

        val plan = relaunchDelivery(store)
        val sent = plan.sessions.single()
        assertEquals(1_300.0, sent.currentTime, 0.0)
        // Dated when it was played, so a device that listened later still wins.
        assertEquals(evening + 1_200_000, sent.updatedAt)
    }

    @Test
    fun `a spot saved without queueing after the queue goes in its place`() {
        val store = Store()
        store.saveAndQueue(110.0, evening + 10_000)
        // Saved later without a queue row (an older build's heartbeat, or a
        // stop with no session). The queue used to be dropped as stale here.
        store.saveOnly(122.0, evening + 22_000)

        val promoted = queuedSpotFromDurable(store.rows, store.durable)
        assertNotNull(promoted)
        val sent = relaunchDelivery(store).sessions.single()
        assertEquals(122.0, sent.currentTime, 0.0)
        assertEquals(evening + 22_000, sent.updatedAt)
    }

    @Test
    fun `a sync of an older spot leaves a newer queued one in place`() {
        val store = Store()
        store.saveAndQueue(112.0, evening + 12_000)
        // A pause queued a later spot while the heartbeat's sync of 100 was out.
        store.sessionSync(100.0, serverAnswered = true)
        assertEquals(112.0, store.rows.single().currentTime, 0.0)
    }
}
