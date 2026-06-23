# LOCAL mode: Archive shelf (keep cover + history when a book is unscanned)

- **Issue:** #40
- **Target:** v2.0 release (bumped from v2.1)
- **Scope:** LOCAL mode only. AUDIOBOOKSHELF mode is server-of-record and already preserves session metadata server-side.
- **Status:** Design approved 2026-06-22.

## Problem

In LOCAL mode, when a user removes (or stops scanning) a folder, the affected books are hard-deleted from `AudioBooks`. The Dossier and library views then lose the cover art, the book row itself (so sessions/bookmarks can't resolve title/author/cover), and the felt-permanence of the listening record.

`LocalListeningSessions` and `LocalBookmarks` already persist independently — they key on a plain `audioBookId` string, not a foreign key — so the underlying history survives the delete. We just can't display it usefully once the parent book row is gone.

## Goal

When a scanned book disappears from its source folder, the user keeps a usable record of it:

- Dossier still shows the sessions with title, author, and cover.
- A new "Archive" view in Library surfaces the archived books.
- Stats include archived books by default, with a Settings toggle to exclude them.
- The user can "Delete forever" from the archive to wipe the book, its sessions, its bookmarks, and the cached cover.

## Key facts established during exploration

- **Single hard-delete site.** `AudioBookRepository.removeMissingLocalBooks(libraryId, scannedIds)` (`data/repository/AudioBookRepository.kt:238`) calls `AudioBookDao.deleteMissingLocalBooks` (non-empty scan) or `deleteLocalByLibrary` (empty scan). Invoked from `SettingsViewModel.removeMissingBooksAfterSuccessfulScan()` only after a clean scan. This is the one place to switch to soft-delete.
- **Local book ids are stable per path.** The scanner uses `id = "local_book_<sha256(rootUri/folderName)>"` (or `/filename` for single-file books) — `service/local/LocalLibraryScanner.kt:203,224`. Re-scanning the same folder reuses the same row, so restore-on-rescan and dedup work for free. A file reappearing at a different path gets a new id and stays archived (matches the issue's out-of-scope note).
- **Cover sourcing is mixed.** `findCoverImage` (a folder image such as `cover.jpg`) takes precedence over embedded extraction (`LocalLibraryScanner.kt:204`). Folder covers are `content://` SAF URIs that die on unscan; embedded covers are already copied to `filesDir/local_covers/<id>.jpg` (`file://`, durable) by `LocalMetadataExtractor.extractEmbeddedCover` (`service/local/LocalMetadataExtractor.kt:72`). Only the folder-cover case needs new persistence. The persisted path is stored in `AudioBookEntity.coverPath`; `effectiveCoverPath = localCoverPath ?: coverPath` already falls back to it.
- **Sessions/bookmarks have no FK cascade.** `LocalListeningSessionEntity.AudioBookId` and `LocalBookmarkEntity.AudioBookId` are plain indexed strings. Sessions store a `DisplayTitle` fallback. Soft-delete keeps the book row so the Dossier resolves everything normally again; delete-forever needs explicit cascade calls.
- **Schema is at v5** (`LocalCoverPath` landed in PR #49's MIGRATION_4_5). This feature adds v5 → v6.

## Design

### 1. Data model (migration v5 → v6)

- `AudioBookEntity`: add `@ColumnInfo(name = "ArchivedAt") val archivedAt: Long? = null` (epoch millis; null = live).
- `AudioBook` domain model: add `val archivedAt: Long? = null` and `val isArchived: Boolean get() = archivedAt != null`.
- `EntityMappers`: map `archivedAt` both directions.
- `MIGRATION_5_6 = ALTER TABLE AudioBooks ADD COLUMN ArchivedAt INTEGER` (nullable, no default). Append to `ALL_MIGRATIONS`; bump `AppDatabase` version to 6. Schema v6 JSON exported.
- No `coverPath` rename (the issue assumed one; not needed).

### 2. Durable covers (persist on scan)

During local import, ensure every cover is a durable local file:

- Embedded covers already persist (no change).
- For a folder cover (`content://`), copy its bytes to `filesDir/local_covers/<id>.jpg` and store the `file://` URI in `coverPath`. Add a helper alongside `extractEmbeddedCover` (e.g. `LocalMetadataExtractor.persistFolderCover(coverUri, bookId): String?`) that reads the content URI via `ContentResolver` and writes the bytes (mirrors the embedded path). Call it during import for any `content://` cover.
- The cover bytes-to-file write is a small pure-ish helper that can be unit-tested with a temp dir (mirrors PR #49's `writeCoverFile`).

**Edge case (documented, not fixed):** a book scanned under an older build still has a `content://` cover until its next scan. If such a book is archived before any re-scan under the new build, its cover is already dead and cannot be recovered. Covers are repaired on the next successful scan of a still-present book. No one-time backfill in this release.

### 3. Soft-delete on unscan (+ restore)

- `removeMissingLocalBooks` no longer deletes. New DAO method `archiveMissingLocalBooks(libraryId, scannedIds, archivedAt)` sets `ArchivedAt` for local books in the library whose id is not in `scannedIds`. The empty-scan branch archives all local books in the library (`archiveLocalByLibrary(libraryId, archivedAt)`).
- The decision of which ids to archive is extracted as a pure function (`idsToArchive(existingLocalIds, scannedIds)`) for unit testing; the DAO call applies it.
- **Restore:** import upserts a found book and sets `archivedAt = null`, so a returning folder (same id) is automatically un-archived. Ensure the import path clears `ArchivedAt` for re-imported books (either in the upsert mapping or an explicit `unarchive(ids)` call for the scanned set).

### 4. Library Archive view

- Add `LibraryTab.Archive("Archive")`, shown only in LOCAL mode (the tab row conditionally includes it when `appMode == LOCAL`).
- The live tabs (All / In Progress / Completed / Downloaded) gain `ArchivedAt IS NULL` in the `getFilteredBooks` SQL so archived books leave the live shelf.
- The Archive tab filters to `ArchivedAt IS NOT NULL`.
- Archived items render dimmer with an "Archived" chip (a small style variant in the existing list/card item).

### 5. BookDetail for archived items

- `BookDetailViewModel` exposes `isArchived` from the book.
- When archived: Play is disabled with the message "Source file no longer available"; a "Delete forever" action is shown. History and cover still render (the row and the cached cover file are intact).

### 6. Dossier + Settings stats toggle

- `AppSettings`: add `includeArchivedInStats: Boolean = true`.
- `SettingsViewModel` + Settings UI: a toggle row mirroring an existing AppSettings boolean (the encrypted-prefs JSON pattern, e.g. `toggleEqEnabled`).
- `NightwatchDossierViewModel`: the book row survives, so archived books already resolve title/author/cover. When `includeArchivedInStats` is false, exclude archived books' sessions from the aggregated totals (it already builds a `bookMap`; filter sessions whose book `isArchived`). The include/exclude filter is a pure function with a unit test.

### 7. Delete forever (cascade)

- `AudioBookRepository.deleteLocalBookForever(id)`:
  1. `AudioBookDao.deleteById(id)` (exists).
  2. `LocalListeningSessionDao.deleteByAudioBookId(id)` — **new** DAO method.
  3. `LocalBookmarkDao.deleteAllForBook(id)` (exists).
  4. Delete the cached cover file `filesDir/local_covers/<id>.jpg` if present.
- Reachable from the Archive item menu and from BookDetail's "Delete forever" action.

## Testing

House style is pure decision functions + plain JUnit (no mock web server / Robolectric). Unit-test:

- `idsToArchive(existingLocalIds, scannedIds)` — the archive diff.
- the cover bytes-to-file write helper — temp dir, like `writeCoverFile`.
- the Dossier stats include/exclude-archived filter.
- `AudioBook.isArchived` resolution.
- `MIGRATION_5_6` adds the column (follow existing migration convention; verified by a clean run on an old DB + schema v6 export).

Migration, DAO queries, scanner cover-copy, and UI are build + on-device verified (LOCAL library on the test device): scan a folder, remove it, confirm the book leaves the live shelf and appears dimmed in Archive with its cover, open its detail (Play disabled, history shown), flip the stats toggle, and Delete forever (book + sessions + bookmarks + cover file gone).

## Out of scope

- Showing archived items mixed into the main library grid.
- Auto-restoring an archived book when its file reappears at a different path (re-scan of the original path handles intentional restore).
- One-time backfill of `content://` covers for books scanned under older builds.

## Acceptance criteria

- [ ] Remove a scanned folder; the book disappears from the main Library but appears in the Archive tab.
- [ ] Open the archived book's detail: history visible, cover visible, Play disabled.
- [ ] Dossier total-time stats are unchanged before vs after archiving (toggle on).
- [ ] "Delete forever" from Archive wipes the book, its sessions, its bookmarks, and the cached cover file.
- [ ] Settings toggle "Include archived in stats" flips the Dossier totals.

## Touched files

`AudioBookEntity`, `AudioBookDao`, `Migrations` / `AppDatabase`, `EntityMappers`, `AudioBook` (domain), `LocalLibraryScanner` / `LocalMetadataExtractor` / `LocalImportMapper`, `AudioBookRepository`, `LocalListeningSessionDao`, `LibraryViewModel` / `LibraryScreen`, `BookDetailViewModel` / `BookDetailScreen`, `NightwatchDossierViewModel`, `AppSettings` / `SettingsViewModel` / Settings UI.
