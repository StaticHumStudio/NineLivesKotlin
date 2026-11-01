package com.ninelivesaudio.app.ui.bookdetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverActionsTest {

    @Test fun `a server book shows no cover actions`() =
        assertEquals(CoverActions(false, false, false), coverActions(isLocal = false, unlocked = true, hasUserCover = true))

    @Test fun `an unlocked user can find a cover`() =
        assertEquals(CoverActions(true, false, false), coverActions(isLocal = true, unlocked = true, hasUserCover = false))

    @Test fun `a free user sees find a cover locked`() =
        assertEquals(CoverActions(true, true, false), coverActions(isLocal = true, unlocked = false, hasUserCover = false))

    @Test fun `use the original cover stays for anyone with a picked cover, unlock or not`() {
        assertTrue(coverActions(isLocal = true, unlocked = false, hasUserCover = true).showUseOriginal)
        assertTrue(coverActions(isLocal = true, unlocked = true, hasUserCover = true).showUseOriginal)
    }

    @Test fun `use the original cover hides when there is no pick`() =
        assertFalse(coverActions(isLocal = true, unlocked = true, hasUserCover = false).showUseOriginal)
}
