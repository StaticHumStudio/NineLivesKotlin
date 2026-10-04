package com.ninelivesaudio.app.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.data.local.migration.ALL_MIGRATIONS
import com.ninelivesaudio.app.data.local.migration.MIGRATION_9_10
import com.ninelivesaudio.app.data.local.migration.SEARCH_BACKFILL_PAGE_SIZE
import com.ninelivesaudio.app.data.repository.buildLibrarySql
import com.ninelivesaudio.app.data.repository.buildLibrarySqlArgs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real v9 database, built from the exported 9.json, upgraded to v10. Room
 * validates the result against the v10 entities, so a column that differs
 * from what the entity declares fails here instead of on a user's phone.
 */
@RunWith(AndroidJUnit4::class)
class MigrationUpgradeInstrumentedTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun nineToTenFillsTheSearchColumnForEveryBookAndKeepsTheData() {
        helper.createDatabase(TEST_DB, 9).use { db ->
            db.insert("AudioBooks", SQLiteDatabase.CONFLICT_FAIL, v9Book("emile", "Émile", "Jean-Jacques ROUSSEAU", "Œuvres", "Zoë"))
            db.insert("AudioBooks", SQLiteDatabase.CONFLICT_FAIL, v9Book("dune", "DUNE", null, null, null))
            // Past one backfill page, so the paging is exercised on real SQLite.
            for (n in 0 until SEARCH_BACKFILL_PAGE_SIZE + 5) {
                db.insert("AudioBooks", SQLiteDatabase.CONFLICT_FAIL, v9Book("bulk-$n", "Bulk $n", "Author $n", null, null))
            }
            db.insert(
                "PlaybackProgress",
                SQLiteDatabase.CONFLICT_FAIL,
                ContentValues().apply {
                    put("AudioBookId", "emile")
                    put("PositionSeconds", 42.5)
                    put("IsFinished", 0)
                    put("UpdatedAt", "2026-09-20T08:30:00Z")
                },
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_9_10)

        db.query("SELECT Id, Title, Author, SeriesName, Narrator, SearchText, Progress, GenresJson FROM AudioBooks").use { rows ->
            assertEquals(SEARCH_BACKFILL_PAGE_SIZE + 7, rows.count)
            while (rows.moveToNext()) {
                val expected = bookSearchText(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4))
                assertEquals(rows.getString(0), expected, rows.getString(5))
                assertEquals(0.25, rows.getDouble(6), 0.0)
                assertEquals("[\"Horror\"]", rows.getString(7))
            }
        }
        db.query("SELECT Title, SearchText FROM AudioBooks WHERE Id = 'emile'").use { rows ->
            rows.moveToFirst()
            assertEquals("Émile", rows.getString(0))
            assertEquals("emile\u001Fjean-jacques rousseau\u001Foeuvres\u001Fzoe", rows.getString(1))
        }
        db.query("SELECT PositionSeconds, UpdatedAt FROM PlaybackProgress WHERE AudioBookId = 'emile'").use { rows ->
            rows.moveToFirst()
            assertEquals(42.5, rows.getDouble(0), 0.0)
            assertEquals("2026-09-20T08:30:00Z", rows.getString(1))
        }
    }

    @Test
    fun anUpgradedLibraryFindsAccentedAndUppercaseTitlesWithNoResync() = runBlocking {
        helper.createDatabase(TEST_DB, 9).use { db ->
            db.insert("AudioBooks", SQLiteDatabase.CONFLICT_FAIL, v9Book("emile", "Émile", "Rousseau", null, null))
            db.insert("AudioBooks", SQLiteDatabase.CONFLICT_FAIL, v9Book("dune", "DUNE", "Frank Herbert", null, null))
            db.insert("AudioBooks", SQLiteDatabase.CONFLICT_FAIL, v9Book("pct", "100% Pure", "Someone", null, null))
        }

        val database = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            TEST_DB,
        ).addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build()
        helper.closeWhenFinished(database)

        suspend fun shelfSearch(query: String) = database.audioBookDao().getFilteredBooks(
            SimpleSQLiteQuery(
                buildLibrarySql(tab = 0, hideFinished = false, downloadedOnly = false, hasSearch = true),
                buildLibrarySqlArgs(LIBRARY, query),
            )
        ).map { it.id }.toSet()

        assertEquals(setOf("emile"), shelfSearch("Emile"))
        assertEquals(setOf("emile"), shelfSearch("émile"))
        assertEquals(setOf("dune"), shelfSearch("dune"))
        assertEquals(setOf("pct"), shelfSearch("%"))
    }

    private fun v9Book(id: String, title: String, author: String?, seriesName: String?, narrator: String?) =
        ContentValues().apply {
            put("Id", id)
            put("LibraryId", LIBRARY)
            put("IsLocal", 0)
            put("Title", title)
            put("Author", author)
            put("SeriesName", seriesName)
            put("Narrator", narrator)
            put("DurationSeconds", 120.0)
            put("Progress", 0.25)
            put("GenresJson", "[\"Horror\"]")
        }

    private companion object {
        const val TEST_DB = "migration-upgrade-test.db"
        const val LIBRARY = "lib"
    }
}
