package com.ninelivesaudio.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningTimeFormatTest {

    @Test fun `listening time reads the way the altar card always has`() {
        assertEquals("< 1m", formatListeningTime(30.0))
        assertEquals("45m", formatListeningTime(45 * 60.0))
        assertEquals("3h 22m", formatListeningTime(3 * 3600.0 + 22 * 60.0))
    }

    @Test fun `total time given is the sum over the recent lives`() {
        assertEquals(5400.0, totalTimeGivenSeconds(listOf(1800.0, 3600.0)), 0.0)
        assertEquals(0.0, totalTimeGivenSeconds(emptyList()), 0.0)
        assertEquals(9, RECENT_LIVES_LIMIT)
    }
}
