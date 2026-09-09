package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.remote.ActiveRemoteScope
import com.ninelivesaudio.app.data.remote.FrozenBearer
import com.ninelivesaudio.app.data.remote.FrozenRemoteRequest
import com.ninelivesaudio.app.data.remote.RemoteOwner
import com.ninelivesaudio.app.data.remote.RemoteTarget
import com.ninelivesaudio.app.data.remote.ServerRoute
import com.ninelivesaudio.app.data.repository.PendingLifetimeClaim
import com.ninelivesaudio.app.data.repository.ProgressIdentity
import com.ninelivesaudio.app.domain.model.PlaybackSessionInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * RED contract for the real RemotePlaybackSessionCoordinator production seam.
 * These tests deliberately do not call a Boolean publication helper. The
 * coordinator must own initial open, stale replacement, null cleanup, and
 * exception cleanup, while its ports expose the state and lifetime effects.
 */
class C2Repair2cPublicationFenceRedContractTest {

    @Test
    fun initialOpenDoesNotPublishOrInvalidateWhenAuthChangesBeforeCommit() = runBlocking {
        val original = scope("owner-a", generation = 7)
        val freshSameOwner = scope("owner-a", generation = 8)
        val state = RecordingSessionState(currentScope = original)
        val lifetime = RecordingPendingLifetime()
        val responseReady = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        var authScope: ActiveRemoteScope = original

        val coordinator = coordinator(
            state = state,
            lifetime = lifetime,
            start = { _, _ ->
                responseReady.complete(Unit)
                releaseResponse.await()
                authScope = freshSameOwner
                PlaybackSessionInfo(id = "session-a", itemId = original.encodeIncoming("book-a"))
            },
            currentScope = { authScope },
        )

        val open = async {
            coordinator.open(
                scope = original,
                bookId = original.encodeIncoming("book-a"),
                requestedGeneration = 4L,
            )
        }
        responseReady.await()
        releaseResponse.complete(Unit)

        assertFalse(open.await())
        assertEquals(emptyList<String>(), state.publishedSessionIds)
        assertEquals(emptyList<PendingLifetimeClaim>(), lifetime.invalidated)
    }

    @Test
    fun staleReplacementDoesNotPublishOrClearAfterFreshSameOwnerAuth() = runBlocking {
        val original = scope("owner-a", generation = 7)
        val freshSameOwner = scope("owner-a", generation = 8)
        val state = RecordingSessionState(
            currentScope = original,
            currentSessionId = "old-session",
        )
        val lifetime = RecordingPendingLifetime()
        var authScope: ActiveRemoteScope = original
        val probe = staleSessionProbe(
            requestedGeneration = 4L,
            bookId = original.encodeIncoming("book-a"),
            sessionId = "old-session",
            position = 42.seconds,
            duration = 100.seconds,
            remoteScope = original,
        )
        val coordinator = coordinator(
            state = state,
            lifetime = lifetime,
            start = { _, _ ->
                authScope = freshSameOwner
                PlaybackSessionInfo(id = "replacement", itemId = probe.bookId)
            },
            currentScope = { authScope },
        )

        assertFalse(coordinator.recover(probe))
        assertEquals(emptyList<String>(), state.publishedSessionIds)
        assertEquals(0, state.clearCount)
        assertEquals(emptyList<PendingLifetimeClaim>(), lifetime.invalidated)
    }

    @Test
    fun staleNullAndExceptionCleanupDoNotClearFreshStateAndInvalidateOnlyTheirClaim() = runBlocking {
        val original = scope("owner-a", generation = 7)
        val freshSameOwner = scope("owner-a", generation = 8)
        val oldClaim = PendingLifetimeClaim(
            identity = ProgressIdentity(original.ownerKey, original.encodeIncoming("book-a")),
            generation = 1L,
        )
        val state = RecordingSessionState(
            currentScope = original,
            currentSessionId = "old-session",
        )
        val lifetime = RecordingPendingLifetime()
        var authScope: ActiveRemoteScope = original
        val probe = staleSessionProbe(
            requestedGeneration = 4L,
            bookId = original.encodeIncoming("book-a"),
            sessionId = "old-session",
            position = 42.seconds,
            duration = 100.seconds,
            remoteScope = original,
            pendingLifetimeClaim = oldClaim,
        )

        val nullCoordinator = coordinator(
            state = state,
            lifetime = lifetime,
            start = { _, _ ->
                authScope = freshSameOwner
                null
            },
            currentScope = { authScope },
        )
        assertFalse(nullCoordinator.recover(probe))

        val exceptionCoordinator = coordinator(
            state = state,
            lifetime = lifetime,
            start = { _, _ ->
                authScope = freshSameOwner
                error("stale transport failed")
            },
            currentScope = { authScope },
        )
        authScope = original
        assertFalse(exceptionCoordinator.recover(probe))

        assertEquals(0, state.clearCount)
        assertEquals(emptyList<PendingLifetimeClaim>(), lifetime.invalidated)
    }

