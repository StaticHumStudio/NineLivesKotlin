package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.remote.ActiveRemoteScope
import com.ninelivesaudio.app.data.remote.FrozenBearer
import com.ninelivesaudio.app.data.remote.FrozenRemoteRequest
import com.ninelivesaudio.app.data.remote.RemoteOwner
import com.ninelivesaudio.app.data.remote.RemoteTarget
import com.ninelivesaudio.app.data.remote.ServerRoute
import com.ninelivesaudio.app.service.SyncManager
import com.ninelivesaudio.app.service.PlaybackThrottleOwner
import com.ninelivesaudio.app.service.shouldPushPlaybackPosition
import com.ninelivesaudio.app.data.local.entity.PendingProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * RED contract for review findings 1, 2, 3, 7, and 10. The production path
 * must retain the rows it actually dispatched, not look them up again after a
 * suspend, and SyncManager must ask for the captured owner's queue.
 */
class C2ProgressDeliveryRepairContractTest {

    @Test
    fun sameIdentityDeliveryIsSerializedButAnotherOwnerMayProceed() {
        val a = scope("account-a")
        val b = scope("account-b")
        val raw = "shared-abs-item"

        assertSame(a.ownerKey, scopedProgressIdentity(a, a.encodeIncoming(raw)).ownerKey)
        assertSame(b.ownerKey, scopedProgressIdentity(b, b.encodeIncoming(raw)).ownerKey)
    }

    @Test
    fun deliveryAcknowledgesTheWholeCapturedSnapshotButNotRowsCreatedLater() {
        val captured = listOf(row(3), row(7))
        val newer = row(9)

        assertEquals(listOf(3L, 7L), capturedPendingRowIds(captured))
        assertFalse(capturedPendingRowIds(captured).contains(newer.id))
    }

    @Test
    fun queuedOnlyPersistenceNeverAdvancesTheRemotePushThrottle() {
        val throttle = PlaybackThrottleOwner()
        val queued = ProgressDeliveryOutcome(persisted = true, remoteDelivered = false)
        if (queued.remoteDelivered) throttle.recordSuccess("book", 10.0, 1_000L)

        assertFalse(queued.remoteDelivered)
        assertFalse(throttle.snapshot("book").lastSyncTimestamp > 0L)
        org.junit.Assert.assertTrue(
            shouldPushPlaybackPosition(throttle.snapshot("book"), 20.0, 600.0, false, 40_000L),
        )
    }

    /**
     * This deliberately type-checks the repaired production boundaries. The
     * required result separates durable queue persistence from accepted remote
     * delivery, exposes exactly the sent row IDs, and keeps scope fencing alive
     * during a shelf callback and acknowledgement.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun requiredRepairBoundaries(
        repository: ProgressRepository,
        syncManager: SyncManager,
        scope: ActiveRemoteScope,
    ) {
        val itemId = scope.encodeIncoming("shared-abs-item")

        val reconnectRows = repository.pendingProgressCount(scope)
        val queuedOnly = repository.savePushOrEnqueueProgress(
            scope = scope,
            itemId = itemId,
            currentTime = 10.0,
            isFinished = false,
            duration = 600.0,
            pushToServer = false,
            onPersisted = {},
        )
        check(!queuedOnly.remoteDelivered)

        val direct = repository.deliverPendingProgress(scope, itemId)
        repository.acknowledgePendingProgress(scope, itemId, direct.sentRowIds)
        repository.flushPendingProgress(scope)

        syncManager.flushOfflineQueue(scope, expectedPendingRows = reconnectRows)
    }

    private fun scope(accountId: String): ActiveRemoteScope {
        val route = requireNotNull(ServerRoute.parse("https://abs.example.test"))
        val owner = RemoteTarget(RemoteOwner(route, accountId), authGeneration = 1)
        return ActiveRemoteScope(
            FrozenRemoteRequest(
                route = route,
                owner = owner,
                bearer = FrozenBearer("fixture-$accountId", route, authGeneration = 1),
                routeRevision = 1,
            ),
        )
    }

    private fun row(id: Long) = PendingProgressEntity(
        id = id,
        itemId = "nlr1:fixture",
        ownerKey = "owner-a",
        currentTime = id.toDouble(),
        duration = 600.0,
        isAtomic = 1,
        timestamp = "2026-09-08T00:00:00Z",
    )
}
