package com.ninelivesaudio.app.ui.library

import com.ninelivesaudio.app.domain.model.AudioBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grouped views start collapsed only past 50 groups. Small libraries open as
 * before, and a group the user opened or closed stays that way.
 */
class LibraryGroupExpansionTest {

    private fun sections(count: Int) = (1..count).map { index ->
        GroupedSection(
            key = "group-$index",
            title = "Group $index",
            books = listOf(AudioBook(id = "a-$index"), AudioBook(id = "b-$index")),
        )
    }

    private fun List<LibraryListItem>.headers() = filterIsInstance<LibraryListItem.GroupHeader>()
    private fun List<LibraryListItem>.rows() = filterIsInstance<LibraryListItem.BookRow>()

    @Test
    fun `50 groups start open, 51 start closed`() {
        val fifty = flattenGroupedItems(sections(COLLAPSE_GROUPS_ABOVE), emptyMap())
        assertTrue(fifty.headers().all { it.isExpanded })
        assertEquals(100, fifty.rows().size)

        val fiftyOne = flattenGroupedItems(sections(COLLAPSE_GROUPS_ABOVE + 1), emptyMap())
        assertTrue(fiftyOne.headers().none { it.isExpanded })
        assertEquals(51, fiftyOne.size)
    }

    @Test
    fun `a hand-picked choice beats the default both ways`() {
        val big = flattenGroupedItems(sections(200), mapOf("group-7" to true))
        assertEquals(listOf("group-7"), big.headers().filter { it.isExpanded }.map { it.groupKey })
        assertEquals(listOf("a-7", "b-7"), big.rows().map { it.book.id })

        val small = flattenGroupedItems(sections(3), mapOf("group-2" to false))
        assertEquals(listOf(true, false, true), small.headers().map { it.isExpanded })
        assertEquals(4, small.rows().size)
    }

    @Test
    fun `a tap flips what the header showed`() {
        // Big view: closed by default, a tap opens it, a second tap closes it.
        val opened = toggledGroupChoices(emptyMap(), "group-7", groupCount = 200)
        assertEquals(mapOf("group-7" to true), opened)
        assertEquals(mapOf("group-7" to false), toggledGroupChoices(opened, "group-7", groupCount = 200))

        // Small view: open by default, so the first tap closes it.
        assertEquals(mapOf("group-2" to false), toggledGroupChoices(emptyMap(), "group-2", groupCount = 3))
    }

    @Test
    fun `choices survive a refilter that changes the group count`() {
        val choices = toggledGroupChoices(emptyMap(), "group-7", groupCount = 200)
        // A search narrows to 10 groups (all open by default) and back to 200.
        val narrowed = flattenGroupedItems(sections(10), choices)
        assertTrue(narrowed.headers().all { it.isExpanded })
        val widened = flattenGroupedItems(sections(200), choices)
        assertTrue(widened.headers().single { it.groupKey == "group-7" }.isExpanded)
        assertFalse(widened.headers().single { it.groupKey == "group-8" }.isExpanded)
    }
}
