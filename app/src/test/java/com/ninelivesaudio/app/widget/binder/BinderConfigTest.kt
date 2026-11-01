package com.ninelivesaudio.app.widget.binder

import com.ninelivesaudio.app.ui.library.SortMode
import org.junit.Assert.assertEquals
import org.junit.Test

class BinderConfigTest {

    @Test fun `setup choices round trip`() {
        for (tap in BinderTap.entries) for (sort in SortMode.entries)
            assertEquals(BinderConfig(tap, sort), decodeBinderConfig(encodeBinderConfig(BinderConfig(tap, sort))))
    }

    @Test fun `a new widget plays on tap and sorts by recently played`() {
        assertEquals(BinderConfig(BinderTap.PLAY, SortMode.RECENTLY_PLAYED), decodeBinderConfig(null))
        assertEquals("PLAY|RECENTLY_PLAYED", encodeBinderConfig(BinderConfig()))
    }

    @Test fun `a stored value from a future build falls back field by field`() {
        assertEquals(BinderConfig(BinderTap.OPEN, SortMode.RECENTLY_PLAYED), decodeBinderConfig("OPEN|SOME_NEW_SORT"))
        assertEquals(BinderConfig(BinderTap.PLAY, SortMode.TITLE_AZ), decodeBinderConfig("WOBBLE|TITLE_AZ"))
        assertEquals(BinderConfig(), decodeBinderConfig("garbage"))
    }
}
