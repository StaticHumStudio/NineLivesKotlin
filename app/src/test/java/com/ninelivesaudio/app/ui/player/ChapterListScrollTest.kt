package com.ninelivesaudio.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chapter list opens already scrolled to the chapter that is playing, so
 * a book with hundreds of chapters does not start at chapter 1.
 */
class ChapterListScrollTest {

    @Test
    fun `opens at the current chapter`() {
        assertEquals(0, chapterListInitialIndex(currentChapterIndex = 0, chapterCount = 300))
        assertEquals(137, chapterListInitialIndex(currentChapterIndex = 137, chapterCount = 300))
        assertEquals(299, chapterListInitialIndex(currentChapterIndex = 299, chapterCount = 300))
    }

    @Test
    fun `no current chapter opens at the top`() {
        assertEquals(0, chapterListInitialIndex(currentChapterIndex = -1, chapterCount = 300))
    }

    @Test
    fun `a stale index past the end opens at the last chapter`() {
        assertEquals(299, chapterListInitialIndex(currentChapterIndex = 450, chapterCount = 300))
    }

    @Test
    fun `an empty list opens at the top`() {
        assertEquals(0, chapterListInitialIndex(currentChapterIndex = 3, chapterCount = 0))
    }
}
