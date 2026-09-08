package com.ninelivesaudio.app.data.remote

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiServiceCatalogScopeTest {
    private fun scope(accountId: String): ActiveRemoteScope {
        val route = requireNotNull(ServerRoute.parse("https://abs.example.test"))
        val owner = RemoteTarget(RemoteOwner(route, accountId), authGeneration = 1)
        return ActiveRemoteScope(
            FrozenRemoteRequest(
                route = route,
                owner = owner,
                bearer = FrozenBearer("test-token", route, authGeneration = 1),
                routeRevision = 1,
            )
        )
    }

    @Test
    fun `same ABS catalog IDs become distinct durable rows for different owners`() {
        val a = scope("account-a")
        val b = scope("account-b")

        assertEquals(
            a.encodeIncoming("library-1"),
            namespaceIncomingLibrary(a, Library(id = "library-1", name = "Shelf")).id,
        )
        assertEquals(
            b.encodeIncoming("book-1"),
            namespaceIncomingBook(b, AudioBook(id = "book-1", libraryId = "library-1"), "library-1").id,
        )
        assertEquals(
            b.encodeIncoming("library-1"),
            namespaceIncomingBook(b, AudioBook(id = "book-1", libraryId = "library-1"), "library-1").libraryId,
        )
    }

    @Test
    fun `raw and foreign catalog IDs cannot be decoded for active egress`() {
        val a = scope("account-a")
        val b = scope("account-b")

        assertNull(catalogEgressId(b, "book-1"))
        assertNull(catalogEgressId(b, a.encodeIncoming("book-1")))
        assertEquals("book-1", catalogEgressId(b, b.encodeIncoming("book-1")))
    }
}
