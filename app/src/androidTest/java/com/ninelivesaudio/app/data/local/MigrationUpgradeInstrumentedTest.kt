package com.ninelivesaudio.app.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.local.entity.LibraryAuthorEntity
import com.ninelivesaudio.app.data.local.entity.toShelfBook
import com.ninelivesaudio.app.data.local.migration.ALL_MIGRATIONS
import com.ninelivesaudio.app.data.local.migration.MIGRATION_10_11
import com.ninelivesaudio.app.data.local.migration.MIGRATION_9_10
import com.ninelivesaudio.app.data.local.migration.SEARCH_BACKFILL_PAGE_SIZE
import com.ninelivesaudio.app.data.repository.buildLibrarySql
import com.ninelivesaudio.app.data.repository.buildLibrarySqlArgs
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.AudioFile
import com.ninelivesaudio.app.domain.model.Chapter
import com.ninelivesaudio.app.domain.model.LibraryAuthor
import com.ninelivesaudio.app.ui.library.AuthorDirectory
import com.ninelivesaudio.app.ui.library.SortMode
import com.ninelivesaudio.app.ui.library.ViewMode
import com.ninelivesaudio.app.ui.library.buildGroupedSections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real v9 and v10 databases, built from the exported schema JSON, upgraded
 * through the app's migrations. Room validates each result against the
 * entities, so a column or table that differs from what an entity declares
 * fails here instead of on a user's phone.
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

    @Test
    fun tenToElevenKeepsEveryRowAndAddsAnEmptyAuthorList() {
        val before = helper.createDatabase(TEST_DB, 10).use { db ->
            seedV10(db)
            dumpV10(db)
        }
        assertEquals(V10_TABLES.map { it to V10_ROW_COUNTS.getValue(it) }, before.map { (t, rows) -> t to rows.size })

        val db = helper.runMigrationsAndValidate(TEST_DB, 11, true, MIGRATION_10_11)

        assertEquals(before, dumpV10(db))
        db.query("SELECT COUNT(*) FROM LibraryAuthors").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.query("PRAGMA user_version").use { it.moveToFirst(); assertEquals(11, it.getInt(0)) }
    }

    @Test
    fun anUpgradedLibraryListsEveryBookUnderItsAuthor() = runBlocking {
        helper.createDatabase(TEST_DB, 10).use(::seedV10)

        val database = openWithAllMigrations()
        val books = database.audioBookDao().getFilteredBooks(
            SimpleSQLiteQuery(
                buildLibrarySql(tab = 0, hideFinished = false, downloadedOnly = false, hasSearch = false),
                buildLibrarySqlArgs(LIBRARY, ""),
            )
        ).map { it.toShelfBook() }
        val sections = buildGroupedSections(books, ViewMode.AUTHOR, SortMode.TITLE_AZ)

        assertEquals(SERVER_BOOK_IDS, books.map { it.id }.toSet())
        assertEquals(SERVER_BOOK_IDS, sections.flatMap { s -> s.books.map { it.id } }.toSet())
        assertEquals(
            listOf("Coretta Scott King", "John", "Martin Luther King, Jr.", "Neil Gaiman", "Smith", "Terry Pratchett", "Unknown Author"),
            sections.map { it.title },
        )
    }

    @Test
    fun anUpgradedLibraryGroupsByAuthorOnceTheListIsStored() = runBlocking {
        helper.createDatabase(TEST_DB, 10).use(::seedV10)

        val database = openWithAllMigrations()
        database.libraryAuthorDao().replaceForLibrary(
            LIBRARY,
            listOf(
                LibraryAuthorEntity(LIBRARY, "au_sj", "Smith, John"),
                LibraryAuthorEntity(LIBRARY, "au_np", "Neil Gaiman"),
                LibraryAuthorEntity(LIBRARY, "au_tp", "Terry Pratchett"),
            ),
        )
        val directory = AuthorDirectory(
            database.libraryAuthorDao().getForLibrary(LIBRARY).map { LibraryAuthor(it.authorId, it.name) }
        )
        val books = database.audioBookDao().getFilteredBooks(
            SimpleSQLiteQuery(
                buildLibrarySql(tab = 0, hideFinished = false, downloadedOnly = false, hasSearch = false),
                buildLibrarySqlArgs(LIBRARY, ""),
            )
        ).map { it.toShelfBook() }
        val sections = buildGroupedSections(books, ViewMode.AUTHOR, SortMode.TITLE_AZ, directory)
        val titles = sections.map { it.title }

        assertTrue(titles.toString(), "Smith, John" in titles)
        assertTrue(titles.toString(), "Smith" !in titles && "John" !in titles)
        assertEquals(SERVER_BOOK_IDS, sections.flatMap { s -> s.books.map { it.id } }.toSet())
        assertEquals(
            listOf("Coretta Scott King", "Martin Luther King, Jr.", "Neil Gaiman", "Smith, John", "Terry Pratchett", "Unknown Author"),
            titles,
        )
    }

    private fun openWithAllMigrations(): AppDatabase {
        val database = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            TEST_DB,
        ).addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build()
        helper.closeWhenFinished(database)
        return database
    }

    /**
     * A v10 database shaped like a real install: a server library and a local
     * folder library, server books with co-authored, "Last, First", suffixed
     * and missing author lines (one downloaded), local books live and
     * archived, and a row in every other table.
     */
    private fun seedV10(db: SupportSQLiteDatabase) {
        db.insert("Libraries", SQLiteDatabase.CONFLICT_FAIL, ContentValues().apply {
            put("Id", LIBRARY)
            put("Name", "Audiobooks")
            put("IsLocal", 0)
            put("DisplayOrder", 1)
            put("Icon", "audiobookshelf")
            put("MediaType", "book")
            put("FoldersJson", "[{\"id\":\"fol_1\",\"fullPath\":\"/audiobooks\"}]")
        })
        db.insert("Libraries", SQLiteDatabase.CONFLICT_FAIL, ContentValues().apply {
            put("Id", LOCAL_LIBRARY)
            put("Name", "Audiobooks (Local)")
            put("IsLocal", 1)
            put("FolderUri", LOCAL_TREE)
            put("DisplayOrder", 0)
            put("Icon", "folder")
            put("MediaType", "book")
        })
        serverBook(db, "omens", "Good Omens", "Terry Pratchett, Neil Gaiman")
        serverBook(db, "smith", "A Smith Book", "Smith, John", downloadedTo = "/data/user/0/app/files/downloads/smith")
        serverBook(db, "king", "A Testament of Hope", "Martin Luther King, Jr., Coretta Scott King")
        serverBook(db, "anon", "Beowulf", null)
        localBook(db, "local-tolkien", "The Hobbit", "Tolkien, J.R.R.", folder = "The Hobbit", archivedAt = null)
        localBook(db, "local-gone", "Carmilla", "J. Sheridan Le Fanu", folder = "Carmilla", archivedAt = 1_791_039_600_000L)
        db.insert("PlaybackProgress", SQLiteDatabase.CONFLICT_FAIL, ContentValues().apply {
            put("AudioBookId", "omens")
            put("PositionSeconds", 120.0)
            put("IsFinished", 0)
            put("UpdatedAt", "2026-10-20T19:02:11Z")
        })
        db.insert("PlaybackProgress", SQLiteDatabase.CONFLICT_FAIL, ContentValues().apply {
            put("AudioBookId", "local-tolkien")
            put("PositionSeconds", 1234.5)
            put("IsFinished", 0)
            put("UpdatedAt", "2026-10-21T07:15:00Z")
        })
        db.insert("PendingProgressUpdates", SQLiteDatabase.CONFLICT_FAIL, ContentValues().apply {
            put("ItemId", "omens")
            put("CurrentTime", 120.0)
            put("IsFinished", 0)
            put("Duration", 3600.0)
            put("IsAtomic", 1)
            put("Timestamp", "2026-10-20T19:02:11Z")
        })
        db.insert("DownloadItems", SQLiteDatabase.CONFLICT_FAIL, ContentValues().apply {
            put("Id", "dl-smith")
            put("AudioBookId", "smith")
            put("Title", "A Smith Book")
            put("Status", 3)
            put("TotalBytes", 52_428_800L)
            put("DownloadedBytes", 52_428_800L)
            put("StartedAt", "2026-10-19T10:00:00Z")
            put("CompletedAt", "2026-10-19T10:04:00Z")
            put("FilesToDownloadJson", "[\"ino_1\"]")
        })
        for ((started, seconds) in listOf(1_790_900_000_000L to 1800.0, 1_790_990_000_000L to 600.0)) {
            db.insert("LocalListeningSessions", SQLiteDatabase.CONFLICT_FAIL, ContentValues().apply {
                put("AudioBookId", "local-tolkien")
                put("LibraryId", LOCAL_LIBRARY)
                put("StartedAt", started)
                put("UpdatedAt", started + (seconds * 1000).toLong() + 20_000L)
                put("TimeListening", seconds)
                put("CurrentTime", 1234.5)
                put("DisplayTitle", "The Hobbit")
            })
        }
        for ((time, title) in listOf(60.0 to "Riddles", 900.0 to "Smaug")) {
            db.insert("LocalBookmarks", SQLiteDatabase.CONFLICT_FAIL, ContentValues().apply {
                put("AudioBookId", "local-tolkien")
                put("Title", title)
                put("Time", time)
                put("CreatedAt", 1_790_900_000_000L + time.toLong())
            })
        }
    }

    private fun serverBook(db: SupportSQLiteDatabase, id: String, title: String, author: String?, downloadedTo: String? = null) {
        db.insert("AudioBooks", SQLiteDatabase.CONFLICT_FAIL, ContentValues().apply {
            put("Id", id)
            put("LibraryId", LIBRARY)
            put("IsLocal", 0)
            put("Title", title)
            put("Author", author)
            put("Narrator", "A Narrator")
            put("CoverPath", "/api/items/$id/cover")
            put("DurationSeconds", 3600.0)
            put("AddedAt", "2026-09-01T12:00:00Z")
            put("AudioFilesJson", "[]")
            put("Progress", if (id == "omens") 0.0333 else 0.0)
            put("IsDownloaded", if (downloadedTo != null) 1 else 0)
            put("LocalPath", downloadedTo)
            put("LocalCoverPath", downloadedTo?.let { "file://$it/cover.jpg" })
            put("GenresJson", "[\"Fantasy\"]")
            put("TagsJson", "[]")
            put("ChaptersJson", "[]")
            put("SearchText", bookSearchText(title, author, null, "A Narrator"))
        })
    }

    private fun localBook(db: SupportSQLiteDatabase, id: String, title: String, author: String, folder: String, archivedAt: Long?) {
        val shaped = AudioBook(
            id = id,
            audioFiles = listOf("01.mp3" to 1_000_000L, "02.mp3" to 2_000_000L).mapIndexed { i, (name, size) ->
                AudioFile(
                    id = "$id-$i", ino = "", index = i, duration = 90.seconds, filename = name,
                    localPath = "$LOCAL_TREE/document/primary%3AAudiobooks%2F${folder.replace(" ", "%20")}%2F$name",
                    mimeType = "audio/mpeg", size = size,
                )
            },
            chapters = listOf(Chapter(id = 0, start = 0.0, end = 90.0, title = "01"), Chapter(id = 1, start = 90.0, end = 180.0, title = "02")),
        ).toEntity()
        db.insert("AudioBooks", SQLiteDatabase.CONFLICT_FAIL, ContentValues().apply {
            put("Id", id)
            put("LibraryId", LOCAL_LIBRARY)
            put("IsLocal", 1)
            put("Title", title)
            put("Author", author)
            put("CoverPath", "file:///data/user/0/app/files/local_covers/$id.jpg")
            put("DurationSeconds", 180.0)
            put("AudioFilesJson", shaped.audioFilesJson)
            put("CurrentTimeSeconds", if (archivedAt == null) 1234.5 else 45.0)
            put("Progress", if (archivedAt == null) 0.4 else 0.25)
            put("IsDownloaded", 1)
            put("ArchivedAt", archivedAt)
            put("GenresJson", "[]")
            put("TagsJson", "[]")
            put("ChaptersJson", shaped.chaptersJson)
            put("SearchText", bookSearchText(title, author, null, null))
        })
    }

    /** Every v10 table, each row as its column values, in a fixed order. */
    private fun dumpV10(db: SupportSQLiteDatabase): List<Pair<String, List<List<Any?>>>> =
        V10_TABLES.map { table ->
            table to db.query("SELECT * FROM $table ORDER BY 1").use { rows ->
                buildList {
                    while (rows.moveToNext()) add((0 until rows.columnCount).map { rows.valueAt(it) })
                }
            }
        }

    private fun Cursor.valueAt(column: Int): Any? = when (getType(column)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(column)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(column)
        Cursor.FIELD_TYPE_BLOB -> getBlob(column).toList()
        else -> "${getColumnName(column)}=${getString(column)}"
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
        const val LOCAL_LIBRARY = "local-lib"
        const val LOCAL_TREE = "content://com.android.externalstorage.documents/tree/primary%3AAudiobooks"
        val SERVER_BOOK_IDS = setOf("omens", "smith", "king", "anon")
        val V10_TABLES = listOf(
            "Libraries", "AudioBooks", "DownloadItems", "PlaybackProgress",
            "PendingProgressUpdates", "LocalListeningSessions", "LocalBookmarks",
        )
        val V10_ROW_COUNTS = mapOf(
            "Libraries" to 2, "AudioBooks" to 6, "DownloadItems" to 1, "PlaybackProgress" to 2,
            "PendingProgressUpdates" to 1, "LocalListeningSessions" to 2, "LocalBookmarks" to 2,
        )
    }
}
