package com.ninelivesaudio.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #118 final review: a flush that ran while a book played on its server
 * session sent each of that book's queued heartbeat spots as a new session
 * with no listening, filling the server's history with 0 s sessions. The
 * playing book's own session syncs confirm those spots, so the flush skips it.
 */
class FlushSkipsLiveSessionTest {

    @Test
    fun `the book playing on a server session is left to that session`() {
        assertEquals(
            listOf("other", "offline"),
            itemsToFlush(
                queuedItemIds = listOf("playing", "other", "playing"),
                offlineItemIds = listOf("offline", "playing"),
                liveSessionItemId = "playing",
            ),
        )
    }

    @Test
    fun `with nothing playing on a session every book goes once`() {
        assertEquals(
            listOf("a", "b"),
            itemsToFlush(queuedItemIds = listOf("a", "b", "a"), offlineItemIds = listOf("b"), liveSessionItemId = null),
        )
    }
}
