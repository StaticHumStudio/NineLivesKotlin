package com.ninelivesaudio.app.widget

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.service.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class WidgetRefreshTriggersTest {

    private val t0 = WidgetTrigger(true, WidgetIdentity(AppMode.AUDIOBOOKSHELF, "http://h", "u", "lib_s", true), "b1", false, 0, true, 0, 0, 0)

    @Test fun `the first trigger draws both widgets`() {
        assertEquals(setOf(WidgetTarget.BINDER, WidgetTarget.HOME), widgetRefreshTargets(null, t0))
    }

    @Test fun `an identity change refreshes both widgets`() {
        for (next in listOf(
            t0.copy(identity = t0.identity.copy(appMode = AppMode.LOCAL, activeLibraryId = "lib_l")),
            t0.copy(identity = t0.identity.copy(serverUrl = "http://other")),
            t0.copy(identity = t0.identity.copy(username = "v")),
            t0.copy(identity = t0.identity.copy(hasAuthToken = false)),
        )) assertEquals(setOf(WidgetTarget.BINDER, WidgetTarget.HOME), widgetRefreshTargets(t0, next))
    }

    @Test fun `unlock, sync, scan and book changes refresh both`() {
        for (next in listOf(t0.copy(isUnlocked = false), t0.copy(syncCount = 1), t0.copy(scanCount = 1), t0.copy(currentBookId = "b2")))
            assertEquals(setOf(WidgetTarget.BINDER, WidgetTarget.HOME), widgetRefreshTargets(t0, next))
    }

    @Test fun `play state, chapter and the playing tick only touch the large widget`() {
        for (next in listOf(t0.copy(isPlaying = true), t0.copy(chapterIndex = 1), t0.copy(tick = 1), t0.copy(serverReachable = false)))
            assertEquals(setOf(WidgetTarget.HOME), widgetRefreshTargets(t0, next))
    }

    @Test fun `nothing changed means no redraw`() {
        assertEquals(emptySet<WidgetTarget>(), widgetRefreshTargets(t0, t0.copy()))
    }

    @Test fun `buffering counts as playing so it does not push`() {
        assertTrue(isPlayingForWidget(PlaybackState.BUFFERING))
        assertTrue(isPlayingForWidget(PlaybackState.PLAYING))
        assertFalse(isPlayingForWidget(PlaybackState.LOADING))
        assertFalse(isPlayingForWidget(PlaybackState.PAUSED))
    }

    @Test fun `relock runs just after the trial ends`() {
        assertNull(trialRelockDelayMs(null, nowEpochMs = 1_000))
        assertEquals(65_000L, trialRelockDelayMs(61_000, nowEpochMs = 1_000))
        assertEquals(5_000L, trialRelockDelayMs(500, nowEpochMs = 1_000))
    }

    @Test fun `the playing refresh is minutes, not seconds`() {
        assertEquals(5.minutes, PLAYING_REFRESH_INTERVAL)
        assertTrue(WIDGET_PUSH_DEBOUNCE < 1.seconds)
    }
}
