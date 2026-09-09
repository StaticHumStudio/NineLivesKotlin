package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.remote.ActiveRemoteScope
import com.ninelivesaudio.app.data.remote.FrozenBearer
import com.ninelivesaudio.app.data.remote.FrozenRemoteRequest
import com.ninelivesaudio.app.data.remote.RemoteOwner
import com.ninelivesaudio.app.data.remote.RemoteTarget
import com.ninelivesaudio.app.data.remote.ServerRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * RED contract for the immutable playback lifetime consumed by PlaybackManager
 * and SyncManager. The values are real production snapshots and listening
 * state, not a parallel source-selection model.
 */
class C2PlaybackScopedLifetimeRepairContractTest {

    @Test
    fun remotePauseTerminalRetainsOriginalSourceScopeAndSendsListeningDelta() = kotlinx.coroutines.runBlocking {
        val scope = scope("owner-a", generation = 7)
        val listening = ServerSessionListening()
        val pause = playbackProgressSnapshot(
            bookId = scope.encodeIncoming("book-a"),
            isLocal = false,
            position = 42.seconds,
            duration = 100.seconds,
            serverSessionId = "session-a",
            serverTimeListened = 12.0,
            localSessionId = null,
            localTimeListened = 0.0,
            serverListening = listening,
            remoteScope = scope,
        )
        val terminal = terminalPlaybackSnapshot(
            bookId = pause.bookId,
            isLocal = pause.isLocal,
            position = pause.position,
            duration = pause.duration,
            isFinished = false,
            serverSessionId = pause.serverSessionId,
            timeListened = pause.serverTimeListened,
            serverListening = pause.serverListening,
            remoteScope = pause.remoteScope,
        )
        val delivered = mutableListOf<Double>()

        assertFalse(terminal.isLocal)
        assertSame(scope, terminal.remoteScope)
        terminal.serverListening!!.deliver(terminal.timeListened) { delta ->
            delivered += delta
            true
        }
        assertEquals(listOf(12.0), delivered)
    }

    @Test
    fun polledReportCarriesTheLoadCapturedRemoteScope() {
        val scope = scope("owner-a", generation = 7)
        val report = PolledProgressReport(
            bookId = scope.encodeIncoming("book-a"),
            currentTime = 42.0,
            duration = 100.0,
            isLocal = false,
            remoteScope = scope,
        )

        assertSame(scope, report.remoteScope)
    }

    @Test
    fun staleSessionProbeRetainsOriginalScopeAcrossSameOwnerReauthentication() {
        val original = scope("owner-a", generation = 7)
        val freshSameOwner = scope("owner-a", generation = 8)
        val probe = staleSessionProbe(
            requestedGeneration = 4L,
            bookId = original.encodeIncoming("book-a"),
            sessionId = "session-a",
            position = 42.seconds,
            duration = 100.seconds,
            remoteScope = original,
        )

        assertSame(original, probe.remoteScope)
        assertFalse(probe.remoteScope == freshSameOwner)
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
