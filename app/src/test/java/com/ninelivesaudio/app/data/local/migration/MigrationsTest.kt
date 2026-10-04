package com.ninelivesaudio.app.data.local.migration

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import com.ninelivesaudio.app.data.local.bookSearchText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * Room refuses to open an upgraded database when a migration is missing or
 * builds a different schema than the entities describe. Neither shows up until
 * a real install upgrades, so these pin the chain and the SQL here. The
 * instrumented MigrationUpgradeInstrumentedTest runs 9 to 10 on real SQLite.
 */
class MigrationsTest {

    private val schemaDir = File("schemas/com.ninelivesaudio.app.data.local.AppDatabase")

    @Test
    fun `migrations run 1 to the newest exported schema with no gaps`() {
        val steps = ALL_MIGRATIONS.map { it.startVersion to it.endVersion }
        val newestSchema = schemaDir.listFiles().orEmpty()
            .mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
            .maxOrNull()

        assertEquals((1 until 10).map { it to it + 1 }, steps)
        assertEquals(newestSchema, steps.last().second)
    }

    @Test
    fun `8 to 9 only creates the recently played index Room expects`() {
        val executed = mutableListOf<String>()
        MIGRATION_8_9.migrate(recordingDatabase(executed))

        val expected = Regex("\"createSql\":\\s*\"(CREATE INDEX IF NOT EXISTS `idx_playback_progress_updated`[^\"]*)\"")
            .find(File(schemaDir, "9.json").readText())
            ?.groupValues?.get(1)
            ?.replace("\${TABLE_NAME}", "PlaybackProgress")

        assertTrue("index missing from the exported v9 schema", expected != null)
        assertEquals(listOf(expected), executed)
    }

    @Test
    fun `9 to 10 adds the search column the way the exported v10 schema describes it`() {
        val executed = mutableListOf<String>()
        MIGRATION_9_10.migrate(backfillDatabase(executed, rows = emptyList(), updates = mutableListOf()))

        val field = Regex("\\{[^{}]*\"columnName\":\\s*\"SearchText\"[^{}]*\\}")
            .find(File(schemaDir, "10.json").readText())
            ?.value
        assertTrue("SearchText missing from the exported v10 schema", field != null)
        assertTrue(field, Regex("\"affinity\":\\s*\"TEXT\"").containsMatchIn(field!!))
        assertTrue(field, Regex("\"notNull\":\\s*true").containsMatchIn(field))
        assertTrue(field, Regex("\"defaultValue\":\\s*\"''\"").containsMatchIn(field))
        assertEquals(listOf("ALTER TABLE AudioBooks ADD COLUMN SearchText TEXT NOT NULL DEFAULT ''"), executed)
    }

    @Test
    fun `9 to 10 fills the search column for every existing book`() {
        // More rows than one backfill page, so the paging is exercised too.
        val rows = (1..(SEARCH_BACKFILL_PAGE_SIZE + 3)).map { n ->
            BookRow(
                rowId = n * 2L,
                id = "book-$n",
                title = if (n == 1) "Émile" else "Title $n",
                author = if (n == 2) null else "AUTHOR $n",
                seriesName = if (n == 3) "Œuvres" else null,
                narrator = if (n == 4) "Zoë" else null,
            )
        }
        val updates = mutableListOf<Pair<String, String>>()

        MIGRATION_9_10.migrate(backfillDatabase(mutableListOf(), rows, updates))

        assertEquals(rows.map { it.id }, updates.map { it.first })
        rows.zip(updates).forEach { (row, update) ->
            assertEquals(bookSearchText(row.title, row.author, row.seriesName, row.narrator), update.second)
        }
        assertEquals("emile", updates.first().second.substringBefore('\u001F'))
    }

    private data class BookRow(
        val rowId: Long,
        val id: String,
        val title: String,
        val author: String?,
        val seriesName: String?,
        val narrator: String?,
    )

    /**
     * A database holding [rows] in the AudioBooks table: it records execSQL,
     * answers the backfill's paged read, and records each UPDATE as
     * (Id, SearchText) in [updates].
     */
    private fun backfillDatabase(
        executed: MutableList<String>,
        rows: List<BookRow>,
        updates: MutableList<Pair<String, String>>,
    ): SupportSQLiteDatabase = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, args ->
        when (method.name) {
            "execSQL" -> {
                executed += args!![0] as String
                null
            }
            "query" -> {
                val sql = args!![0] as String
                assertTrue(sql, sql.startsWith("SELECT rowid, Id, Title, Author, SeriesName, Narrator FROM AudioBooks"))
                val bind = args[1] as Array<*>
                val after = (bind[0] as Number).toLong()
                val limit = (bind[1] as Number).toInt()
                cursorOf(rows.filter { it.rowId > after }.sortedBy { it.rowId }.take(limit))
            }
            "compileStatement" -> {
                assertEquals("UPDATE AudioBooks SET SearchText = ? WHERE rowid = ?", args!![0])
                recordingUpdate(rows, updates)
            }
            "toString" -> "BackfillDatabase"
            else -> error("unexpected call ${method.name}")
        }
    } as SupportSQLiteDatabase

    private fun cursorOf(page: List<BookRow>): Cursor {
        var position = -1
        return Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, method, args ->
            when (method.name) {
                "moveToNext" -> ++position < page.size
                "getLong" -> {
                    assertEquals(0, args!![0])
                    page[position].rowId
                }
                "getString" -> page[position].let { row ->
                    when (args!![0] as Int) {
                        1 -> row.id
                        2 -> row.title
                        3 -> row.author
                        4 -> row.seriesName
                        5 -> row.narrator
                        else -> error("no column ${args[0]}")
                    }
                }
                "close" -> null
                "toString" -> "PageCursor"
                else -> error("unexpected cursor call ${method.name}")
            }
        } as Cursor
    }

    private fun recordingUpdate(rows: List<BookRow>, updates: MutableList<Pair<String, String>>): SupportSQLiteStatement {
        var text: String? = null
        var rowId: Long? = null
        return Proxy.newProxyInstance(
            SupportSQLiteStatement::class.java.classLoader,
            arrayOf(SupportSQLiteStatement::class.java),
        ) { _, method, args ->
            when (method.name) {
                "bindString" -> {
                    assertEquals(1, args!![0])
                    text = args[1] as String
                    null
                }
                "bindLong" -> {
                    assertEquals(2, args!![0])
                    rowId = args[1] as Long
                    null
                }
                "executeUpdateDelete" -> {
                    updates += rows.single { it.rowId == rowId }.id to text!!
                    1
                }
                "clearBindings" -> {
                    text = null
                    rowId = null
                    null
                }
                "close" -> null
                "toString" -> "RecordingUpdate"
                else -> error("unexpected statement call ${method.name}")
            }
        } as SupportSQLiteStatement
    }

    /** A database that records execSQL and fails on anything else. */
    private fun recordingDatabase(executed: MutableList<String>): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    executed += args!![0] as String
                    null
                }
                "toString" -> "RecordingDatabase"
                else -> error("migration 8 to 9 should only run SQL, it called ${method.name}")
            }
        } as SupportSQLiteDatabase
}
