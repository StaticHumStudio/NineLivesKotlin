package com.ninelivesaudio.app.data.local.migration

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * Room refuses to open an upgraded database when a migration is missing or
 * builds a different schema than the entities describe. Neither shows up until
 * a real install upgrades, so these pin the chain and the 8 to 9 SQL here.
 */
class MigrationsTest {

    private val schemaDir = File("schemas/com.ninelivesaudio.app.data.local.AppDatabase")

    @Test
    fun `migrations run 1 to the newest exported schema with no gaps`() {
        val steps = ALL_MIGRATIONS.map { it.startVersion to it.endVersion }
        val newestSchema = schemaDir.listFiles().orEmpty()
            .mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
            .maxOrNull()

        assertEquals((1 until 9).map { it to it + 1 }, steps)
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
