package com.ninelivesaudio.app.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library's error banner speaks like the rest of the screen ("Library
 * could not be loaded") and never shows raw exception text, which used to
 * read "Failed to load audiobooks: SQLiteException ...".
 */
class LibraryErrorBannerCopyTest {

    @Test
    fun `each failure has its own line in the screen voice`() {
        assertEquals("Libraries could not be loaded.", libraryErrorMessage(LibraryLoadFailure.LIBRARIES))
        assertEquals("Books could not be loaded.", libraryErrorMessage(LibraryLoadFailure.BOOKS))
        assertEquals("Saved books could not be read.", libraryErrorMessage(LibraryLoadFailure.SHELF))
    }

    @Test
    fun `no line carries exception detail or a dash`() {
        for (failure in LibraryLoadFailure.entries) {
            val line = libraryErrorMessage(failure)
            assertTrue(line, line.endsWith("."))
            for (banned in listOf(":", ";", "Failed", "\u2014", "\u2013", " - ")) {
                assertFalse("$failure: $line", line.contains(banned))
            }
        }
    }
}
