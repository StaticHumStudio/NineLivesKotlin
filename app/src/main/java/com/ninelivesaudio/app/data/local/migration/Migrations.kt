package com.ninelivesaudio.app.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migration definitions.
 *
 * Each migration should:
 * 1. Be defined as a val in this file
 * 2. Be added to the migrations list in AppDatabase
 * 3. Have the database version bumped in @Database annotation
 * 4. Be tested by running the app after a fresh install on the old version
 *
 * Schema JSON files are exported to app/schemas/ for validation.
 */

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE AudioBooks ADD COLUMN IsLocal INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE Libraries ADD COLUMN IsLocal INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE Libraries ADD COLUMN FolderUri TEXT")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `LocalListeningSessions` (
                `Id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `AudioBookId` TEXT NOT NULL,
                `LibraryId` TEXT NOT NULL,
                `StartedAt` INTEGER NOT NULL,
                `UpdatedAt` INTEGER NOT NULL,
                `TimeListening` REAL NOT NULL DEFAULT 0,
                `CurrentTime` REAL NOT NULL DEFAULT 0,
                `DisplayTitle` TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_local_session_book` ON `LocalListeningSessions` (`AudioBookId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_local_session_library` ON `LocalListeningSessions` (`LibraryId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_local_session_started` ON `LocalListeningSessions` (`StartedAt`)")
    }
}

/**
 * All migrations to register with Room, in order.
 * Add new migrations here as they are created.
 */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
)
