package com.ninelivesaudio.app.widget.binder

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.ui.library.SortMode
import com.ninelivesaudio.app.ui.library.sortBooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test fun `the first pocket is the first book, never the icon`() {
        val p = binderPages(listOf(AudioBook(id = "b1", title = "One"), AudioBook(id = "b2", title = "Two")), perPage = 2)
        assertEquals(listOf(BinderPage(listOf(BinderPocket.Book("b1", "One"), BinderPocket.Book("b2", "Two")))), p)
    }

    @Test fun `a short last page is padded with empty pockets`() {
        val p = binderPages((1..5).map { AudioBook(id = "b$it") }, perPage = 4)
        assertEquals(2, p.size)
        assertEquals(listOf("b5"), p[1].pockets.filterIsInstance<BinderPocket.Book>().map { it.id })
        assertEquals(3, p[1].pockets.count { it == BinderPocket.Empty })
        assertTrue(p.all { it.pockets.size == 4 })
    }

    @Test fun `fifty books make the page counts each size expects`() {
        val books = (1..50).map { AudioBook(id = "b$it") }
        assertEquals(25, binderPages(books, 2).size)
        assertEquals(13, binderPages(books, 4).size)
        assertEquals(7, binderPages(books, 8).size)
    }

    @Test fun `page ids survive a rebuild and differ between pages and sizes`() {
        val books = (1..5).map { AudioBook(id = "b$it") }
        assertEquals(binderPages(books, 2).map { it.stableId }, binderPages(books.toList(), 2).map { it.stableId })
        assertEquals(3, binderPages(books, 2).map { it.stableId }.toSet().size)
        assertNotEquals(binderPages(books, 2)[0].stableId, binderPages(books, 4)[0].stableId)
    }

    @Test fun `an empty shelf still has one page of empty pockets`() {
        assertEquals(listOf(BinderPage(List(4) { BinderPocket.Empty })), binderPages(emptyList(), 4))
    }

    @Test fun `the arrows step one page and wrap at both ends`() {
        assertEquals(1, binderTurnTarget(0, 13, forward = true))
        assertEquals(12, binderTurnTarget(0, 13, forward = false))
        assertEquals(0, binderTurnTarget(12, 13, forward = true))
        assertEquals(11, binderTurnTarget(12, 13, forward = false))
        assertEquals(0, binderTurnTarget(0, 1, forward = true))
        assertEquals(0, binderTurnTarget(0, 1, forward = false))
        assertEquals(0, binderTurnTarget(5, 0, forward = true))
    }

    @Test fun `the page number is one based`() {
        assertEquals("1 / 13", binderPageNumber(0, 13))
        assertEquals("13 / 13", binderPageNumber(12, 13))
    }

    @Test fun `a stack's version changes with its contents and nothing else`() {
        val l = BinderLayout(2, 1, BinderArrows.FOOTER, 2)
        val keys = listOf("b1|One|c1|true", "b2|Two|c2|false")
        val v = binderStackVersion(keys, BinderTap.PLAY, l, 256)
        assertEquals(v, binderStackVersion(keys.toList(), BinderTap.PLAY, l.copy(), 256))
        assertNotEquals(v, binderStackVersion(keys.reversed(), BinderTap.PLAY, l, 256))
        assertNotEquals(v, binderStackVersion(listOf("b1|One|c1|true", "b2|Two|c2|true"), BinderTap.PLAY, l, 256))
        assertNotEquals(v, binderStackVersion(keys, BinderTap.OPEN, l, 256))
        assertNotEquals(v, binderStackVersion(keys, BinderTap.PLAY, l.copy(rows = 2), 256))
        assertNotEquals(v, binderStackVersion(keys, BinderTap.PLAY, l, 240))
        // Joined keys never collide by shifting a boundary.
        assertNotEquals(binderStackVersion(listOf("ab", "c"), BinderTap.PLAY, l, 256), binderStackVersion(listOf("a", "bc"), BinderTap.PLAY, l, 256))
    }
}
