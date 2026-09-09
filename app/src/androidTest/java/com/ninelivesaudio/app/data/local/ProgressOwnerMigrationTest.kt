package com.ninelivesaudio.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ninelivesaudio.app.data.local.migration.MIGRATION_8_9
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class ProgressOwnerMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrateV8ToV9PreservesProgressAndLeavesLegacyPendingOwnerNull() {
        helper.createDatabase(TEST_DB, 8).apply {
            execSQL(
                """
                INSERT INTO PendingProgressUpdates
                (Id, ItemId, CurrentTime, IsFinished, Duration, IsAtomic, Timestamp)
                VALUES (41, 'raw-pending', 123.5, 1, 456.0, 1, '2026-09-08T00:00:00Z')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO PlaybackProgress (AudioBookId, PositionSeconds, IsFinished, UpdatedAt)
                VALUES ('raw-remote', 12.5, 0, '2026-09-08T00:01:00Z')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO PlaybackProgress (AudioBookId, PositionSeconds, IsFinished, UpdatedAt)
                VALUES ('local-book', 78.0, 1, '2026-09-08T00:02:00Z')
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9)

        migrated.query("SELECT * FROM PendingProgressUpdates WHERE Id = 41").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(41L, cursor.getLong(cursor.getColumnIndexOrThrow("Id")))
            assertEquals("raw-pending", cursor.getString(cursor.getColumnIndexOrThrow("ItemId")))
            assertEquals(123.5, cursor.getDouble(cursor.getColumnIndexOrThrow("CurrentTime")), 0.0)
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("IsFinished")))
            assertEquals(456.0, cursor.getDouble(cursor.getColumnIndexOrThrow("Duration")), 0.0)
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("IsAtomic")))
            assertEquals("2026-09-08T00:00:00Z", cursor.getString(cursor.getColumnIndexOrThrow("Timestamp")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("OwnerKey")))
        }
        assertProgress(migrated, "raw-remote", 12.5, 0, "2026-09-08T00:01:00Z")
        assertProgress(migrated, "local-book", 78.0, 1, "2026-09-08T00:02:00Z")

        val pendingIndexColumns = mutableListOf<String>()
        migrated.query("PRAGMA index_info('idx_pending_owner_item_id')").use { cursor ->
            while (cursor.moveToNext()) {
                pendingIndexColumns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
        }
        assertEquals(listOf("OwnerKey", "ItemId", "Id"), pendingIndexColumns)

        migrated.query("PRAGMA table_info('PlaybackProgress')").use { cursor ->
            val columns = mutableListOf<String>()
            while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            assertFalse(columns.contains("OwnerKey"))
        }
        migrated.close()
    }

    private fun assertProgress(
        database: SupportSQLiteDatabase,
        itemId: String,
        position: Double,
        finished: Int,
        updatedAt: String,
    ) {
        database.query("SELECT * FROM PlaybackProgress WHERE AudioBookId = ?", arrayOf(itemId)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(position, cursor.getDouble(cursor.getColumnIndexOrThrow("PositionSeconds")), 0.0)
            assertEquals(finished, cursor.getInt(cursor.getColumnIndexOrThrow("IsFinished")))
            assertEquals(updatedAt, cursor.getString(cursor.getColumnIndexOrThrow("UpdatedAt")))
        }
    }

    private companion object {
        const val TEST_DB = "progress-owner-migration-test"
    }
}
