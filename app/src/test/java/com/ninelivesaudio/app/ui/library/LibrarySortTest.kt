package com.ninelivesaudio.app.ui.library

import com.ninelivesaudio.app.data.repository.buildLibrarySql
import com.ninelivesaudio.app.domain.model.AudioBook
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The shelf sort works out its keys once per book and the query no longer
 * sorts first. The order must match the former SQL-then-Kotlin sort exactly.
 */
class LibrarySortTest {

    @Test
    fun `every sort mode matches the former order on 1,000 random books`() {
        val books = randomBooks(Random(42), 1_000)
        // The former query returned books ORDER BY Title, then Kotlin sorted
        // stably on lowercased keys. Now the query order is anything.
        val formerInput = books.sortedBy { it.title }
        val newInput = books.shuffled(Random(7))

        for (mode in SortMode.entries) {
            assertEquals(
                "order differs for $mode",
                formerSortBooks(formerInput, mode).map { it.id },
                sortBooks(newInput, mode).map { it.id },
            )
        }
    }

    @Test
    fun `groups keep the shelf order without sorting again`() {
        val books = randomBooks(Random(3), 400)
        for (mode in SortMode.entries) {
            val sorted = sortBooks(books.shuffled(Random(11)), mode)
            val sections = buildGroupedSections(sorted, ViewMode.AUTHOR, mode)
            sections.forEach { section ->
                assertEquals(
                    "group ${section.key} out of order for $mode",
                    formerSortBooks(section.books.sortedBy { it.title }, mode).map { it.id },
                    section.books.map { it.id },
                )
            }
        }
    }

    @Test
    fun `the shelf query leaves sorting to Kotlin`() {
        val sql = buildLibrarySql(tab = 0, hideFinished = true, downloadedOnly = false, hasSearch = true)
        assertFalse(sql.contains("ORDER BY"))
    }

    private fun randomBooks(random: Random, count: Int): List<AudioBook> {
        val words = listOf("alpha", "Beta", "gamma", "DELTA", "Echo", "zulu", "\u00e9lan", "omega")
        val authors = listOf("Ann", "ann", "Bob", "", "Cy", "cy", "Dee")
        val seen = mutableSetOf<String>()
        return (0 until count).map { index ->
            var title: String
            do {
                // Case-only twins ("Echo 3", "echo 3") tie on the lowercased key.
                val word = words[random.nextInt(words.size)]
                val cased = if (random.nextBoolean()) word.lowercase() else word.replaceFirstChar { it.uppercase() }
                title = "$cased ${random.nextInt(400)}"
            } while (!seen.add(title))
            AudioBook(
                id = "id-$index",
                title = title,
                author = authors[random.nextInt(authors.size)],
                duration = (random.nextInt(4) * 3_600).seconds,
                progress = listOf(0.0, 0.0, 0.25, 0.5, 1.0)[random.nextInt(5)],
                addedAt = if (random.nextInt(4) == 0) null else random.nextLong(5) * 1_000L,
                lastPlayedAt = if (random.nextInt(3) == 0) null else random.nextLong(5) * 1_000L,
                genres = listOf("Horror"),
            )
        }
    }

    /** The sort as it was before keys were worked out once, kept here as the reference. */
    private fun formerSortBooks(books: List<AudioBook>, sortMode: SortMode): List<AudioBook> {
        val sequence = books.asSequence()
        return when (sortMode) {
            SortMode.RECENTLY_ADDED -> sequence.sortedWith(
                compareByDescending<AudioBook> { it.addedAt ?: Long.MIN_VALUE }
                    .thenBy { it.title.lowercase() }
            )
            SortMode.TITLE_AZ -> sequence.sortedBy { it.title.lowercase() }
            SortMode.TITLE_ZA -> sequence.sortedByDescending { it.title.lowercase() }
            SortMode.AUTHOR_AZ -> sequence.sortedWith(compareBy({ it.author.lowercase() }, { it.title.lowercase() }))
            SortMode.AUTHOR_ZA -> sequence.sortedWith(
                compareByDescending<AudioBook> { it.author.lowercase() }.thenByDescending { it.title.lowercase() }
            )
            SortMode.PROGRESS_HIGH -> sequence.sortedByDescending { it.progressPercent }
            SortMode.PROGRESS_LOW -> sequence.sortedBy { it.progressPercent }
            SortMode.DURATION_LONG -> sequence.sortedByDescending { it.duration.inWholeSeconds }
            SortMode.DURATION_SHORT -> sequence.sortedBy { it.duration.inWholeSeconds }
            SortMode.RECENTLY_PLAYED -> sequence.sortedWith(
                compareByDescending<AudioBook> { it.lastPlayedAt ?: Long.MIN_VALUE }
                    .thenBy { it.title.lowercase() }
            )
            SortMode.UNPLAYED_FIRST -> sequence.sortedWith(
                compareBy<AudioBook> { if (it.hasProgress) 1 else 0 }
                    .thenBy { it.title.lowercase() }
            )
        }.toList()
    }
}
