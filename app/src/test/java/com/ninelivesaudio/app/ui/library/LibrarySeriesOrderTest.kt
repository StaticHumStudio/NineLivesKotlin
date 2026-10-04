package com.ninelivesaudio.app.ui.library

import com.ninelivesaudio.app.domain.model.AudioBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Series view lists each series in reading order, by sequence, whatever the
 * shelf sort (issue #63). It used to reuse the shelf sort, so a series read
 * in title or date order.
 */
class LibrarySeriesOrderTest {

    @Test
    fun `sequences compare as numbers, so 2 comes before 10`() {
        val books = listOf(book("10"), book("2"), book("1"), book("11"), book("3"))

        assertEquals(listOf("1", "2", "3", "10", "11"), seriesReadingOrder(books).map { it.seriesSequence })
    }

    @Test
    fun `a half step sits between its neighbours`() {
        val books = listOf(book("2"), book("1.5"), book("1"), book("0.5"), book("1,25"))

        assertEquals(listOf("0.5", "1", "1,25", "1.5", "2"), seriesReadingOrder(books).map { it.seriesSequence })
    }

    @Test
    fun `missing and non-numeric sequences go last, by title`() {
        val books = listOf(
            book(null, title = "Zeta"),
            book("Prequel", title = "Alpha"),
            book("2"),
            book("", title = "Beta"),
            book("1"),
        )

        assertEquals(
            listOf("1", "2", "Alpha", "Beta", "Zeta"),
            seriesReadingOrder(books).map { if (it.title == "Book") it.seriesSequence else it.title },
        )
    }

    @Test
    fun `equal sequences go by title, and equal titles keep the order they came in`() {
        val books = listOf(
            book("1", title = "beta", id = "b"),
            book("1", title = "Alpha", id = "a2"),
            book("1", title = "alpha", id = "a1"),
        )

        assertEquals(listOf("a2", "a1", "b"), seriesReadingOrder(books).map { it.id })
    }

    @Test
    fun `only a plain number is a number`() {
        assertEquals(10.0, seriesSequenceNumber(" 10 ")!!, 0.0)
        assertEquals(1.5, seriesSequenceNumber("1.5")!!, 0.0)
        assertEquals(1.5, seriesSequenceNumber("1,5")!!, 0.0)
        assertNull(seriesSequenceNumber(null))
        assertNull(seriesSequenceNumber(""))
        assertNull(seriesSequenceNumber("Book 3"))
        assertNull(seriesSequenceNumber("1-3"))
        assertNull(seriesSequenceNumber("NaN"))
        assertNull(seriesSequenceNumber("1e3"))
    }

    @Test
    fun `Series view uses reading order whatever the shelf sort`() {
        val books = listOf(
            book("3", title = "Ashes", id = "s3", addedAt = 3),
            book("1", title = "Cinders", id = "s1", addedAt = 1),
            book("2", title = "Blaze", id = "s2", addedAt = 2),
        )
        for (mode in SortMode.entries) {
            val sections = buildGroupedSections(sortBooks(books, mode), ViewMode.SERIES, mode)

            assertEquals("for $mode", listOf("s1", "s2", "s3"), sections.single().books.map { it.id })
        }
    }

    @Test
    fun `series are still ordered by the shelf sort, newest book first for Recently Added`() {
        val books = listOf(
            // Old series whose book 3 was added last of everything.
            book("1", series = "Old", id = "old1", addedAt = 1),
            book("3", series = "Old", id = "old3", addedAt = 30),
            // New series, every book added after Old's first.
            book("1", series = "New", id = "new1", addedAt = 10),
            book("2", series = "New", id = "new2", addedAt = 20),
        )

        val sections = buildGroupedSections(sortBooks(books, SortMode.RECENTLY_ADDED), ViewMode.SERIES, SortMode.RECENTLY_ADDED)

        assertEquals(listOf("Old", "New"), sections.map { it.key })
        assertEquals(listOf("old1", "old3"), sections[0].books.map { it.id })
        assertEquals(listOf("new1", "new2"), sections[1].books.map { it.id })
    }

    @Test
    fun `books with no series keep the shelf order`() {
        val books = listOf(
            book(null, series = null, title = "Zulu", id = "z"),
            book(null, series = null, title = "Alpha", id = "a"),
        )

        val sections = buildGroupedSections(sortBooks(books, SortMode.TITLE_ZA), ViewMode.SERIES, SortMode.TITLE_ZA)

        assertEquals(listOf("z", "a"), sections.single().books.map { it.id })
    }

    @Test
    fun `other views keep the shelf order inside each group`() {
        val books = listOf(book("2", title = "Alpha", id = "a"), book("1", title = "Beta", id = "b"))

        val sections = buildGroupedSections(sortBooks(books, SortMode.TITLE_AZ), ViewMode.AUTHOR, SortMode.TITLE_AZ)

        assertEquals(listOf("a", "b"), sections.single().books.map { it.id })
    }

    private fun book(
        sequence: String?,
        title: String = "Book",
        id: String = "id-$sequence-$title",
        series: String? = "Saga",
        addedAt: Long? = null,
    ) = AudioBook(
        id = id,
        title = title,
        author = "Author",
        seriesName = series,
        seriesSequence = sequence,
        addedAt = addedAt,
    )
}
