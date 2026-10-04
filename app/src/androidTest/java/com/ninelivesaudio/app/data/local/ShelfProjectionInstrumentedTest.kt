package com.ninelivesaudio.app.data.local

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.data.local.entity.toShelfBook
import com.ninelivesaudio.app.data.repository.buildLibrarySql
import com.ninelivesaudio.app.data.repository.buildLibrarySqlArgs
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.util.toEpochMillis
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The light shelf query returns the same books, with the same shelf fields,
 * as the former `ab.*` read, on a real Room database.
 */
@RunWith(AndroidJUnit4::class)
class ShelfProjectionInstrumentedTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun shelfRowsMatchTheFullRowsMinusTheHeavyFields() = runBlocking {
        val dao = database.audioBookDao()
        dao.upsertAll(
            listOf(
                book("b", "Beta", chaptersJson = """[{"Id":1,"Start":0.0,"End":60.0,"Title":"One"}]"""),
                book("a", "alpha", genresJson = """["Horror"]"""),
                book("c", "Gamma", archivedAt = 1L),
                book("d", "Delta", libraryId = "other"),
            )
        )
        database.playbackProgressDao().upsert(
            PlaybackProgressEntity(audioBookId = "b", positionSeconds = 30.0, updatedAt = "2026-09-20T08:30:00Z")
        )

        val former: Map<String, AudioBook> = dao.getByLibraryWithLastPlayed(LIBRARY)
            .filter { it.audioBook.archivedAt == null }
            .associate { result ->
                result.audioBook.id to result.audioBook.toDomain().copy(
                    lastPlayedAt = result.lastPlayedAt?.toEpochMillis(),
                    description = null,
                    audioFiles = emptyList(),
                    tags = emptyList(),
                )
            }
        val shelf = dao.getFilteredBooks(
            SimpleSQLiteQuery(
                buildLibrarySql(tab = 0, hideFinished = false, downloadedOnly = false, hasSearch = false),
                arrayOf(LIBRARY),
            )
        ).map { it.toShelfBook() }

        assertEquals(former, shelf.associateBy { it.id })
        assertEquals(setOf("a", "b"), shelf.map { it.id }.toSet())
        assertEquals(1, shelf.single { it.id == "b" }.chapters.size)
    }

    @Test
    fun percentAndUnderscoreMatchOnlyThemselves() = runBlocking {
        val dao = database.audioBookDao()
        dao.upsertAll(
            listOf(
                book("pct", "100% Pure"),
                book("num", "100 Days"),
                book("under", "a_b"),
                book("any", "axb"),
            )
        )

        suspend fun shelfSearch(query: String) = dao.getFilteredBooks(
            SimpleSQLiteQuery(
                buildLibrarySql(tab = 0, hideFinished = false, downloadedOnly = false, hasSearch = true),
                buildLibrarySqlArgs(LIBRARY, query),
            )
        ).map { it.id }.toSet()

        assertEquals(setOf("pct"), shelfSearch("%"))
        assertEquals(setOf("under"), shelfSearch("_"))
        assertEquals(setOf("pct", "num"), shelfSearch("100"))
    }

    @Test
    fun searchIgnoresAccentsAndCaseInEveryField() = runBlocking {
        val dao = database.audioBookDao()
        dao.upsertAll(
            listOf(
                book("emile", "Émile"),
                book("dune", "DUNE"),
                book("by-author", "Other").copy(author = "Jo Nesbø"),
                book("by-narrator", "Another").copy(narrator = "Zoë Wanamaker"),
                book("plain", "Nothing Here"),
            )
        )

        suspend fun shelfSearch(query: String) = dao.getFilteredBooks(
            SimpleSQLiteQuery(
                buildLibrarySql(tab = 0, hideFinished = false, downloadedOnly = false, hasSearch = true),
                buildLibrarySqlArgs(LIBRARY, query),
            )
        ).map { it.id }.toSet()

        assertEquals(setOf("emile"), shelfSearch("Emile"))
        assertEquals(setOf("emile"), shelfSearch("ÉMILE"))
        assertEquals(setOf("dune"), shelfSearch("dune"))
        assertEquals(setOf("by-author"), shelfSearch("nesbo"))
        assertEquals(setOf("by-narrator"), shelfSearch("zoe"))
    }

    private fun book(
        id: String,
        title: String,
        libraryId: String = LIBRARY,
        archivedAt: Long? = null,
        genresJson: String = "[]",
        chaptersJson: String = "[]",
    ) = AudioBookEntity(
        id = id,
        libraryId = libraryId,
        title = title,
        author = "Author $id",
        description = "Long description for $id",
        durationSeconds = 120.0,
        addedAt = "2026-09-01T12:00:00Z",
        audioFilesJson = """[{"Id":"f-$id","Ino":"1","Index":1,"Duration":120.0,"Filename":"$id.mp3"}]""",
        progress = 0.25,
        archivedAt = archivedAt,
        seriesName = "Series",
        seriesSequence = "1",
        genresJson = genresJson,
        tagsJson = """["tag"]""",
        chaptersJson = chaptersJson,
    )

    private companion object {
        const val LIBRARY = "lib"
    }
}
