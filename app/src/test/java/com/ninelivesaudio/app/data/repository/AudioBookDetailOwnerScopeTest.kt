package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.remote.ActiveRemoteScope
import com.ninelivesaudio.app.data.remote.FrozenBearer
import com.ninelivesaudio.app.data.remote.FrozenRemoteRequest
import com.ninelivesaudio.app.data.remote.RemoteOwner
import com.ninelivesaudio.app.data.remote.RemoteTarget
import com.ninelivesaudio.app.data.remote.ServerRoute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBookDetailOwnerScopeTest {
    private fun scope(accountId: String): ActiveRemoteScope {
        val route = requireNotNull(ServerRoute.parse("https://abs.example.test"))
        val target = RemoteTarget(RemoteOwner(route, accountId), authGeneration = 7)
        return ActiveRemoteScope(
            FrozenRemoteRequest(
                route = route,
                owner = target,
                bearer = FrozenBearer("test-token", route, authGeneration = 7),
                routeRevision = 3,
            )
        )
    }

    @Test
    fun `observeById boundary accepts only a book and library from active owner`() {
        val a = scope("a")
        val b = scope("b")
        val book = a.encodeIncoming("book")

        assertTrue(isVisibleActiveRemoteBook(a, book, a.encodeIncoming("library")))
        assertFalse(isVisibleActiveRemoteBook(a, book, "raw-library"))
        assertFalse(isVisibleActiveRemoteBook(a, book, b.encodeIncoming("library")))
        assertFalse(isVisibleActiveRemoteBook(a, book, a.idPrefix + "not-base64"))
    }

    @Test
    fun `getById boundary requires the same valid library envelope`() {
        val a = scope("a")
        val book = a.encodeIncoming("book")

        assertFalse(isVisibleActiveRemoteBook(a, book, null))
        assertTrue(isVisibleActiveRemoteBook(a, book, a.encodeIncoming("library")))
    }
}
