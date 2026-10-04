package com.ninelivesaudio.app.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** TalkBack hears whether a group is open and what a tap on it does. */
class LibraryGroupHeaderSemanticsTest {

    @Test
    fun `an open group says so and offers to collapse`() {
        assertEquals("Expanded", groupHeaderStateDescription(isExpanded = true))
        assertEquals("Collapse group", groupHeaderClickLabel(isExpanded = true))
    }

    @Test
    fun `a closed group says so and offers to expand`() {
        assertEquals("Collapsed", groupHeaderStateDescription(isExpanded = false))
        assertEquals("Expand group", groupHeaderClickLabel(isExpanded = false))
    }
}
