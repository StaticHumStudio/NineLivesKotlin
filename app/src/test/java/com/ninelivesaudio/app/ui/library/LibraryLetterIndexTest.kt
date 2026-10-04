package com.ninelivesaudio.app.ui.library

import com.ninelivesaudio.app.domain.model.AudioBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The letter rail on the Library list. Its offsets are worked out once per
 * list change, so these pin what they say and that a long shelf is cheap.
 */
class LibraryLetterIndexTest {

    private fun book(id: Int, title: String, author: String = "") =
        AudioBook(id = "b$id", title = title, author = author)

    /** [perLetter] books for each letter in [letters], titled like "A title 3". */
    private fun shelf(letters: List<Char>, perLetter: Int): List<AudioBook> =
        letters.flatMapIndexed { li, letter ->
            (0 until perLetter).map { n -> book(li * 1000 + n, "$letter title $n") }
        }

    private fun LetterIndex.asPairs() = labels.zip(offsets.toList())

    @Test
    fun `a title's letter is its first letter in capitals`() {
        assertEquals("D", letterLabelFor("dune"))
        assertEquals("D", letterLabelFor("Dune"))
        assertEquals("T", letterLabelFor("The Hobbit"))
    }

    @Test
    fun `digits and symbols share the number sign`() {
        assertEquals("#", letterLabelFor("1984"))
        assertEquals("#", letterLabelFor("'Salem's Lot"))
        assertEquals("#", letterLabelFor("[REC]"))
    }

    @Test
    fun `a blank title falls under the number sign`() {
        assertEquals("#", letterLabelFor(""))
        assertEquals("#", letterLabelFor("   "))
    }

    @Test
    fun `leading spaces are skipped`() {
        assertEquals("M", letterLabelFor("  Mort"))
    }

    @Test
    fun `an accented first letter files under its plain letter`() {
        assertEquals("E", letterLabelFor("Émile"))
        assertEquals("O", letterLabelFor("Östen"))
        assertEquals("N", letterLabelFor("ñandú"))
    }

    @Test
    fun `a letter outside the Latin alphabet falls under the number sign`() {
        assertEquals("#", letterLabelFor("日本語"))
        assertEquals("#", letterLabelFor("Привет"))
    }

    @Test
    fun `a flat title shelf gets the first row of each letter`() {
        val books = shelf(listOf('A', 'B', 'D'), perLetter = 20)

        val index = flatLetterIndex(books, SortMode.TITLE_AZ)

        assertEquals(listOf("A" to 0, "B" to 20, "D" to 40), index.asPairs())
    }

    @Test
    fun `numbers come first on an ascending shelf and last on a descending one`() {
        val asc = listOf(book(1, "1984")) + shelf(listOf('A', 'B'), 25)
        val desc = shelf(listOf('B', 'A'), 25) + listOf(book(9, "1984"))

        assertEquals(listOf("#" to 0, "A" to 1, "B" to 26), flatLetterIndex(asc, SortMode.TITLE_AZ).asPairs())
        assertEquals(listOf("B" to 0, "A" to 25, "#" to 50), flatLetterIndex(desc, SortMode.TITLE_ZA).asPairs())
    }

    @Test
    fun `a letter with no books is left off`() {
        val books = shelf(listOf('A', 'C'), 25)

        assertEquals(listOf("A", "C"), flatLetterIndex(books, SortMode.TITLE_AZ).labels)
    }

    @Test
    fun `an accented title that sorts at the end does not disturb the offsets`() {
        // The shelf sorts on the lowercased title, so "Émile" lands after "z".
        // The rail keeps the first run of each letter and ignores the stray.
        val books = shelf(listOf('E', 'Z'), 25) + book(99, "Émile")

        val index = flatLetterIndex(books, SortMode.TITLE_AZ)

        assertEquals(listOf("E" to 0, "Z" to 25), index.asPairs())
    }

    @Test
    fun `an author sort files books under the author's letter`() {
        val books = (0 until 50).map { book(it, title = "Zzz $it", author = if (it < 25) "Austen" else "Brontë") }

        val index = flatLetterIndex(books, SortMode.AUTHOR_AZ)

        assertEquals(listOf("A" to 0, "B" to 25), index.asPairs())
    }