    @Test
    fun currentScopeNullRecoveryClearsAndInvalidatesItsCapturedClaim() = runBlocking {
        val original = scope("owner-a", generation = 7)
        val claim = PendingLifetimeClaim(
            identity = ProgressIdentity(original.ownerKey, original.encodeIncoming("book-a")),
            generation = 1L,
        )
        val state = RecordingSessionState(original, "old-session")
        val lifetime = RecordingPendingLifetime()
        val probe = staleSessionProbe(
            requestedGeneration = 4L,
            bookId = original.encodeIncoming("book-a"),
            sessionId = "old-session",
            position = 42.seconds,
            duration = 100.seconds,
            remoteScope = original,
            pendingLifetimeClaim = claim,
        )

        assertFalse(
            coordinator(
                state = state,
                lifetime = lifetime,
                start = { _, _ -> null },
                currentScope = { original },
            ).recover(probe),
        )
        assertEquals(1, state.clearCount)
        assertEquals(listOf(claim), lifetime.invalidated)
    }

    @Test
    fun currentScopeExceptionRecoveryClearsAndInvalidatesItsCapturedClaim() = runBlocking {
        val original = scope("owner-a", generation = 7)
        val claim = PendingLifetimeClaim(
            identity = ProgressIdentity(original.ownerKey, original.encodeIncoming("book-a")),
            generation = 1L,
        )
        val state = RecordingSessionState(original, "old-session")
        val lifetime = RecordingPendingLifetime()
        val probe = staleSessionProbe(
            requestedGeneration = 4L,
            bookId = original.encodeIncoming("book-a"),
            sessionId = "old-session",
            position = 42.seconds,
            duration = 100.seconds,
            remoteScope = original,
            pendingLifetimeClaim = claim,
        )

        assertFalse(
            coordinator(
                state = state,
                lifetime = lifetime,
                start = { _, _ -> error("stale transport failed") },
                currentScope = { original },
            ).recover(probe),
        )
        assertEquals(1, state.clearCount)
        assertEquals(listOf(claim), lifetime.invalidated)
    }

    private fun coordinator(
        state: RecordingSessionState,
        lifetime: RecordingPendingLifetime,
        start: suspend (ActiveRemoteScope, String) -> PlaybackSessionInfo?,
        currentScope: () -> ActiveRemoteScope?,
    ): RemotePlaybackSessionCoordinator = RemotePlaybackSessionCoordinator(
        scopeFence = RemoteScopePublicationFence { scope, publish ->
            if (currentScope() === scope) publish() else null
        },
        sessionTransport = RemoteSessionTransport(
            start = start,
            close = { _, _ -> },
        ),
        state = state,
        pendingLifetime = lifetime,
    )

    private class RecordingSessionState(
        private val currentScope: ActiveRemoteScope?,
        private val currentSessionId: String? = null,
    ) : PlaybackSessionStatePort {
        val publishedSessionIds = mutableListOf<String>()
        var clearCount: Int = 0

        override fun currentState(): PlaybackSessionStateSnapshot = PlaybackSessionStateSnapshot(
            requestedGeneration = 4L,
            bookId = currentScope?.encodeIncoming("book-a"),
            sessionId = currentSessionId,
            scope = currentScope,
            isPlaying = true,
        )

        override fun publish(session: PlaybackSessionInfo) {
            publishedSessionIds += session.id
        }

        override fun clear() {
            clearCount++
        }
    }

    private class RecordingPendingLifetime : PendingLifetimePort {
        val invalidated = mutableListOf<PendingLifetimeClaim>()

        override fun invalidate(claim: PendingLifetimeClaim): Boolean {
            invalidated += claim
            return true
        }
    }

    private fun scope(ownerId: String, generation: Long): ActiveRemoteScope {
        val route = requireNotNull(ServerRoute.parse("https://abs.example.test"))
        val target = RemoteTarget(RemoteOwner(route, ownerId), authGeneration = generation)
        return ActiveRemoteScope(
            FrozenRemoteRequest(
                route = route,
                owner = target,
                bearer = FrozenBearer("token-$generation", route, authGeneration = generation),
                routeRevision = generation,
            ),
        )
    }
}
