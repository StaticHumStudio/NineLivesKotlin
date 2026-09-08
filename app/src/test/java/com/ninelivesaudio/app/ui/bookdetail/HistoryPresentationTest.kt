package com.ninelivesaudio.app.ui.bookdetail

import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.domain.model.ListeningSession
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPresentationTest {

    private val session = ListeningSession(
        id = "session-1",
        libraryItemId = "book-1",
        currentTime = 10.seconds,
        timeListening = 20.seconds,
        startedAt = 1_000L,
        updatedAt = 2_000L,
        displayTitle = "Book one",
    )

    @Test
    fun `complete empty history is the only empty result`() {
        val presentation = historyPresentation(RemoteResult.Ok(emptyList()))

        assertEquals(HistoryLoadStatus.COMPLETE, presentation.status)
        assertTrue(presentation.sessions.isEmpty())
        assertEquals(null, presentation.message)
    }

    @Test
    fun `partial history keeps loaded rows and names the incomplete fetch`() {
        val presentation = historyPresentation(
            RemoteResult.Partial(listOf(session), "page 1: HTTP 500"),
        )

        assertEquals(HistoryLoadStatus.PARTIAL, presentation.status)
        assertEquals(listOf(session), presentation.sessions)
        assertTrue(presentation.message.orEmpty().contains("stopped early"))
        assertTrue(presentation.message.orEmpty().contains("page 1: HTTP 500"))
    }

    @Test
    fun `failed history is not represented as an empty complete history`() {
        val presentation = historyPresentation(RemoteResult.Failed("page 0: empty body"))

        assertEquals(HistoryLoadStatus.FAILED, presentation.status)
        assertTrue(presentation.sessions.isEmpty())
        assertTrue(presentation.message.orEmpty().contains("could not load"))
    }
}