    @Test
    fun `a sort that is not alphabetical gets no index`() {
        val books = shelf(listOf('A', 'B', 'C'), 20)
        val alphabetical = setOf(
            SortMode.TITLE_AZ, SortMode.TITLE_ZA, SortMode.AUTHOR_AZ, SortMode.AUTHOR_ZA,
        )

        for (mode in SortMode.entries - alphabetical) {
            assertTrue("index for $mode", flatLetterIndex(books, mode).isEmpty)
            assertFalse("alphabetical $mode", sortsAlphabetically(mode))
        }
        for (mode in alphabetical) {
            assertTrue("alphabetical $mode", sortsAlphabetically(mode))
        }
    }

    @Test
    fun `a short shelf gets no index`() {
        val books = shelf(listOf('A', 'B', 'C'), perLetter = 5)

        assertTrue(books.size < MIN_ROWS_FOR_LETTER_INDEX)
        assertTrue(flatLetterIndex(books, SortMode.TITLE_AZ).isEmpty)
    }

    @Test
    fun `a shelf under a single letter gets no index`() {
        val books = shelf(listOf('A'), perLetter = 60)

        assertTrue(flatLetterIndex(books, SortMode.TITLE_AZ).isEmpty)
    }

    @Test
    fun `an empty shelf gets no index`() {
        assertTrue(flatLetterIndex(emptyList(), SortMode.TITLE_AZ).isEmpty)
        assertTrue(groupedLetterIndex(emptyList(), SortMode.TITLE_AZ).isEmpty)
    }

    @Test
    fun `a grouped list indexes its headers and counts the rows between them`() {
        // Expanded groups put book rows between headers. The offset is the
        // header's position in the flat list, not its position among groups.
        val sections = listOf("Apple" to 10, "Avocado" to 12, "Banana" to 20, "Cherry" to 30)
            .map { (name, count) ->
                GroupedSection(name, name, (0 until count).map { book(it, "$name book $it") })
            }
        val items = flattenGroupedItems(sections, choices = sections.associate { it.key to true })

        val index = groupedLetterIndex(items, SortMode.TITLE_AZ)

        assertEquals(listOf("A" to 0, "B" to 24, "C" to 45), index.asPairs())
        assertTrue(items[0] is LibraryListItem.GroupHeader)
        assertTrue(items[24] is LibraryListItem.GroupHeader)
        assertTrue(items[45] is LibraryListItem.GroupHeader)
    }

    @Test
    fun `book rows never add letters to a grouped index`() {
        val sections = listOf("Alpha", "Beta", "Gamma").map { name ->
            GroupedSection(name, name, (0 until 30).map { book(it, "Zebra $it") })
        }
        val items = flattenGroupedItems(sections, choices = sections.associate { it.key to true })

        assertEquals(listOf("A", "B", "G"), groupedLetterIndex(items, SortMode.TITLE_AZ).labels)
    }

    @Test
    fun `collapsed groups index just their headers`() {
        val names = ('A'..'Z').flatMap { listOf("$it one", "$it two") }
        val sections = names.map { GroupedSection(it, it, listOf(book(1, "x"))) }
        val items = flattenGroupedItems(sections, choices = sections.associate { it.key to false })

        val index = groupedLetterIndex(items, SortMode.TITLE_AZ)

        assertEquals(('A'..'Z').map { "$it" }, index.labels)
        assertEquals((0 until 26).map { it * 2 }, index.offsets.toList())
    }

    @Test
    fun `a grouped list that is not in alphabetical order gets no index`() {
        val sections = listOf("Alpha", "Beta", "Gamma").map { name ->
            GroupedSection(name, name, (0 until 30).map { book(it, "x $it") })
        }
        val items = flattenGroupedItems(sections, choices = sections.associate { it.key to true })

        assertTrue(groupedLetterIndex(items, SortMode.RECENTLY_PLAYED).isEmpty)
    }

    @Test
    fun `the work stops once every letter has been found`() {
        var calls = 0
        val index = buildLetterIndex(rowCount = 50_000) { i ->
            calls++
            // 26 letters then the number sign, then 49,973 more of the same.
            if (i < 26) ('A' + i).toString() else if (i == 26) "#" else "Z"
        }

        assertEquals(27, index.labels.size)
        assertEquals(27, calls)
    }

