package com.ninelivesaudio.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesNameParserTest {

    @Test
    fun `a single series keeps its name and numeric sequence`() {
        assertEquals("Dungeon Crawler Carl" to "7", parseSeriesNameField("Dungeon Crawler Carl #7"))
        assertEquals("Series Name" to "1.5", parseSeriesNameField("Series Name #1.5"))
    }

    @Test
    fun `a multi series book groups under its first series only`() {
        assertEquals("S1" to "1", parseSeriesNameField("S1 #1, S2 #3"))
        assertEquals("The Expanse" to "4", parseSeriesNameField("The Expanse #4, Space Operas #12, Hugo Nominees #2"))
    }

    @Test
    fun `a comma inside the series name stays in the name`() {
        assertEquals("Love, Death" to "2", parseSeriesNameField("Love, Death #2"))
    }

    @Test
    fun `a non numeric sequence is still split from the name`() {
        assertEquals("Saga" to "1-3", parseSeriesNameField("Saga #1-3"))
        assertEquals("Discworld" to "Book 2", parseSeriesNameField("Discworld #Book 2"))
    }

    @Test
    fun `a hash inside the series name stays in the name`() {
        assertEquals("C# Recipes" to "2", parseSeriesNameField("C# Recipes #2"))
    }

    @Test
    fun `a plain series name has no sequence`() {
        assertEquals("Saga" to null, parseSeriesNameField("Saga"))
        assertEquals("Saga" to null, parseSeriesNameField("  Saga  "))
    }

    @Test
    fun `blank input is no series`() {
        assertEquals(null to null, parseSeriesNameField("   "))
    }
}
