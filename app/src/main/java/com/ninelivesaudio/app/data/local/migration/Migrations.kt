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

/**
 * Migration stub for version 1 → 2.
 * Uncomment and implement when the first schema change is needed.
 */
// val MIGRATION_1_2 = object : Migration(1, 2) {
//     override fun migrate(db: SupportSQLiteDatabase) {
//         // Example: db.execSQL("ALTER TABLE AudioBooks ADD COLUMN NewColumn TEXT DEFAULT NULL")
//     }
// }

/**
 * All migrations to register with Room, in order.
 * Add new migrations here as they are created.
 */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    // MIGRATION_1_2,
)
