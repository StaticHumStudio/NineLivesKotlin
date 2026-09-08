package com.ninelivesaudio.app.ui.dossier

import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.domain.model.ListeningSession
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DossierHistoryResultTest {

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
    fun `only complete history permits Dossier aggregation`() {
        assertEquals(null, dossierHistoryUnavailableMessage(RemoteResult.Ok(listOf(session))))
    }

    @Test
    fun `partial history produces an explicit no-statistics error`() {
        val message = dossierHistoryUnavailableMessage(
            RemoteResult.Partial(listOf(session), "page 1: auth session changed"),
        )

        assertTrue(message.orEmpty().contains("Statistics are unavailable"))
        assertTrue(message.orEmpty().contains("page 1: auth session changed"))
    }

    @Test
    fun `failed history produces an explicit no-statistics error`() {
        val message = dossierHistoryUnavailableMessage(RemoteResult.Failed("page 0: HTTP 500"))

        assertTrue(message.orEmpty().contains("Statistics are unavailable"))
        assertTrue(message.orEmpty().contains("page 0: HTTP 500"))
    }
}
