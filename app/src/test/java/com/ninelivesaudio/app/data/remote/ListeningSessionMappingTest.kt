package com.ninelivesaudio.app.data.remote

import com.ninelivesaudio.app.data.remote.dto.ApiListeningSession
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningSessionMappingTest {

    @Test
    fun `incoming server session receives its captured owner envelope`() {
        val session = ApiListeningSession(
            id = "session-1",
            libraryItemId = "book-00006",
            currentTime = 120.0,
            timeListening = 120.0,
            startedAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_120_000L,
            displayTitle = "History fixture",
        )

        val mapped = namespaceIncomingListeningSession("owner-a", session)

        assertEquals("session-1", mapped.id)
        assertEquals(RemoteIdCodec.encode("owner-a", "book-00006"), mapped.libraryItemId)
        assertEquals(120.seconds, mapped.currentTime)
        assertEquals(120.seconds, mapped.timeListening)
        assertEquals(1_700_000_000_000L, mapped.startedAt)
        assertEquals(1_700_000_120_000L, mapped.updatedAt)
        assertEquals("History fixture", mapped.displayTitle)
    }
}
