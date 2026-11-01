package com.ninelivesaudio.app.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class SortModeLabelTest {

    @Test fun `every sort mode has a distinct label`() {
        val labels = SortMode.entries.map { it.label() }
        assertEquals(11, labels.size)
        assertEquals(11, labels.toSet().size)
    }

    @Test fun `labels match the library chips`() {
        assertEquals("Newest", SortMode.RECENTLY_ADDED.label())
        assertEquals("Recently played", SortMode.RECENTLY_PLAYED.label())
        assertEquals("Unplayed", SortMode.UNPLAYED_FIRST.label())
        assertEquals("Title A→Z", SortMode.TITLE_AZ.label())
    }
}
