# LOCAL Archive Shelf Implementation Plan (#40)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In LOCAL mode, when a book's source folder is unscanned, soft-delete (archive) it instead of hard-deleting, preserving its cover and listening history, with an Archive view, a stats toggle, and explicit Delete-forever.

**Architecture:** Add a nullable `ArchivedAt` timestamp to `AudioBookEntity` (migration v5→v6). The single hard-delete site (`removeMissingLocalBooks`) becomes a soft-delete that stamps `ArchivedAt`; re-importing the same folder (stable sha256 id) clears it. Folder cover images (content:// SAF URIs that die on unscan) are copied to `filesDir/local_covers/<id>.jpg` during scan so covers survive. Library excludes archived books from live tabs and adds a LOCAL-only Archive tab; BookDetail disables Play for archived; Dossier gains a stats toggle; Delete-forever cascades book + sessions + bookmarks + cover file.

**Tech Stack:** Kotlin, Jetpack Compose, Room (SQLite, WAL), Hilt, Coil, kotlinx.serialization. Plain JUnit4 for unit tests.

## Global Constraints

- LOCAL mode only. AUDIOBOOKSHELF mode is unaffected (`ArchivedAt` stays null for server books; the live-tab `ArchivedAt IS NULL` clause is a no-op for them).
- Migration is **v5 → v6**; the new column is `ArchivedAt INTEGER` (nullable, no default). Schema v5 already exists (PR #49's `LocalCoverPath`).
- Test style is **pure decision functions + plain JUnit4**. No MockK, Mockito, Robolectric, or mock web server (none are on the classpath). DAO/Room/Compose/scanner are verified by build + on-device run, not unit tests.
- Local book ids are stable per path: `local_book_<sha256(rootUri/folderName)>` (or `/filename`). Do not change id generation.
- Local covers live in `AudioBookEntity.coverPath` (a `file://` after this change). `effectiveCoverPath = localCoverPath ?: coverPath` already falls back to it; do not move local covers into `localCoverPath`.
- Build offline: `./gradlew :app:testDebugUnitTest --offline` and `./gradlew :app:assembleDebug --offline`. ADB at `~/Android/Sdk/platform-tools/adb`; debug app id `com.ninelivesaudio.app.debug`. When reading the DB off-device, pull `audiobookshelf.db` + `-wal` + `-shm` (Room uses WAL).
- Every commit message ends with:
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01Mz5R7Tue2qsDw34WrkcyUS
  ```
- Branch: `feat/local-archive-shelf` (already created off `origin/master`; this plan + the design spec are already committed on it).

---

## File Structure

**Schema / data**
- Modify `app/src/main/java/com/ninelivesaudio/app/data/local/entity/AudioBookEntity.kt` — add `ArchivedAt` column.
- Modify `app/src/main/java/com/ninelivesaudio/app/domain/model/AudioBook.kt` — add `archivedAt` + `isArchived`.
- Modify `app/src/main/java/com/ninelivesaudio/app/data/local/converter/EntityMappers.kt` — map `archivedAt`.
- Modify `app/src/main/java/com/ninelivesaudio/app/data/local/migration/Migrations.kt` — `MIGRATION_5_6` + `ALL_MIGRATIONS`.
- Modify `app/src/main/java/com/ninelivesaudio/app/data/local/AppDatabase.kt` — version 5 → 6.
- Modify `app/src/main/java/com/ninelivesaudio/app/data/local/dao/AudioBookDao.kt` — archive queries.
- Modify `app/src/main/java/com/ninelivesaudio/app/data/local/dao/LocalListeningSessionDao.kt` — `deleteByAudioBookId`.

**Repository / scan**
- Modify `app/src/main/java/com/ninelivesaudio/app/data/repository/AudioBookRepository.kt` — `idsToArchive`, soft-delete, `deleteLocalBookForever`, live-tab SQL.
- Modify `app/src/main/java/com/ninelivesaudio/app/service/local/LocalMetadataExtractor.kt` — `persistFolderCover` + `writeLocalCoverFile`.
- Modify `app/src/main/java/com/ninelivesaudio/app/service/local/LocalLibraryScanner.kt` — persist folder covers (line 204).

**UI**
- Modify `app/src/main/java/com/ninelivesaudio/app/ui/library/LibraryViewModel.kt` + `LibraryScreen.kt` — Archive tab, exclusion, dim/chip.
- Modify `app/src/main/java/com/ninelivesaudio/app/ui/bookdetail/BookDetailViewModel.kt` + `BookDetailScreen.kt` — archived state.
- Modify `app/src/main/java/com/ninelivesaudio/app/ui/dossier/NightwatchDossierViewModel.kt` — stats filter.
- Modify `app/src/main/java/com/ninelivesaudio/app/domain/model/AppSettings.kt` + `ui/settings/SettingsViewModel.kt` + `SettingsScreen.kt` — stats toggle.

**Tests (new files)**
- `app/src/test/java/com/ninelivesaudio/app/domain/model/AudioBookArchiveTest.kt`
- `app/src/test/java/com/ninelivesaudio/app/data/repository/IdsToArchiveTest.kt`
- `app/src/test/java/com/ninelivesaudio/app/service/local/LocalCoverFileTest.kt`
- `app/src/test/java/com/ninelivesaudio/app/data/repository/LibrarySqlArchiveTest.kt`
- `app/src/test/java/com/ninelivesaudio/app/ui/bookdetail/BookPlayabilityTest.kt`
- `app/src/test/java/com/ninelivesaudio/app/ui/dossier/StatsArchiveFilterTest.kt`

---

## Task 1: ArchivedAt schema + domain + migration (v5→v6)

**Files:**
- Test: `app/src/test/java/com/ninelivesaudio/app/domain/model/AudioBookArchiveTest.kt`
- Modify: `app/src/main/java/com/ninelivesaudio/app/domain/model/AudioBook.kt:27-29` (add field), `:30` (add computed prop)
- Modify: `app/src/main/java/com/ninelivesaudio/app/data/local/entity/AudioBookEntity.kt` (add column after `LocalCoverPath`)
- Modify: `app/src/main/java/com/ninelivesaudio/app/data/local/converter/EntityMappers.kt:41-43,60-62` (map both ways)
- Modify: `app/src/main/java/com/ninelivesaudio/app/data/local/migration/Migrations.kt` (add `MIGRATION_5_6`, append to `ALL_MIGRATIONS`)
- Modify: `app/src/main/java/com/ninelivesaudio/app/data/local/AppDatabase.kt:18` (`version = 6`)

**Interfaces:**
- Produces: `AudioBook.archivedAt: Long?`, `AudioBook.isArchived: Boolean`, `AudioBookEntity.archivedAt: Long?`, `MIGRATION_5_6`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/ninelivesaudio/app/domain/model/AudioBookArchiveTest.kt`:
```kotlin
package com.ninelivesaudio.app.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A book is archived exactly when it has an archivedAt timestamp. */
class AudioBookArchiveTest {

    @Test
    fun `archived when archivedAt is set`() {
        assertTrue(AudioBook(id = "1", archivedAt = 1_700_000_000_000L).isArchived)
    }

    @Test
    fun `not archived when archivedAt is null`() {
        assertFalse(AudioBook(id = "1", archivedAt = null).isArchived)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ninelivesaudio.app.domain.model.AudioBookArchiveTest" --offline`
Expected: FAIL — compile error, `archivedAt`/`isArchived` unresolved.

- [ ] **Step 3: Add the domain field + computed property**

In `AudioBook.kt`, add to the constructor (after `localCoverPath`/`localPath`, before `lastPlayedAt`):
```kotlin
    val archivedAt: Long? = null, // epoch millis; null = live (LOCAL mode soft-delete)
```
And inside the class body (near `effectiveCoverPath`):
```kotlin
    /** LOCAL mode: book's source folder was unscanned; row kept for history. */
    val isArchived: Boolean
        get() = archivedAt != null
```

- [ ] **Step 4: Add the entity column**

In `AudioBookEntity.kt`, add after the `LocalCoverPath` column:
```kotlin
    @ColumnInfo(name = "ArchivedAt")
    val archivedAt: Long? = null,
```

- [ ] **Step 5: Map both directions**

In `EntityMappers.kt`, add `archivedAt = archivedAt,` to both `AudioBookEntity.toDomain()` (after `localCoverPath = localCoverPath,`) and `AudioBook.toEntity()` (after `localCoverPath = localCoverPath,`).

- [ ] **Step 6: Add the migration and bump the version**

In `Migrations.kt`, add before `ALL_MIGRATIONS`:
```kotlin
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // LOCAL-mode soft-delete: epoch millis when the book's folder was unscanned.
        db.execSQL("ALTER TABLE AudioBooks ADD COLUMN ArchivedAt INTEGER")
    }
}
```
Append `MIGRATION_5_6,` to the `ALL_MIGRATIONS` array. In `AppDatabase.kt`, change `version = 5` to `version = 6`.

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ninelivesaudio.app.domain.model.AudioBookArchiveTest" --offline`
Expected: PASS. Also confirm `app/schemas/com.ninelivesaudio.app.data.local.AppDatabase/6.json` now exists with `ArchivedAt`.

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "Add ArchivedAt column + isArchived (Room v5->v6) for the Archive shelf" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Mz5R7Tue2qsDw34WrkcyUS"
```

---

## Task 2: `idsToArchive` pure function

**Files:**
- Test: `app/src/test/java/com/ninelivesaudio/app/data/repository/IdsToArchiveTest.kt`
- Modify: `app/src/main/java/com/ninelivesaudio/app/data/repository/AudioBookRepository.kt` (add top-level `internal fun` at end of file, near `mergeSyncedBook`)

**Interfaces:**
- Produces: `internal fun idsToArchive(existingLocalIds: List<String>, scannedIds: List<String>): List<String>`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/ninelivesaudio/app/data/repository/IdsToArchiveTest.kt`:
```kotlin
package com.ninelivesaudio.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/** On rescan, archive exactly the existing local books that the scan no longer found. */
class IdsToArchiveTest {

    @Test
    fun `archives existing ids missing from the scan`() {
        val result = idsToArchive(existingLocalIds = listOf("a", "b", "c"), scannedIds = listOf("a", "c"))
        assertEquals(listOf("b"), result)
    }

    @Test
    fun `archives nothing when all existing ids were scanned`() {
        assertEquals(emptyList<String>(), idsToArchive(listOf("a", "b"), listOf("a", "b", "z")))
    }

    @Test
    fun `archives all existing ids when the scan is empty`() {
        assertEquals(listOf("a", "b"), idsToArchive(listOf("a", "b"), emptyList()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ninelivesaudio.app.data.repository.IdsToArchiveTest" --offline`
Expected: FAIL — `idsToArchive` unresolved.

- [ ] **Step 3: Implement**

At the end of `AudioBookRepository.kt` (top-level, after `mergeSyncedBook`):
```kotlin
/**
 * The local book ids to archive after a scan: those that exist locally but were
 * not seen in the latest scan. Pure, so it is unit-testable without the DB.
 */
internal fun idsToArchive(existingLocalIds: List<String>, scannedIds: List<String>): List<String> {
    val scanned = scannedIds.toHashSet()
    return existingLocalIds.filterNot { it in scanned }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ninelivesaudio.app.data.repository.IdsToArchiveTest" --offline`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add idsToArchive pure helper for soft-delete diff" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Mz5R7Tue2qsDw34WrkcyUS"
```

---

## Task 3: DAO archive queries + soft-delete on unscan

No new unit test (wires the Task 2 function into Room/repo; integration verified in Task 9). Reviewer gate: build green + correct wiring.

**Files:**
- Modify: `app/src/main/java/com/ninelivesaudio/app/data/local/dao/AudioBookDao.kt` (add 2 queries near the other delete/query methods)
- Modify: `app/src/main/java/com/ninelivesaudio/app/data/repository/AudioBookRepository.kt:238-245` (`removeMissingLocalBooks`)

**Interfaces:**
- Consumes: `idsToArchive` (Task 2).
- Produces: `AudioBookDao.getLocalIdsByLibrary(libraryId): List<String>`, `AudioBookDao.archiveByIds(ids, archivedAt)`.

- [ ] **Step 1: Add the DAO queries**

In `AudioBookDao.kt`, after `deleteMissingLocalBooks` (line 61):
```kotlin
    /** Ids of all LOCAL books in a library (live or archived). */
    @Query("SELECT Id FROM AudioBooks WHERE LibraryId = :libraryId AND IsLocal = 1")
    suspend fun getLocalIdsByLibrary(libraryId: String): List<String>

    /** Soft-delete: stamp ArchivedAt on the given books (skips already-archived). */
    @Query("UPDATE AudioBooks SET ArchivedAt = :archivedAt WHERE Id IN (:ids) AND ArchivedAt IS NULL")
    suspend fun archiveByIds(ids: List<String>, archivedAt: Long)
```

- [ ] **Step 2: Switch `removeMissingLocalBooks` to soft-delete**

Replace the body of `removeMissingLocalBooks` in `AudioBookRepository.kt`:
```kotlin
    /**
     * Archive local books that were not present in the latest scan (LOCAL-mode
     * soft-delete) instead of hard-deleting them, so their cover + history
     * survive. A returning folder re-imports with the same id and clears the
     * flag (see importLocalBooks).
     */
    suspend fun removeMissingLocalBooks(libraryId: String, scannedIds: List<String>) {
        val existing = audioBookDao.getLocalIdsByLibrary(libraryId)
        val toArchive = idsToArchive(existing, scannedIds)
        if (toArchive.isNotEmpty()) {
            audioBookDao.archiveByIds(toArchive, System.currentTimeMillis())
        }
    }
```

- [ ] **Step 3: Make re-import clear the flag (restore)**

In `importLocalBooks` (`AudioBookRepository.kt:223-235`), the upsert already overwrites the row from the scanned book whose `archivedAt` defaults to null, so a returning book is restored. Make it explicit by adding `archivedAt = null,` to the `book.copy(...)` block so intent is clear and refactor-safe:
```kotlin
                book.copy(
                    libraryId = libraryId,
                    isLocal = true,
                    isDownloaded = true,
                    archivedAt = null, // present in this scan => restore if it was archived
                    currentTime = existing?.currentTimeSeconds?.seconds ?: book.currentTime,
                    progress = existing?.progress ?: book.progress,
                    isFinished = existing?.isFinished?.let { it == 1 } ?: book.isFinished,
                ).toEntity()
```

- [ ] **Step 4: Build to verify it compiles**

Run: `./gradlew :app:testDebugUnitTest --offline`
Expected: BUILD SUCCESSFUL (all existing tests still pass).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Soft-delete local books on unscan instead of hard delete" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Mz5R7Tue2qsDw34WrkcyUS"
```

---

## Task 4: Persist folder covers on scan (durable file://)

**Files:**
- Test: `app/src/test/java/com/ninelivesaudio/app/service/local/LocalCoverFileTest.kt`
- Modify: `app/src/main/java/com/ninelivesaudio/app/service/local/LocalMetadataExtractor.kt` (add `writeLocalCoverFile` top-level + `persistFolderCover` method)
- Modify: `app/src/main/java/com/ninelivesaudio/app/service/local/LocalLibraryScanner.kt:204` (use the persisted cover)

**Interfaces:**
- Produces: `internal fun writeLocalCoverFile(bytes: ByteArray, coverDir: File, bookId: String): File`, `LocalMetadataExtractor.persistFolderCover(coverUri: String, bookId: String): String?`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/ninelivesaudio/app/service/local/LocalCoverFileTest.kt`:
```kotlin
package com.ninelivesaudio.app.service.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Local covers are written to <coverDir>/<bookId>.jpg so they survive unscan. */
class LocalCoverFileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `writes bytes to bookId dot jpg in the cover dir`() {
        val coverDir = tempFolder.newFolder("local_covers")
        val bytes = byteArrayOf(7, 8, 9)

        val file = writeLocalCoverFile(bytes, coverDir, "local_book_abc")

        assertEquals("local_book_abc.jpg", file.name)
        assertEquals(coverDir, file.parentFile)
        assertTrue(file.exists())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun `creates the cover dir if missing`() {
        val coverDir = java.io.File(tempFolder.root, "local_covers")
        val file = writeLocalCoverFile(byteArrayOf(1), coverDir, "x")
        assertTrue(file.exists())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ninelivesaudio.app.service.local.LocalCoverFileTest" --offline`
Expected: FAIL — `writeLocalCoverFile` unresolved.

- [ ] **Step 3: Implement the pure helper + the persist method**

In `LocalMetadataExtractor.kt`, add a top-level function (outside the class, after it) and refactor `extractEmbeddedCover` to use it:
```kotlin
/**
 * Write cover bytes to <coverDir>/<bookId>.jpg (creating the dir) and return the
 * file. Pure file IO, unit-testable without the Android framework.
 */
internal fun writeLocalCoverFile(bytes: ByteArray, coverDir: File, bookId: String): File {
    coverDir.mkdirs()
    val file = File(coverDir, "$bookId.jpg")
    file.writeBytes(bytes)
    return file
}
```
Replace the write block inside `extractEmbeddedCover` (lines 84-89) with:
```kotlin
            val coverDir = File(context.filesDir, "local_covers")
            Uri.fromFile(writeLocalCoverFile(artBytes, coverDir, bookId)).toString()
```
Then add the new method to the class (after `extractEmbeddedCover`):
```kotlin
    /**
     * Copy a folder cover image (a content:// SAF URI that dies when the folder
     * is unscanned) into app-private storage and return a durable file:// URI.
     * Returns null if [coverUri] is null/blank, already a file://, or unreadable.
     */
    fun persistFolderCover(coverUri: String?, bookId: String): String? {
        if (coverUri.isNullOrBlank()) return null
        val parsed = Uri.parse(coverUri)
        if (parsed.scheme == "file") return coverUri // already durable (embedded)
        return try {
            val bytes = context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
                ?: return null
            if (bytes.isEmpty()) return null
            val coverDir = File(context.filesDir, "local_covers")
            Uri.fromFile(writeLocalCoverFile(bytes, coverDir, bookId)).toString()
        } catch (e: Exception) {
            Log.w(TAG, "persistFolderCover failed for $bookId: ${e.message}")
            null
        }
    }
```

- [ ] **Step 4: Wire it into the scanner**

In `LocalLibraryScanner.kt:204`, change:
```kotlin
        val coverUri = findCoverImage(allChildren) ?: metadataExtractor.extractEmbeddedCover(firstFileUri, bookId)
```
to:
```kotlin
        val coverUri = metadataExtractor.persistFolderCover(findCoverImage(allChildren), bookId)
            ?: metadataExtractor.extractEmbeddedCover(firstFileUri, bookId)
```
Do the same at the single-file site (`LocalLibraryScanner.kt:235`) if it also calls `findCoverImage`; if that path only uses `extractEmbeddedCover`, leave it.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ninelivesaudio.app.service.local.LocalCoverFileTest" --offline`
Expected: PASS. Then `./gradlew :app:assembleDebug --offline` → BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Persist folder cover images to filesDir on scan so they survive unscan" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Mz5R7Tue2qsDw34WrkcyUS"
```

---

## Task 5: Library Archive tab + exclude archived from live shelf

**Files:**
- Test: `app/src/test/java/com/ninelivesaudio/app/data/repository/LibrarySqlArchiveTest.kt`
- Modify: `app/src/main/java/com/ninelivesaudio/app/data/repository/AudioBookRepository.kt:96-150` (extract `buildLibrarySql`, add archive clauses)
- Modify: `app/src/main/java/com/ninelivesaudio/app/ui/library/LibraryViewModel.kt:34-39` (enum), `:335-340` (tab→int)
- Modify: `app/src/main/java/com/ninelivesaudio/app/ui/library/LibraryScreen.kt` (tab row LOCAL-only Archive; dim + chip)

**Interfaces:**
- Produces: `internal fun buildLibrarySql(tab: Int, hideFinished: Boolean, downloadedOnly: Boolean, hasSearch: Boolean): String`; `LibraryTab.Archive` (tab int `4`).

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/ninelivesaudio/app/data/repository/LibrarySqlArchiveTest.kt`:
```kotlin
package com.ninelivesaudio.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live tabs hide archived books; the Archive tab shows only archived. */
class LibrarySqlArchiveTest {

    @Test
    fun `live All tab excludes archived`() {
        val sql = buildLibrarySql(tab = 0, hideFinished = false, downloadedOnly = false, hasSearch = false)
        assertTrue(sql.contains("ab.ArchivedAt IS NULL"))
        assertFalse(sql.contains("ab.ArchivedAt IS NOT NULL"))
    }

    @Test
    fun `archive tab shows only archived`() {
        val sql = buildLibrarySql(tab = 4, hideFinished = false, downloadedOnly = false, hasSearch = false)
        assertTrue(sql.contains("ab.ArchivedAt IS NOT NULL"))
        assertFalse(sql.contains("ab.ArchivedAt IS NULL"))
    }

    @Test
    fun `downloaded tab still excludes archived`() {
        val sql = buildLibrarySql(tab = 3, hideFinished = false, downloadedOnly = false, hasSearch = false)
        assertTrue(sql.contains("ab.IsDownloaded = 1"))
        assertTrue(sql.contains("ab.ArchivedAt IS NULL"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ninelivesaudio.app.data.repository.LibrarySqlArchiveTest" --offline`
Expected: FAIL — `buildLibrarySql` unresolved.

- [ ] **Step 3: Extract and extend the SQL builder**

In `AudioBookRepository.kt`, extract the `buildString { ... }` block from `getFilteredBooks` (lines 104-142) into a top-level pure function, adding the archive clause. The Archive tab is int `4`:
```kotlin
internal fun buildLibrarySql(
    tab: Int,
    hideFinished: Boolean,
    downloadedOnly: Boolean,
    hasSearch: Boolean,
): String = buildString {
    append("SELECT ab.*, pp.UpdatedAt AS lastPlayedAt FROM AudioBooks ab")
    append(" LEFT JOIN PlaybackProgress pp ON ab.Id = pp.AudioBookId")
    append(" WHERE ab.LibraryId = ?")

    // Archive visibility: the Archive tab shows only archived; every other tab
    // shows only live books.
    if (tab == 4) append(" AND ab.ArchivedAt IS NOT NULL")
    else append(" AND ab.ArchivedAt IS NULL")

    when (tab) {
        1 -> {
            append(" AND ab.Progress > 0")
            append(" AND ab.IsFinished = 0")
            append(" AND (CASE WHEN ab.Progress <= 1.0 THEN ab.Progress * 100.0 ELSE ab.Progress END) < 99.5")
        }
        2 -> {
            append(" AND (ab.IsFinished = 1 OR ab.Progress >= 1.0")
            append(" OR (CASE WHEN ab.Progress <= 1.0 THEN ab.Progress * 100.0 ELSE ab.Progress END) >= 99.5)")
        }
        3 -> {
            append(" AND ab.IsDownloaded = 1")
        }
    }

    if (hideFinished) {
        append(" AND ab.IsFinished = 0 AND ab.Progress < 1.0")
        append(" AND (CASE WHEN ab.Progress <= 1.0 THEN ab.Progress * 100.0 ELSE ab.Progress END) < 99.5")
    }
    if (downloadedOnly) {
        append(" AND ab.IsDownloaded = 1")
    }
    if (hasSearch) {
        append(" AND (ab.Title LIKE ? OR ab.Author LIKE ? OR ab.SeriesName LIKE ? OR ab.Narrator LIKE ?)")
    }
    append(" ORDER BY ab.Title")
}
```
Then replace the inline `buildString` in `getFilteredBooks` (the suspend method around line 104) with:
```kotlin
        val sql = buildLibrarySql(tab, hideFinished, downloadedOnly, searchQuery.isNotBlank())
```
(Leave the `args` assembly and `audioBookDao.getFilteredBooks(...)` call unchanged.)

- [ ] **Step 4: Add the Archive tab to LibraryViewModel**

In `LibraryViewModel.kt:34-39`, add `Archive` to the enum:
```kotlin
enum class LibraryTab(val label: String) {
    All("All"),
    InProgress("In Progress"),
    Completed("Completed"),
    Downloaded("Downloaded"),
    Archive("Archive"),
}
```
In the tab→int mapping (`applyFilterSuspend`, around line 335-340), add:
```kotlin
            LibraryTab.Archive -> 4
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ninelivesaudio.app.data.repository.LibrarySqlArchiveTest" --offline`
Expected: PASS.

- [ ] **Step 6: Show the Archive tab in LOCAL mode + dim archived items**

In `LibraryScreen.kt`, the tab row (the `StoneTabsRow`/tabs near line 146-150) renders `LibraryTab.entries`. Make the Archive entry conditional on LOCAL mode: filter the rendered tabs to exclude `LibraryTab.Archive` unless `uiState.appMode == AppMode.LOCAL` (the screen already has access to app mode via the view model state; if not, add `val isLocalMode: Boolean` to `LibraryViewModel.UiState` set from `settingsManager.currentSettings.appMode == AppMode.LOCAL`). Render archived items in the list with reduced alpha and an "Archived" chip: in the book list/card item, when `book.isArchived`, wrap the cover/text in `Modifier.alpha(0.6f)` and add a small `Surface` chip labeled "Archived" next to the status tags (mirror the existing `StatusTag` in `ui/components/BookCard.kt:151-171`).

- [ ] **Step 7: Build**

Run: `./gradlew :app:assembleDebug --offline`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "Add LOCAL-mode Archive tab and hide archived books from live shelf" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Mz5R7Tue2qsDw34WrkcyUS"
```

---

## Task 6: BookDetail archived handling

**Files:**
- Test: `app/src/test/java/com/ninelivesaudio/app/ui/bookdetail/BookPlayabilityTest.kt`
- Modify: `app/src/main/java/com/ninelivesaudio/app/ui/bookdetail/BookDetailViewModel.kt` (add `isArchived` to UiState; set in `populateFromBook` ~line 113-143; add a pure `canPlayBook`)
- Modify: `app/src/main/java/com/ninelivesaudio/app/ui/bookdetail/BookDetailScreen.kt` (disable Play + message; Delete-forever button)

**Interfaces:**
- Produces: `internal fun canPlayBook(isLocal: Boolean, isDownloaded: Boolean, isArchived: Boolean): Boolean`; `BookDetailViewModel.UiState.isArchived: Boolean`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/ninelivesaudio/app/ui/bookdetail/BookPlayabilityTest.kt`:
```kotlin
package com.ninelivesaudio.app.ui.bookdetail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An archived book's source file is gone, so it is never playable. */
class BookPlayabilityTest {

    @Test
    fun `local book is playable`() {
        assertTrue(canPlayBook(isLocal = true, isDownloaded = false, isArchived = false))
    }

    @Test
    fun `downloaded remote book is playable`() {
        assertTrue(canPlayBook(isLocal = false, isDownloaded = true, isArchived = false))
    }

    @Test
    fun `archived book is never playable`() {
        assertFalse(canPlayBook(isLocal = true, isDownloaded = true, isArchived = true))
    }

    @Test
    fun `remote not-downloaded book is not playable`() {
        assertFalse(canPlayBook(isLocal = false, isDownloaded = false, isArchived = false))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ninelivesaudio.app.ui.bookdetail.BookPlayabilityTest" --offline`
Expected: FAIL — `canPlayBook` unresolved.

- [ ] **Step 3: Implement `canPlayBook` + wire UiState**

Add a top-level function at the end of `BookDetailViewModel.kt`:
```kotlin
/** Archived books have no source file, so they are never playable. */
internal fun canPlayBook(isLocal: Boolean, isDownloaded: Boolean, isArchived: Boolean): Boolean =
    !isArchived && (isLocal || isDownloaded)
```
Add `val isArchived: Boolean = false` to `BookDetailViewModel.UiState`. In `populateFromBook` (the `_uiState.update { it.copy(...) }` around line 113-143), set `isArchived = book.isArchived,`.

- [ ] **Step 4: Update the screen**

In `BookDetailScreen.kt`, where the Play/Continue button is rendered: when `uiState.isArchived`, render the Play button disabled (`enabled = false`) and a caption "Source file no longer available"; and render a "Delete forever" button that calls a new `viewModel.deleteForever()` (added in Task 7). Use `canPlayBook(uiState.isLocal, uiState.isDownloaded, uiState.isArchived)` for the enabled state of Play.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ninelivesaudio.app.ui.bookdetail.BookPlayabilityTest" --offline`
Expected: PASS. (The `deleteForever()` call compiles only after Task 7; if doing tasks in order, stub `fun deleteForever() {}` here and fill it in Task 7, or reorder so Task 7's repo method exists first. Recommended: keep the stub here, implement in Task 7.)

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Disable Play and surface archived state in BookDetail" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Mz5R7Tue2qsDw34WrkcyUS"
```

---

## Task 7: Delete-forever cascade

No new unit test for the cascade itself (Room/file IO; verified in Task 9). One pure helper is tested.

**Files:**
- Modify: `app/src/main/java/com/ninelivesaudio/app/data/local/dao/LocalListeningSessionDao.kt` (add `deleteByAudioBookId`)
- Modify: `app/src/main/java/com/ninelivesaudio/app/data/repository/AudioBookRepository.kt` (add `deleteLocalBookForever`); needs `@ApplicationContext context` injected (currently only `audioBookDao`, `apiService`) plus the two history DAOs.
- Modify: `app/src/main/java/com/ninelivesaudio/app/ui/bookdetail/BookDetailViewModel.kt` (`deleteForever()`), and `LibraryViewModel`/Archive item menu to call it.

**Interfaces:**
- Consumes: `LocalBookmarkDao.deleteAllForBook(id)` (exists), `AudioBookDao.deleteById(id)` (exists).
- Produces: `LocalListeningSessionDao.deleteByAudioBookId(id)`, `AudioBookRepository.deleteLocalBookForever(id)`.

- [ ] **Step 1: Add the session delete query**

In `LocalListeningSessionDao.kt`, add:
```kotlin
    @Query("DELETE FROM LocalListeningSessions WHERE AudioBookId = :audioBookId")
    suspend fun deleteByAudioBookId(audioBookId: String)
```

- [ ] **Step 2: Add the cascade to the repository**

`AudioBookRepository` needs `@ApplicationContext private val context: Context`, `private val localListeningSessionDao: LocalListeningSessionDao`, and `private val localBookmarkDao: LocalBookmarkDao` added to its constructor (Hilt provides them; the DAOs are already `@Provides` in `di/AppModule.kt`). Add:
```kotlin
    /**
     * Permanently delete a LOCAL book and everything tied to it: the row, its
     * listening sessions, its bookmarks, and the cached cover file.
     */
    suspend fun deleteLocalBookForever(bookId: String) {
        audioBookDao.deleteById(bookId)
        localListeningSessionDao.deleteByAudioBookId(bookId)
        localBookmarkDao.deleteAllForBook(bookId)
        runCatching {
            java.io.File(java.io.File(context.filesDir, "local_covers"), "$bookId.jpg").delete()
        }
    }
```
(Add the imports: `android.content.Context`, `dagger.hilt.android.qualifiers.ApplicationContext`, the two DAO types.)

- [ ] **Step 3: Wire the UI calls**

In `BookDetailViewModel.kt`, implement the `deleteForever()` stub from Task 6:
```kotlin
    fun deleteForever() {
        val id = _uiState.value.book?.id ?: return
        viewModelScope.launch {
            audioBookRepository.deleteLocalBookForever(id)
            // navigate back / emit a closed event using the existing pattern in this VM
        }
    }
```
In the Archive list item menu (Task 5's archived rows), add a "Delete forever" action that calls `LibraryViewModel.deleteForever(bookId)`, which calls `audioBookRepository.deleteLocalBookForever(bookId)` and refreshes the list.

- [ ] **Step 4: Build**

Run: `./gradlew :app:testDebugUnitTest --offline && ./gradlew :app:assembleDebug --offline`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add Delete-forever cascade (book + sessions + bookmarks + cover)" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Mz5R7Tue2qsDw34WrkcyUS"
```

---

## Task 8: Dossier stats toggle (include archived)

**Files:**
- Test: `app/src/test/java/com/ninelivesaudio/app/ui/dossier/StatsArchiveFilterTest.kt`
- Modify: `app/src/main/java/com/ninelivesaudio/app/domain/model/AppSettings.kt` (add `includeArchivedInStats`)
- Modify: `app/src/main/java/com/ninelivesaudio/app/ui/settings/SettingsViewModel.kt` (toggle) + `SettingsScreen.kt` (row)
- Modify: `app/src/main/java/com/ninelivesaudio/app/ui/dossier/NightwatchDossierViewModel.kt:285-312` (apply the filter)

**Interfaces:**
- Produces: `AppSettings.includeArchivedInStats: Boolean` (default `true`); `internal fun statsBookIds(allBookIds: Set<String>, archivedBookIds: Set<String>, includeArchived: Boolean): Set<String>`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/ninelivesaudio/app/ui/dossier/StatsArchiveFilterTest.kt`:
```kotlin
package com.ninelivesaudio.app.ui.dossier

import org.junit.Assert.assertEquals
import org.junit.Test

/** When the toggle is off, archived books drop out of the stats book set. */
class StatsArchiveFilterTest {

    private val all = setOf("a", "b", "c")
    private val archived = setOf("b")

    @Test
    fun `includes archived when toggle on`() {
        assertEquals(all, statsBookIds(all, archived, includeArchived = true))
    }

    @Test
    fun `excludes archived when toggle off`() {
        assertEquals(setOf("a", "c"), statsBookIds(all, archived, includeArchived = false))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ninelivesaudio.app.ui.dossier.StatsArchiveFilterTest" --offline`
Expected: FAIL — `statsBookIds` unresolved.

- [ ] **Step 3: Implement the filter + settings field**

Add a top-level function at the end of `NightwatchDossierViewModel.kt`:
```kotlin
/** The set of book ids whose sessions count toward stats, honoring the toggle. */
internal fun statsBookIds(
    allBookIds: Set<String>,
    archivedBookIds: Set<String>,
    includeArchived: Boolean,
): Set<String> = if (includeArchived) allBookIds else allBookIds - archivedBookIds
```
Add `val includeArchivedInStats: Boolean = true` to `AppSettings.kt`.

- [ ] **Step 4: Apply the filter in the Dossier aggregation**

In `NightwatchDossierViewModel.kt` where it builds `bookMap` and groups `sessionsByBook` (around line 257-312): compute `archivedBookIds = allBooks.filter { it.isArchived }.map { it.id }.toSet()`, read `includeArchivedInStats` from `settingsManager.currentSettings`, compute the allowed set with `statsBookIds(...)`, and skip sessions whose `bookId` is not in the allowed set when aggregating totals and the book list.

- [ ] **Step 5: Add the Settings toggle**

In `SettingsViewModel.kt`, mirror the existing AppSettings boolean toggle pattern (e.g. `toggleEqEnabled` at ~line 893): add `includeArchivedInStats` to the Settings UiState (collected from `settingsManager.settings`) and a `fun toggleIncludeArchivedInStats()` that calls `settingsManager.updateSettings { it.copy(includeArchivedInStats = !it.includeArchivedInStats) }`. In `SettingsScreen.kt`, add a labeled switch row "Include archived in stats" bound to it (follow an existing switch row in that screen).

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ninelivesaudio.app.ui.dossier.StatsArchiveFilterTest" --offline`
Expected: PASS. Then `./gradlew :app:assembleDebug --offline` → BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "Add 'include archived in stats' toggle to Dossier + Settings" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Mz5R7Tue2qsDw34WrkcyUS"
```

---

## Task 9: Full verification (build + on-device LOCAL flow)

No new code unless a defect is found.

- [ ] **Step 1: Full unit suite + assemble**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --offline`
Expected: BUILD SUCCESSFUL; the 6 new test classes pass; `app/schemas/.../6.json` present with `ArchivedAt`.

- [ ] **Step 2: Install on device**

Run: `~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: Success. App launches without a Room migration crash (proves v5→v6 applied on an existing install).

- [ ] **Step 3: Walk the acceptance criteria on a LOCAL library**

1. Scan a LOCAL folder with at least one book that has a folder `cover.jpg`. Note its title.
2. Remove/stop scanning that folder and rescan. The book leaves the live shelf and appears in the **Archive** tab, dimmed, with an "Archived" chip and its cover still showing (pull DB+WAL to confirm `ArchivedAt` set and `coverPath` is a `file://`; confirm `filesDir/local_covers/<id>.jpg` exists).
3. Open the archived book's detail: history + cover visible, Play disabled with "Source file no longer available".
4. Confirm Dossier total time is unchanged before vs after archiving (toggle on).
5. Flip Settings "Include archived in stats" off; confirm Dossier totals drop by the archived book's time; toggle back on.
6. Re-add the folder and rescan: the book returns to the live shelf (restored, same id).
7. From the Archive item menu (or BookDetail), "Delete forever": confirm the book, its sessions, its bookmarks, and `local_covers/<id>.jpg` are gone (DB+WAL + file check).

- [ ] **Step 4: Commit any fixes; open the PR**

```bash
git push -u origin feat/local-archive-shelf
gh pr create --base master --title "LOCAL Archive shelf: keep cover + history when a book is unscanned (#40)" --body "Closes #40. <summary + the verification results>"
```

---

## Self-Review

- **Spec coverage:** (1) durable covers → Task 4; (2) soft-delete → Tasks 1-3; (3) Archive view → Task 5; (4) BookDetail archived → Task 6; (5) Dossier stats + toggle → Task 8; (6) Delete-forever cascade → Task 7; restore-on-rescan → Task 3 Step 3; migration v5→v6 → Task 1. All five issue parts + acceptance criteria mapped.
- **Type consistency:** `idsToArchive`, `archiveByIds`, `getLocalIdsByLibrary`, `buildLibrarySql` (tab Int; Archive = 4), `writeLocalCoverFile(bytes, coverDir, bookId)`, `persistFolderCover(coverUri, bookId)`, `canPlayBook(isLocal, isDownloaded, isArchived)`, `statsBookIds(all, archived, includeArchived)`, `deleteLocalBookForever(id)`, `deleteByAudioBookId(id)` — names/signatures consistent across tasks.
- **Cross-task dependency note:** Task 6 references `viewModel.deleteForever()` implemented in Task 7. If executed strictly in order, keep the empty `deleteForever()` stub from Task 6 and fill it in Task 7 (called out in Task 6 Step 5). Tasks 1→9 otherwise build linearly.
