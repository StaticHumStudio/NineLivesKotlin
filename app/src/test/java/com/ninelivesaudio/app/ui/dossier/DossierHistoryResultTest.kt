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
    fun `complete history aggregates with no warning`() {
        val presentation = dossierHistoryPresentation(RemoteResult.Ok(listOf(session)))

        assertEquals(listOf(session), presentation.sessions)
        assertEquals(null, presentation.warning)
        assertEquals(null, presentation.unavailableMessage)
    }

    @Test
    fun `partial history keeps its statistics and warns`() {
        val presentation = dossierHistoryPresentation(
            RemoteResult.Partial(listOf(session), "page 1: auth session changed"),
        )

        assertEquals(listOf(session), presentation.sessions)
        assertEquals(null, presentation.unavailableMessage)
        assertTrue(presentation.warning.orEmpty().contains("part of your history"))
        assertTrue(presentation.warning.orEmpty().contains("page 1: auth session changed"))
    }

    @Test
    fun `failed history produces an explicit no-statistics error`() {
        val presentation = dossierHistoryPresentation(RemoteResult.Failed("page 0: HTTP 500"))

        assertTrue(presentation.sessions.isEmpty())
        assertEquals(null, presentation.warning)
        assertTrue(presentation.unavailableMessage.orEmpty().contains("Statistics are unavailable"))
        assertTrue(presentation.unavailableMessage.orEmpty().contains("page 0: HTTP 500"))
    }

    @Test
    fun `a failed fetch resets the header whisper with the statistics`() {
        val previous = NightwatchDossierViewModel.DossierState(
            isLoading = false,
            headerWhisper = "You listened like someone with a deadline.",
            totalSessions = 23,
            overviewWhisper = "Busy month.",
        )
        val failed = previous.historyUnavailable("Statistics are unavailable: page 0: HTTP 500")
        assertEquals("Statistics are unavailable: page 0: HTTP 500", failed.error)
        assertEquals(NightwatchDossierViewModel.DossierState().headerWhisper, failed.headerWhisper)
        assertEquals(0, failed.totalSessions)
        assertEquals(null, failed.overviewWhisper)
    }
}
