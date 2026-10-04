package com.ninelivesaudio.app.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SQLite LIKE folds case for ASCII only and never drops accents, so search
 * compares folded text on both sides instead (issue #61).
 */
class SearchFoldTest {

    @Test
    fun `case folds, ASCII and beyond`() {
        assertEquals("dune", foldForSearch("DUNE"))
        assertEquals("привет", foldForSearch("ПРИВЕТ"))
    }

    @Test
    fun `accents are dropped`() {
        assertEquals("emile", foldForSearch("Émile"))
        assertEquals("elan", foldForSearch("élan"))
        assertEquals("osten", foldForSearch("Östen"))
        assertEquals("nandu", foldForSearch("ñandú"))
        // Already decomposed: E plus a combining acute.
        assertEquals("emile", foldForSearch("Émile"))
    }

    @Test
    fun `Nordic and other letters with no decomposition fold to their plain spelling`() {
        assertEquals("jo nesbo", foldForSearch("Jo Nesbø"))
        assertEquals("lodz", foldForSearch("Łódź"))
        assertEquals("strasse", foldForSearch("Straße"))
        assertEquals("aesir", foldForSearch("Æsir"))
        assertEquals("oeuvre", foldForSearch("Œuvre"))
    }

    @Test
    fun `everything else is left as typed`() {
        assertEquals("100% pure_a\\b", foldForSearch("100% Pure_A\\b"))
        assertEquals("  two  spaces ", foldForSearch("  Two  Spaces "))
        assertEquals("日本語", foldForSearch("日本語"))
        assertEquals("", foldForSearch(""))
    }
}
