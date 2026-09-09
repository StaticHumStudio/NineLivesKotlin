package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.remote.ActiveRemoteScope
import com.ninelivesaudio.app.data.remote.FrozenBearer
import com.ninelivesaudio.app.data.remote.FrozenRemoteRequest
import com.ninelivesaudio.app.data.remote.RemoteOwner
import com.ninelivesaudio.app.data.remote.RemoteTarget
import com.ninelivesaudio.app.data.remote.ServerRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressRepositoryOwnerScopeTest {

    @Test
    fun remoteScopeResolvesOnlyItsFreshNamespacedIdentityBeforePersistence() {
        val a = scope("account-a")
        val b = scope("account-b")
        val rawItemId = "shared-abs-item"
        val bItemId = b.encodeIncoming(rawItemId)

        assertEquals(
            ProgressIdentity(b.ownerKey, bItemId),
            resolveProgressIdentity(ProgressScope.Remote(b), bItemId) { it === b },
        )
        assertNull(resolveProgressIdentity(ProgressScope.Remote(b), rawItemId) { true })
        assertNull(resolveProgressIdentity(ProgressScope.Remote(b), a.encodeIncoming(rawItemId)) { true })
        assertNull(resolveProgressIdentity(ProgressScope.Remote(b), b.idPrefix + "not-base64") { true })
        assertNull(resolveProgressIdentity(ProgressScope.Remote(b), bItemId) { false })
    }

    @Test
    fun confirmedLocalScopeUsesItsFixedNamespaceWithoutMakingLegacyRemoteWorkExecutable() {
        val localItemId = "local-book"

        assertEquals(
            ProgressIdentity("local-progress-v1", localItemId),
            resolveProgressIdentity(ProgressScope.Local, localItemId) { error("LOCAL must not query remote scope") },
        )
        assertNull(resolveProgressIdentity(ProgressScope.Local, localItemId, localItemIsConfirmed = false) { true })
    }

    private fun scope(accountId: String): ActiveRemoteScope {
        val route = requireNotNull(ServerRoute.parse("https://abs.example.test"))
        val target = RemoteTarget(RemoteOwner(route, accountId), authGeneration = 1)
        return ActiveRemoteScope(
            FrozenRemoteRequest(
                route = route,
                owner = target,
                bearer = FrozenBearer("fixture-token", route, authGeneration = 1),
                routeRevision = 1,
            ),
        )
    }
}
