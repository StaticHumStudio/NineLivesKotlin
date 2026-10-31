package com.ninelivesaudio.app.service.local

import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #93: a local track's title tag names its chapter, unless the tag is really the book's name. */
class LocalChapterTitlesTest {

    private fun tracks(vararg tags: String?) = tags.mapIndexed { i, tag ->
        ScannedTrack(
            id = "t$i",
            uri = "content://x/carmilla_0${i + 1}_lefanu_64kb.mp3",
            filename = "carmilla_0${i + 1}_lefanu_64kb.mp3",
            index = i,
            title = tag,
        )
    }

    private val fileNames = listOf("carmilla_01_lefanu_64kb", "carmilla_02_lefanu_64kb", "carmilla_03_lefanu_64kb")

    @Test
    fun `a track with a title tag uses it`() {
        assertEquals(
            listOf("Chapter 02: A Guest", "Chapter 03: The Inn"),
            chapterTitles(tracks("Chapter 02: A Guest", "Chapter 03: The Inn")),
        )
    }

    @Test
    fun `a track with no title tag keeps its file name`() {
        assertEquals(
            listOf("Chapter 01", "carmilla_02_lefanu_64kb", "carmilla_03_lefanu_64kb"),
            chapterTitles(tracks("Chapter 01", null, "  ")),
        )
    }

    @Test
    fun `a title repeated on every track is the book's name, so file names are used`() {
        assertEquals(fileNames, chapterTitles(tracks("Dracula", "Dracula", "Dracula")))
    }

    @Test
    fun `the repeat check ignores case and spaces`() {
        assertEquals(fileNames, chapterTitles(tracks("Dracula", " dracula ", "DRACULA")))
    }

    @Test
    fun `a lone shared tag beside untagged tracks still counts as a repeat`() {
        assertEquals(fileNames, chapterTitles(tracks("Dracula", "Dracula", null)))
    }

    @Test
    fun `a one track book keeps its file name`() {
        assertEquals(listOf("carmilla_01_lefanu_64kb"), chapterTitles(tracks("Dracula")))
    }

    @Test
    fun `a few shared titles among distinct ones are kept`() {
        assertEquals(listOf("Intro", "Chapter 1", "Intro"), chapterTitles(tracks("Intro", "Chapter 1", "Intro")))
    }
}