    @Test
    fun `a 50,000 book shelf is indexed in one pass`() {
        var calls = 0
        val index = buildLetterIndex(rowCount = 50_000) { i ->
            calls++
            // Only ever two letters, so the pass cannot stop early.
            if (i < 25_000) "A" else "B"
        }

        assertEquals(listOf("A" to 0, "B" to 25_000), index.asPairs())
        assertEquals(50_000, calls)
    }

    @Test
    fun `the label for a scroll position is the last one that has started`() {
        val index = LetterIndex(listOf("A", "B", "D"), intArrayOf(0, 20, 40))

        assertEquals(0, index.labelIndexAt(0))
        assertEquals(0, index.labelIndexAt(19))
        assertEquals(1, index.labelIndexAt(20))
        assertEquals(1, index.labelIndexAt(39))
        assertEquals(2, index.labelIndexAt(40))
        assertEquals(2, index.labelIndexAt(10_000))
    }

    @Test
    fun `a position before the first label counts as the first label`() {
        val index = LetterIndex(listOf("A", "B"), intArrayOf(3, 20))

        assertEquals(0, index.labelIndexAt(-1))
        assertEquals(0, index.labelIndexAt(0))
    }

    @Test
    fun `a touch on the rail picks the letter under the finger`() {
        // 27 letters in 540 px is 20 px each.
        assertEquals(0, letterIndexAtTouch(y = 0f, heightPx = 540f, labelCount = 27))
        assertEquals(0, letterIndexAtTouch(y = 19.9f, heightPx = 540f, labelCount = 27))
        assertEquals(1, letterIndexAtTouch(y = 20f, heightPx = 540f, labelCount = 27))
        assertEquals(26, letterIndexAtTouch(y = 539.9f, heightPx = 540f, labelCount = 27))
    }

    @Test
    fun `a drag past either end of the rail sticks to the first or last letter`() {
        assertEquals(0, letterIndexAtTouch(y = -300f, heightPx = 540f, labelCount = 27))
        assertEquals(26, letterIndexAtTouch(y = 9_000f, heightPx = 540f, labelCount = 27))
    }

    @Test
    fun `a rail with no height or no letters picks nothing unsafe`() {
        assertEquals(0, letterIndexAtTouch(y = 10f, heightPx = 0f, labelCount = 27))
        assertEquals(0, letterIndexAtTouch(y = 10f, heightPx = 540f, labelCount = 0))
    }

    @Test
    fun `every letter shows when each has room`() {
        assertEquals(1, letterRailStride(labelCount = 27, heightPx = 540f, minSlotPx = 20f))
    }

    @Test
    fun `a short rail shows every other letter or fewer`() {
        // 27 letters at 20 px need 540 px. 270 px fits every second one.
        assertEquals(2, letterRailStride(labelCount = 27, heightPx = 270f, minSlotPx = 20f))
        assertEquals(3, letterRailStride(labelCount = 27, heightPx = 180f, minSlotPx = 20f))
    }

    @Test
    fun `a rail that is nearly tall enough still thins its letters`() {
        // 540 px are needed and 400 px are there, so every letter would be
        // crowded. Rounding the stride down to 1 would leave them all in.
        assertEquals(2, letterRailStride(labelCount = 27, heightPx = 400f, minSlotPx = 20f))
    }

    @Test
    fun `a rail with no height still has a stride`() {
        assertEquals(1, letterRailStride(labelCount = 27, heightPx = 0f, minSlotPx = 20f))
        assertEquals(1, letterRailStride(labelCount = 0, heightPx = 100f, minSlotPx = 20f))
    }

    @Test
    fun `an identity key matches the same list and never a copy`() {
        val books = shelf(listOf('A', 'B'), 25)
        val copy = books.toList()

        assertEquals(IdentityKey(books), IdentityKey(books))
        assertEquals(IdentityKey(books).hashCode(), IdentityKey(books).hashCode())
        assertFalse(IdentityKey(books) == IdentityKey(copy))
    }

    @Test
    fun `TalkBack hears the number sign as words`() {
        assertEquals("numbers and symbols", letterRailSpokenLabel("#"))
        assertEquals("M", letterRailSpokenLabel("M"))
    }
}
