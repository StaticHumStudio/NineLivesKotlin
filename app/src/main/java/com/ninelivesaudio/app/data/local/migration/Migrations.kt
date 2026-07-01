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

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `LocalBookmarks` (
                `Id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `AudioBookId` TEXT NOT NULL,
                `Title` TEXT NOT NULL,
                `Time` REAL NOT NULL,
                `CreatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_local_bookmark_book` ON `LocalBookmarks` (`AudioBookId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_local_bookmark_book_time` ON `LocalBookmarks` (`AudioBookId`, `Time`)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // file:// URI of the cover persisted locally at download time, so
        // downloaded books render their cover with no network.
        db.execSQL("ALTER TABLE AudioBooks ADD COLUMN LocalCoverPath TEXT")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // LOCAL-mode soft-delete: epoch millis when the book's folder was unscanned.
        db.execSQL("ALTER TABLE AudioBooks ADD COLUMN ArchivedAt INTEGER")
    }
}

/**
 * All migrations to register with Room, in order.
 * Add new migrations here as they are created.
 */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
)
