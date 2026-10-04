package com.ninelivesaudio.app.data.local

import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.data.local.entity.SHELF_BOOK_COLUMNS
import com.ninelivesaudio.app.data.local.entity.ShelfBookRow
import com.ninelivesaudio.app.data.local.entity.toShelfBook
import com.ninelivesaudio.app.data.repository.buildLibrarySql
import com.ninelivesaudio.app.domain.util.toEpochMillis
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Library shelf reads light rows, and they show the same book the full row does. */
class ShelfBookRowTest {

    private val chaptersJson =
        """[{"Id":1,"Start":0.0,"End":600.0,"Title":"One"},{"Id":2,"Start":600.0,"End":1200.0,"Title":"Two"}]"""

    private val entity = AudioBookEntity(
        id = "book-1",
        libraryId = "lib-1",
        isLocal = 0,
        title = "The Long Hum",
        author = "Static",
        narrator = "A Voice",
        description = "A very long description that the shelf never shows.",
        coverPath = "https://abs.example/api/items/book-1/cover",
        durationSeconds = 1200.0,
        addedAt = "2026-09-01T12:00:00Z",
        audioFilesJson = """[{"Id":"f1","Ino":"1","Index":1,"Duration":1200.0,"Filename":"a.mp3"}]""",
        currentTimeSeconds = 700.0,
        progress = 0.58,
        isFinished = 0,
        isDownloaded = 1,
        localPath = "/storage/books/The Long Hum",
        localCoverPath = "file:///storage/books/The Long Hum/cover.jpg",
        archivedAt = null,
        seriesName = "Hum Cycle",
        seriesSequence = "2",
        genresJson = """["Horror","Mystery"]""",
        tagsJson = """["tag-a"]""",
        chaptersJson = chaptersJson,
    )

    private fun rowOf(e: AudioBookEntity, lastPlayedAt: String?) = ShelfBookRow(
        id = e.id,
        libraryId = e.libraryId,
        isLocal = e.isLocal,
        title = e.title,
        author = e.author,
        narrator = e.narrator,
        coverPath = e.coverPath,
        durationSeconds = e.durationSeconds,
        addedAt = e.addedAt,
        currentTimeSeconds = e.currentTimeSeconds,
        progress = e.progress,
        isFinished = e.isFinished,
        isDownloaded = e.isDownloaded,
        localPath = e.localPath,
        localCoverPath = e.localCoverPath,
        archivedAt = e.archivedAt,
        seriesName = e.seriesName,
        seriesSequence = e.seriesSequence,
        genresJson = e.genresJson,
        chaptersJson = e.chaptersJson,
        lastPlayedAt = lastPlayedAt,
    )

    @Test
    fun `a shelf book equals the full book minus description, audio files, and tags`() {
        val lastPlayed = "2026-09-20T08:30:00Z"
        val full = entity.toDomain().copy(lastPlayedAt = lastPlayed.toEpochMillis())

        val shelf = rowOf(entity, lastPlayed).toShelfBook()

        assertEquals(full.copy(description = null, audioFiles = emptyList(), tags = emptyList()), shelf)
        // The row's "Ch x/y" label still works.
        assertEquals("58% • Ch 2/2", shelf.progressText)
    }

    @Test
    fun `empty lists and nulls map the way the full row maps them`() {
        val bare = entity.copy(genresJson = "[]", chaptersJson = null, author = null, addedAt = null)
        val shelf = rowOf(bare, lastPlayedAt = null).toShelfBook()
        val full = bare.toDomain()

        assertEquals(full.genres, shelf.genres)
        assertEquals(full.chapters, shelf.chapters)
        assertEquals("", shelf.author)
        assertEquals(null, shelf.addedAt)
        assertEquals(null, shelf.lastPlayedAt)
    }

    @Test
    fun `the shelf query selects every row field by name and none of the heavy columns`() {
        val sql = buildLibrarySql(tab = 0, hideFinished = false, downloadedOnly = false, hasSearch = false)
        assertTrue(sql.contains(SHELF_BOOK_COLUMNS))
        assertFalse(sql.contains("ab.*"))
        for (heavy in listOf("Description", "AudioFilesJson", "TagsJson")) {
            assertFalse("$heavy is read", sql.contains(heavy))
        }

        // Room maps a raw query by column name, so a field without a matching
        // alias would read as a default. Compare names case-insensitively.
        val aliases = SHELF_BOOK_COLUMNS.split(",")
            .map { it.trim().substringAfter(" AS ").lowercase() }
            .toSet()
        val fields = ShelfBookRow::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .map { it.name.lowercase() }
            .toSet()
        assertEquals(fields, aliases)
    }
}
