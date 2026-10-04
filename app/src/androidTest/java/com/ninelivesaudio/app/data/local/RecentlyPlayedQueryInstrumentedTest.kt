package com.ninelivesaudio.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The recently played queries now start from PlaybackProgress and walk its
 * UpdatedAt index. They must return the same rows as the old library-first
 * join, which is kept here as the reference.
 */
@RunWith(AndroidJUnit4::class)
class RecentlyPlayedQueryInstrumentedTest {

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
    fun recentQueriesReturnTheSameRowsAsTheLibraryFirstJoin() = runBlocking {
        seed()
        val dao = database.audioBookDao()

        assertEquals(reference("lib", isLocal = null, limit = 9), dao.getRecentlyPlayedByLibrary("lib", 9).map { it.audioBook.id })
        assertEquals(reference("lib", isLocal = null, limit = 9), dao.observeRecentlyPlayedByLibrary("lib", 9).first().map { it.audioBook.id })
        assertEquals(reference("lib", isLocal = 0, limit = 20), dao.getRecentlyPlayedByLibraryAndSource("lib", 0, 20).map { it.audioBook.id })
        assertEquals(reference(null, isLocal = null, limit = 9), dao.getRecentlyPlayed(9).map { it.audioBook.id })
        assertEquals(reference(null, isLocal = null, limit = 9), dao.observeRecentlyPlayed(9).first().map { it.audioBook.id })
        assertEquals(
            reference("lib", isLocal = 0, limit = 10_000).size,
            dao.countRecentlyPlayedByLibrary("lib", 0),
        )
        assertEquals(
            "2026-01-30T00:00:00Z",
            dao.getRecentlyPlayedByLibrary("lib", 1).single().lastPlayedAt,
        )
    }

    @Test
    fun theUpdatedAtIndexExists() {
        val cursor = database.openHelper.readableDatabase.query("PRAGMA index_list(`PlaybackProgress`)")
        val names = mutableListOf<String>()
        cursor.use { while (it.moveToNext()) names += it.getString(it.getColumnIndexOrThrow("name")) }
        assertTrue(names.toString(), "idx_playback_progress_updated" in names)
    }

    private suspend fun seed() {
        val books = (0 until 30).map { index ->
            AudioBookEntity(
                id = "book-$index",
                libraryId = if (index % 5 == 0) "other" else "lib",
                isLocal = if (index % 7 == 0) 1 else 0,
                title = "Title $index",
                archivedAt = if (index % 11 == 0) 1L else null,
            )
        }
        database.audioBookDao().upsertAll(books)
        val progress = (0 until 30).filter { it % 3 != 1 }.map { index ->
            PlaybackProgressEntity(
                audioBookId = "book-$index",
                positionSeconds = 1.0,
                updatedAt = "2026-01-%02dT00:00:00Z".format(index + 1),
            )
        } + PlaybackProgressEntity(audioBookId = "deleted-book", updatedAt = "2027-01-01T00:00:00Z")
        progress.forEach { database.playbackProgressDao().upsert(it) }
    }

    /** The pre-change query shape, run raw: AudioBooks first, then PlaybackProgress. */
    private fun reference(libraryId: String?, isLocal: Int?, limit: Int): List<String> {
        val where = buildList {
            add("ab.ArchivedAt IS NULL")
            if (libraryId != null) add("ab.LibraryId = '$libraryId'")
            if (isLocal != null) add("ab.IsLocal = $isLocal")
        }.joinToString(" AND ")
        val sql = "SELECT ab.Id FROM AudioBooks ab INNER JOIN PlaybackProgress pp ON ab.Id = pp.AudioBookId " +
            "WHERE $where ORDER BY pp.UpdatedAt DESC LIMIT $limit"
        val ids = mutableListOf<String>()
        database.openHelper.readableDatabase.query(sql).use { while (it.moveToNext()) ids += it.getString(0) }
        return ids
    }
}
