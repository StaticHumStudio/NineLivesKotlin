package com.ninelivesaudio.app.ui.bookdetail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only an archived book (source file gone) is blocked from playback. Local,
 * downloaded, and streamed-from-server books all play.
 */
class BookPlayabilityTest {

    @Test
    fun `a live book is playable`() {
        assertTrue(canPlayBook(isArchived = false))
    }

    @Test
    fun `archived book is never playable`() {
        assertFalse(canPlayBook(isArchived = true))
    }
}
