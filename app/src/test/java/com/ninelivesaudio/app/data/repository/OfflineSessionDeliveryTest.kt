package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.local.entity.OfflineListeningSessionEntity
import com.ninelivesaudio.app.data.local.entity.PendingProgressEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.domain.model.UserProgress
import com.ninelivesaudio.app.domain.util.toEpochMillis
import com.ninelivesaudio.app.domain.util.toIso8601
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #118: listening made on the phone without a server session reaches
 * the server through Audiobookshelf's offline session sync, carrying the time
 * it happened. The server refuses to move progress that was updated more
 * recently, so the phone never guesses which device is newer. These pin what
 * one delivery sends and how listening builds into sessions.
 */
class OfflineSessionDeliveryTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val evening = 1_794_693_600_000L // 2026-11-14 22:00 UTC

    private fun session(
        id: String,
        updatedAt: Long,
        currentTime: Double,
        timeListening: Double,
        synced: Double = 0.0,
    ) = OfflineListeningSessionEntity(
        id = id,
        itemId = "item-1",
        displayTitle = "Long Book",
        startedAt = updatedAt - (timeListening * 1000).toLong(),
        updatedAt = updatedAt,
        startTime = currentTime - timeListening,
        currentTime = currentTime,
        duration = 36_000.0,
        timeListening = timeListening,
        syncedTimeListening = synced,
    )

    private fun queued(position: Double, at: Long, finished: Boolean = false, duration: Double = 36_000.0) =
        QueuedSpot(
            rowId = 9,
            rowTimestamp = "row-time-$at",
            positionSeconds = position,
            isFinished = finished,
            durationSeconds = duration,
            timestampMs = at,
        )

    // ─── Building sessions from listening ────────────────────────────────

    private fun listen(latest: OfflineListeningSessionEntity?, seconds: Double, at: Long, position: Double) =
        offlineSessionAfterListening(
            latest = latest,
            itemId = "item-1",
            displayTitle = null,
            listenedSeconds = seconds,
            currentTime = position,
            duration = 36_000.0,
            nowMs = at,
            newId = { "new-$at" },
        )

    @Test
    fun `listening soon after a session continues it with the whole time`() {
        val first = listen(null, 12.0, evening, position = 112.0)
        assertEquals("new-$evening", first.id)
        assertEquals(evening - 12_000, first.startedAt)
        assertEquals(100.0, first.startTime, 0.0)
        assertEquals(12.0, first.timeListening, 0.0)

        val next = listen(first, 12.0, evening + 12_000, position = 124.0)
        // Same id, so a second delivery replaces the first on the server
        // instead of counting the first 12 seconds twice.
        assertEquals(first.id, next.id)
        assertEquals(24.0, next.timeListening, 0.0)
        assertEquals(124.0, next.currentTime, 0.0)
        assertEquals(evening + 12_000, next.updatedAt)
        assertEquals(first.startedAt, next.startedAt)
    }

    @Test
    fun `a long gap or a clock that moved back starts a new session`() {
        val first = listen(null, 12.0, evening, position = 112.0)

        assertNotEquals(first.id, listen(first, 5.0, evening + OFFLINE_SESSION_GAP_MS + 1, 200.0).id)
        assertNotEquals(first.id, listen(first, 5.0, evening - minute, 200.0).id)
        assertEquals(first.id, listen(first, 5.0, evening + OFFLINE_SESSION_GAP_MS, 200.0).id)
    }

    // ─── What one delivery sends ─────────────────────────────────────────

    @Test
    fun `a queued spot with no listening goes as a session of its own, the same each time`() {
        val plan = offlineDelivery("item-1", queued(56.8, evening), stored = emptyList())

        val only = plan.sessions.single()
        assertEquals(0, plan.carrierIndex)
        assertEquals(56.8, only.currentTime, 0.0)
        // The time the listening happened, not the time it is sent. The
        // server keeps another device's later spot over it (the issue's S1).
        assertEquals(evening, only.updatedAt)
        assertEquals(0.0, only.timeListening, 0.0)
        // Sent again after a lost answer, it is the same session.
        assertEquals(only.id, offlineDelivery("item-1", queued(56.8, evening), emptyList()).sessions.single().id)
    }

    @Test
    fun `a queued spot after a session rides on it`() {
        val listened = session("s1", updatedAt = evening, currentTime = 100.0, timeListening = 600.0)

        val plan = offlineDelivery("item-1", queued(104.0, evening + 4_000), listOf(listened))

        val carrier = plan.sessions.single()
        assertEquals("s1", carrier.id)
        assertEquals(104.0, carrier.currentTime, 0.0)
        assertEquals(evening + 4_000, carrier.updatedAt)
        assertEquals(600.0, carrier.timeListening, 0.0)
    }

    @Test
    fun `a delivered session still carries a later queued spot`() {
        val delivered = session("s1", updatedAt = evening, currentTime = 100.0, timeListening = 600.0, synced = 600.0)

        val plan = offlineDelivery("item-1", queued(80.0, evening + minute), listOf(delivered))

        assertEquals(listOf("s1"), plan.sessions.map { it.id })
        assertEquals(80.0, plan.sessions.single().currentTime, 0.0)
    }

    @Test
    fun `a queued spot older than a session's spot claims nothing`() {
        // A heartbeat after the last queued save holds the phone's later spot.
        val listened = session("s1", updatedAt = evening + minute, currentTime = 160.0, timeListening = 60.0)

        val plan = offlineDelivery("item-1", queued(100.0, evening), listOf(listened))

        assertNull(plan.carrierIndex)
        assertEquals(listOf(listened), plan.sessions)
        // And with the session already delivered, nothing goes at all.
        val delivered = listened.copy(syncedTimeListening = 60.0)
        assertEquals(emptyList<OfflineListeningSessionEntity>(), offlineDelivery("item-1", queued(100.0, evening), listOf(delivered)).sessions)
    }

    @Test
    fun `a queued spot long after the last session gets its own and both go newest first`() {
        val earlier = session("s1", updatedAt = evening, currentTime = 100.0, timeListening = 600.0)

        val plan = offlineDelivery("item-1", queued(400.0, evening + 2 * hour), listOf(earlier))

        assertEquals(2, plan.sessions.size)
        assertEquals(0, plan.carrierIndex)
        assertEquals(400.0, plan.sessions[0].currentTime, 0.0)
        assertEquals("s1", plan.sessions[1].id)
        assertEquals(100.0, plan.sessions[1].currentTime, 0.0)
    }

    @Test
    fun `several sessions go newest first, so the newest spot is the one the server applies`() {
        // Audiobookshelf stamps progress it creates or updates with the time
        // it does so, then refuses any session dated earlier. Sent oldest
        // first, the oldest spot would land and refuse the rest. Newest first,
        // the newest lands and the older ones are kept for listening stats only.
        val morning = session("s1", updatedAt = evening - 10 * hour, currentTime = 100.0, timeListening = 600.0)
        val noon = session("s2", updatedAt = evening - 6 * hour, currentTime = 900.0, timeListening = 600.0)
        val night = session("s3", updatedAt = evening, currentTime = 1_800.0, timeListening = 600.0)

        val plan = offlineDelivery("item-1", queued = null, stored = listOf(noon, morning, night))
        assertEquals(listOf("s3", "s2", "s1"), plan.sessions.map { it.id })

        // With a queued spot riding on the latest session, it still goes first.
        val carried = offlineDelivery("item-1", queued(1_830.0, evening + 30_000), listOf(morning, night, noon))
        assertEquals(listOf("s3", "s2", "s1"), carried.sessions.map { it.id })
        assertEquals(0, carried.carrierIndex)
        assertEquals(1_830.0, carried.sessions[0].currentTime, 0.0)
    }

    @Test
    fun `only listening the server lacks goes when nothing is queued`() {
        val delivered = session("s1", updatedAt = evening, currentTime = 100.0, timeListening = 600.0, synced = 600.0)
        val grown = session("s2", updatedAt = evening + hour, currentTime = 900.0, timeListening = 300.0, synced = 120.0)

        val plan = offlineDelivery("item-1", queued = null, stored = listOf(grown, delivered))

        assertEquals(listOf("s2"), plan.sessions.map { it.id })
        assertNull(plan.carrierIndex)
    }

    @Test
    fun `a finished spot goes at the end of the book, where the server marks it finished`() {
        val plan = offlineDelivery("item-1", queued(35_995.0, evening, finished = true), emptyList())
        assertEquals(36_000.0, plan.sessions.single().currentTime, 0.0)

        // With no length known it goes as it is, and the flush marks it.
        val unknown = offlineDelivery("item-1", queued(35_995.0, evening, finished = true, duration = 0.0), emptyList())
        assertEquals(35_995.0, unknown.sessions.single().currentTime, 0.0)
    }

    @Test
    fun `a queued spot with no known length still goes`() {
        // It used to sit in the queue forever, blocking imports for the book.
        val plan = offlineDelivery("item-1", queued(250.0, evening, duration = 0.0), emptyList())

        assertEquals(250.0, plan.sessions.single().currentTime, 0.0)
        assertEquals(0.0, plan.sessions.single().duration, 0.0)
    }

    @Test
    fun `a session is sent dated on the phone's calendar`() {
        val api = session("s1", updatedAt = evening, currentTime = 100.0, timeListening = 600.0)
            .toApiLocalSession(ZoneId.of("America/New_York"))

        assertEquals("s1", api.id)
        assertEquals("item-1", api.libraryItemId)
        assertEquals("book", api.mediaType)
        assertEquals(3, api.playMethod)
        assertEquals(evening, api.updatedAt)
        assertEquals(600.0, api.timeListening, 0.0)
        assertEquals(100.0, api.currentTime, 0.0)
        // 22:00 UTC is 17:00 in New York, the same Saturday.
        assertEquals("2026-11-14", api.date)
        assertEquals("Saturday", api.dayOfWeek)
    }

    @Test
    fun `a spot of unknown age is dated when the book reached the server, not 1970`() {
        // Audiobookshelf files the session in listening history under its
        // dates. 0 put it on 1970-01-01. The book's added date is just as
        // safe: any progress on the server for the book was saved after it
        // was added, so that progress still wins over this spot.
        val added = evening - 30L * 24 * hour
        assertEquals(added, queuedSpotTimestampMs(0L.toIso8601(), bookAddedAtMs = added))
        assertEquals(added, queuedSpotTimestampMs("garbage", bookAddedAtMs = added))
        // Nothing known about the book either: as old as can be.
        assertEquals(0L, queuedSpotTimestampMs(0L.toIso8601(), bookAddedAtMs = null))
        // A dated spot keeps its own time.
        assertEquals(evening, queuedSpotTimestampMs(evening.toIso8601(), bookAddedAtMs = added))

        val api = offlineDelivery("item-1", queued(56.8, added), emptyList()).sessions.single()
            .toApiLocalSession(ZoneId.of("UTC"))
        assertEquals(added, api.updatedAt)
        assertEquals(added, api.startedAt)
        assertEquals("2026-10-15", api.date)
    }

    // ─── Kept from the first #118 pass ───────────────────────────────────

    @Test
    fun `a promoted legacy row keeps the time it was saved`() {
        val legacy = PendingProgressEntity(
            id = 7,
            itemId = "item-1",
            currentTime = 5_631.0,
            isFinished = 0,
            timestamp = "2026-11-14T22:00:00Z",
            duration = 36_000.0,
            isAtomic = 0,
        )
        val savedAt = "2026-11-14T22:00:00Z".toEpochMillis()!!
        val muchLater = savedAt + 14 * hour

        assertEquals(savedAt, legacyPromotionTimestampMs(listOf(legacy), durableProgress = null, nowMs = muchLater))
        // A later durable write is the newer local act.
        val durable = PlaybackProgressEntity("item-1", 5_640.0, 0, "2026-11-14T22:05:00Z")
        assertEquals(savedAt + 5 * minute, legacyPromotionTimestampMs(listOf(legacy), durable, nowMs = muchLater))
        // Nothing parseable: now.
        assertEquals(
            muchLater,
            legacyPromotionTimestampMs(listOf(legacy.copy(timestamp = "garbage")), durable.copy(updatedAt = null), muchLater),
        )
    }

    @Test
    fun `server position falls back to progress times duration`() {
        val atStart = UserProgress(currentTime = 0.seconds, progress = 0.25)
        assertEquals(900.0, serverProgressPositionSeconds(atStart, durationSeconds = 3_600.0), 0.0)
        assertEquals(0.0, serverProgressPositionSeconds(atStart, durationSeconds = null), 0.0)
        assertEquals(
            42.0,
            serverProgressPositionSeconds(UserProgress(currentTime = 42.seconds, progress = 0.5), 3_600.0),
            0.0,
        )
    }
}
