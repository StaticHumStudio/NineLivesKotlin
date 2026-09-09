package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.data.remote.ActiveRemoteScope
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.remote.FrozenBearer
import com.ninelivesaudio.app.data.remote.FrozenRemoteRequest
import com.ninelivesaudio.app.data.remote.RemoteOwner
import com.ninelivesaudio.app.data.remote.RemoteTarget
import com.ninelivesaudio.app.data.remote.ServerRoute
import com.ninelivesaudio.app.data.repository.ProgressRepository
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * C2b's remote seams must not accept an item ID without the scope that first
 * authenticated it. This is a compile contract on purpose. The C2a raw forms
 * must not be reused by pause, heartbeat, terminal, recovery, or queue work.
 */
class ScopedProgressBoundaryContractTest {

    @Test
    fun remoteSnapshotsRetainTheCapturedScopeInsteadOfRecapturingCurrentAuth() = runBlocking {
        val scope = scope("account-a")

        val pause = playbackProgressSnapshot(
            bookId = scope.encodeIncoming("shared-abs-item"),
            isLocal = false,
            position = 12.seconds,
            duration = 600.seconds,
            serverSessionId = "session-a",
            serverTimeListened = 12.0,
            localSessionId = null,
            localTimeListened = 0.0,
            remoteScope = scope,
        )
        val terminal = terminalPlaybackSnapshot(
            bookId = pause.bookId,
            isLocal = pause.isLocal,
            position = pause.position,
            duration = pause.duration,
            isFinished = true,
            serverSessionId = pause.serverSessionId,
            timeListened = pause.serverTimeListened,
            remoteScope = scope,
        )

        assertSame(scope, pause.remoteScope)
        assertSame(scope, terminal.remoteScope)
    }

    /**
     * Type-check every C2b ingress, durable, dispatch, and acknowledgement
     * boundary. A raw ID can only travel beside its exact [ActiveRemoteScope].
     * The first red compile must name these missing scoped overloads.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun requireCapturedScopeAtEveryRemoteBoundary(
        api: ApiService,
        repository: ProgressRepository,
        scope: ActiveRemoteScope,
    ) {
        val encodedItemId = scope.encodeIncoming("shared-abs-item")
        val serverProgress = PlaybackProgressEntity(
            audioBookId = encodedItemId,
            positionSeconds = 12.0,
            updatedAt = "2026-09-08T00:00:00Z",
        )

        repository.getPlaybackProgress(scope, encodedItemId)
        repository.pendingProgressToken(scope, encodedItemId)
        repository.invalidatePendingProgressLifetime(scope, encodedItemId)
        repository.savePushOrEnqueueProgress(
            scope = scope,
            itemId = encodedItemId,
            currentTime = 12.0,
            isFinished = false,
            duration = 600.0,
            pushToServer = true,
        )
        repository.importServerProgressIfNoPending(
            scope = scope,
            progress = serverProgress,
            importToken = repository.progressImportToken(scope),
            onImported = {},
        )
        repository.flushPendingProgress(scope)
        repository.acknowledgePendingProgress(
            scope = scope,
            itemId = encodedItemId,
            rowIds = listOf(1L),
        )

        api.getAllUserProgress(scope)
        api.getUserProgress(scope, encodedItemId)
        api.updateProgress(scope, encodedItemId, 12.0, isFinished = false, duration = 600.0)
        api.startPlaybackSession(scope, encodedItemId)
        api.syncSessionProgress(scope, "session-a", 12.0, 600.0, timeListened = 12.0)
        api.closeSession(scope, "session-a")
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
}
