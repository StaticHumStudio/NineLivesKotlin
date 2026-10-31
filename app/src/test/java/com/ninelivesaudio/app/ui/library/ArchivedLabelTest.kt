package com.ninelivesaudio.app.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class ArchivedLabelTest {

    @Test
    fun `an archived book says since when its folder was missing`() {
        assertEquals(
            "Folder not found since Oct 3, 2026",
            archivedLabel(1_791_039_600_000L, ZoneId.of("UTC")),
        )
    }

    @Test
    fun `the date is the reader's local day`() {
        assertEquals(
            "Folder not found since Oct 2, 2026",
            archivedLabel(1_790_992_800_000L, ZoneId.of("America/New_York")),
        )
    }
}
