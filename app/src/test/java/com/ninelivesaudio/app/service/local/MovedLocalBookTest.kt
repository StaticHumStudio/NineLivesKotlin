package com.ninelivesaudio.app.service.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #20: a moved folder gets a new id, so the row carrying the user's place
 * reconciles away. [matchMovedLocalBooks] is the narrow rescue that carries the
 * position over, and its whole job is knowing when NOT to guess.
 */
class MovedLocalBookTest {

    private fun book(id: String, folder: String, vararg tracks: Pair<String, Long>) =
        LocalBookFingerprint(id, folder, tracks.toSet())

    @Test
    fun `same folder name and same files carries over`() {
        val moves = matchMovedLocalBooks(
            vanished = listOf(book("old", "Dune", "01.mp3" to 10L, "02.mp3" to 20L)),
            arrived = listOf(book("new", "Dune", "02.mp3" to 20L, "01.mp3" to 10L)),
        )

        assertEquals(mapOf("old" to "new"), moves)
    }

    @Test
    fun `a changed file set is not the same book`() {
        val moves = matchMovedLocalBooks(
            vanished = listOf(book("old", "Dune", "01.mp3" to 10L, "02.mp3" to 20L)),
            arrived = listOf(book("new", "Dune", "01.mp3" to 10L)),
        )

        assertTrue(moves.isEmpty())
    }

    @Test
    fun `two identical copies moving at once is ambiguous and skipped`() {
        val moves = matchMovedLocalBooks(
            vanished = listOf(book("oldA", "Dune", "01.mp3" to 10L), book("oldB", "Dune", "01.mp3" to 10L)),
            arrived = listOf(book("newA", "Dune", "01.mp3" to 10L), book("newB", "Dune", "01.mp3" to 10L)),
        )

        assertTrue(moves.isEmpty())
    }

    @Test
    fun `a same-shaped folder with different file sizes is a different book`() {
        val moves = matchMovedLocalBooks(
            vanished = listOf(book("old", "CD 1", "01.mp3" to 10L)),
            arrived = listOf(book("new", "CD 1", "01.mp3" to 99L)),
        )

        assertTrue(moves.isEmpty())
    }

    @Test
    fun `a book with no tracks never matches`() {
        val moves = matchMovedLocalBooks(
            vanished = listOf(book("old", "Dune")),
            arrived = listOf(book("new", "Dune")),
        )

        assertTrue(moves.isEmpty())
    }

    @Test
    fun `folder name comes from the encoded SAF document path`() {
        assertEquals(
            "Ghost Book",
            folderNameOfTrackUri(
                "content://com.android.externalstorage.documents/tree/primary%3AAudiobooks" +
                    "/document/primary%3AAudiobooks%2FZZ%20Scan%20Test%2FGhost%20Book%2F01.mp3"
            ),
        )
        assertEquals("", folderNameOfTrackUri(null))
    }
}
