package com.ninelivesaudio.app.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A book is archived exactly when it has an archivedAt timestamp. */
class AudioBookArchiveTest {

    @Test
    fun `archived when archivedAt is set`() {
        assertTrue(AudioBook(id = "1", archivedAt = 1_700_000_000_000L).isArchived)
    }

    @Test
    fun `not archived when archivedAt is null`() {
        assertFalse(AudioBook(id = "1", archivedAt = null).isArchived)
    }
}
