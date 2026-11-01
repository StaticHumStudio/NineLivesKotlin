package com.ninelivesaudio.app.ui.bookdetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverActionsTest {

    @Test fun `a server book shows no cover actions`() =
        assertEquals(CoverActions(false, false), coverActions(isLocal = false, hasUserCover = true))

    @Test fun `every user can find a cover for a local book`() =
        assertEquals(CoverActions(true, false), coverActions(isLocal = true, hasUserCover = false))

    @Test fun `use the original cover shows when there is a pick`() =
        assertTrue(coverActions(isLocal = true, hasUserCover = true).showUseOriginal)

    @Test fun `use the original cover hides when there is no pick`() =
        assertFalse(coverActions(isLocal = true, hasUserCover = false).showUseOriginal)
}
