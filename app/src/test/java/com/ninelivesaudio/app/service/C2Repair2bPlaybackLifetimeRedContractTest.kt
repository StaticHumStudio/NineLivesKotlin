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
 * RED contract for C2 repair round 2b. These tests deliberately name the
 * narrow production seams required to prove the remaining lifetime defects.
 * They must be made executable through PlaybackManager or SyncManager paths,
 * not replaced with a parallel test-only source-selection model.
 */
class C2Repair2bPlaybackLifetimeRedContractTest {

    @Test
    fun staleRecoveryPublicationAndCleanupRequireTheOriginalScopeAtTheDecisionPoint() {
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

        assertFalse(
            staleSessionRecoveryStateIsCurrent(
                probe = probe,
                currentGeneration = 4L,
                currentBookId = probe.bookId,
                currentSessionId = probe.sessionId,
                currentScope = freshSameOwner,
                remoteScopeIsCurrent = false,
            ),
        )
        assertFalse(
            staleSessionRecoveryStateIsCurrent(
                probe = probe,
                currentGeneration = 4L,
                currentBookId = probe.bookId,
                currentSessionId = probe.sessionId,
                currentScope = original,
                remoteScopeIsCurrent = false,
            ),
        )
    }

    @Test
    fun heartbeatLifetimeRequiresCompleteImmutableSourceIdentity() {
        val local = playbackProgressSource(
            bookId = "book-a",
            isLocal = true,
            remoteScope = null,
        )
        val remote = playbackProgressSource(
            bookId = "book-a",
            isLocal = false,
            remoteScope = scope("owner-a", generation = 7),
        )

        assertFalse(
            playbackSyncLifetimeIsCurrent(
                requestedGeneration = 4L,
                currentGeneration = 4L,
                requestedBookId = "book-a",
                currentBookId = "book-a",
                requestedServerSessionId = null,
                currentServerSessionId = null,
                requestedLocalSessionId = null,
                currentLocalSessionId = null,
                requestedSource = local,
                currentSource = remote,
            ),
        )
    }

    @Test
    fun playbackPollSnapshotCannotPairABookIdWithAnotherSource() {
        val source = playbackProgressSource(
            bookId = "book-a",
            isLocal = false,
            remoteScope = scope("owner-a", generation = 7),
        )

        val snapshot = playbackSourceSnapshot(bookId = "book-a", source = source)

        assertEquals("book-a", snapshot.bookId)
        assertSame(source, snapshot.source)
    }

    @Test
    fun terminalCloseUsesCapturedBookIdAfterANewBookLoads() {
        assertEquals(
            "book-a",
            terminalCloseBookId(terminalBookId = "book-a", liveBookId = "book-b"),
        )
    }

    @Test
    fun restoreCarriesCapturedScopeIntoThePlaybackLoad() {
        val captured = scope("owner-a", generation = 7)
        val load = restorePlaybackLoad(capturedScope = captured)

        assertSame(captured, load.remoteScope)
    }

    @Test
    fun orchestrationSeamsMustExposeCapturedSourceAndScopeToProductionCallers() {
        val captured = scope("owner-a", generation = 7)
        val source = playbackProgressSource(
            bookId = captured.encodeIncoming("book-a"),
            isLocal = false,
            remoteScope = captured,
        )

        val report = productionPlaybackReport(source = source, currentTime = 42.0, duration = 100.0)

        assertFalse(report.isLocal)
        assertSame(captured, report.remoteScope)
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
