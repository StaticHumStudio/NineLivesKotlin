package com.ninelivesaudio.app.widget.binder

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.ui.library.SortMode
import com.ninelivesaudio.app.ui.library.sortBooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BinderPagesTest {

    @Test fun `the binder takes the library's order and stops at fifty`() {
        val books = (1..60).map { AudioBook(id = "b$it", title = "T%02d".format(it), lastPlayedAt = it.toLong()) }
        val out = binderBooks(books, SortMode.RECENTLY_PLAYED)
        assertEquals(50, out.size)
        assertEquals("b60", out.first().id)
        assertEquals(sortBooks(books, SortMode.TITLE_AZ).take(50), binderBooks(books, SortMode.TITLE_AZ))
    }

    @Test fun `archived books never reach the binder`() {
        val out = binderBooks(listOf(AudioBook(id = "a", archivedAt = 1L), AudioBook(id = "b")), SortMode.TITLE_AZ)
        assertEquals(listOf("b"), out.map { it.id })
    }

    @Test fun `the first spread opens on the icon`() {
        val s = binderSpreads(listOf(AudioBook(id = "b1", title = "One"), AudioBook(id = "b2", title = "Two")))
        assertEquals(BinderSpread(BinderPage.Icon, BinderPage.Book("b1", "One")), s[0])
        assertEquals(BinderSpread(BinderPage.Book("b2", "Two"), BinderPage.Empty), s[1])
    }

    @Test fun `an even page count needs no empty sleeve`() {
        val s = binderSpreads(listOf(AudioBook(id = "b1"), AudioBook(id = "b2"), AudioBook(id = "b3")))
        assertEquals(2, s.size)
        assertTrue(s.none { it.right == BinderPage.Empty || it.left == BinderPage.Empty })
    }

    @Test fun `spread ids survive a rebuild and differ between spreads`() {
        val books = (1..5).map { AudioBook(id = "b$it") }
        assertEquals(binderSpreads(books).map { it.stableId }, binderSpreads(books.toList()).map { it.stableId })
        assertEquals(3, binderSpreads(books).map { it.stableId }.toSet().size)
    }

    @Test fun `an empty shelf still shows the icon spread`() {
        assertEquals(listOf(BinderSpread(BinderPage.Icon, BinderPage.Empty)), binderSpreads(emptyList()))
    }
}
