# NineLivesAudio v2: Local Playback Mode

## Context

NineLivesAudio currently centers on Audiobookshelf (ABS): libraries, metadata, playback sessions, bookmarks, progress sync, and listening history all assume a server-backed book. The v2 local playback work should add a Local Library mode without weakening the existing ABS path.

The first usable milestone is:

- Switch between ABS and Local modes in Settings.
- Add one or more local audiobook folders through Android's Storage Access Framework (SAF).
- Scan those folders into Room.
- Show local books in the existing Library/Home/Book Detail/Player surfaces.
- Play local SAF `content://` audio files with local progress persistence.

Bookmarks, rich listening history, welcome onboarding, and background rescans can follow after the local import/playback loop is solid.

## Important Current-Code Findings

The original plan was directionally right, but these details matter in the current codebase:

- `PlaybackManager.loadLocalTracks()` currently assumes filesystem `File` paths and builds `Uri.fromFile(...)`. SAF imports will provide `content://` URIs, so playback must be updated before local mode can work.
- `PlaybackManager.loadAudioBook()` always attempts `apiService.startPlaybackSession(book.id)`. Local books must skip server sessions entirely.
- `SyncManager.reportPlaybackPosition()` and `flushPlaybackProgress()` always try to push or enqueue progress for server sync. Local books must save progress locally without entering the pending server queue.
- `isDownloaded` already means "ABS book downloaded to local app storage." It cannot also mean "local library book." Add an explicit local/source marker.
- `SettingsViewModel` cannot launch the SAF picker directly. `SettingsScreen` must own the `ActivityResultLauncher`, then pass the returned URI into the ViewModel.
- Standard `MediaMetadataRetriever` is good for title/artist/album/duration/artwork, but embedded M4B chapter extraction is not a safe Phase 1 assumption. Treat M4B chapter extraction as best-effort or a follow-up unless verified on device.

## Core Architecture Decisions

### Mode and Source State

Add explicit mode and source fields instead of overloading existing download state.

New model:

- `AppMode`: `AUDIOBOOKSHELF`, `LOCAL`
- `AudioBook.isLocal: Boolean`
- `Library.isLocal: Boolean`
- `Library.folderUri: String?`

Settings changes:

- Add `appMode: AppMode = AppMode.AUDIOBOOKSHELF`.
- Keep existing `selectedLibraryId` as the ABS library selection to minimize churn.
- Add `selectedLocalLibraryId: String?`.
- Add a helper on `SettingsManager` or a small `ModeLibraryResolver` that returns the active library ID for the current mode.

Why: ABS downloaded books are still ABS books. They may have `isDownloaded = true` and `localPath != null`, but they still need ABS metadata/session/progress behavior. Local books need a separate branch.

### Local Library Storage

Use `Libraries` rows for local folders:

- One picked SAF folder becomes one local `LibraryEntity`.
- `LibraryEntity.folderUri` stores the root tree URI.
- `LibraryEntity.isLocal` marks it as local.
- `LibraryEntity.name` comes from the folder display name, with a user-editable name later if needed.

Avoid duplicating folder URIs in both settings and Room. Persist mode/selected-local-library in settings; persist imported folder roots in Room.

### Stable Local IDs

Local book IDs should be deterministic and survive rescans.

Use:

- `libraryId = "local_library_" + sha256(rootTreeUri)`
- `bookId = "local_book_" + sha256(rootTreeUri + "/" + stableRelativePathOrDocumentId)`
- `audioFile.id = "local_file_" + sha256(fileUri)`

Do not base IDs only on title/author, because metadata edits would break progress/bookmark history.

### SAF Permissions

`SettingsScreen` launches `ACTION_OPEN_DOCUMENT_TREE` through `rememberLauncherForActivityResult`.

On result:

- Call `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`.
- Pass the URI string to `SettingsViewModel.addLocalFolder(uriString)`.
- On app start and before scan/playback, verify the URI is still in `contentResolver.persistedUriPermissions`.
- If permission is revoked, show the folder as unavailable and prompt the user to re-grant.

No runtime storage permissions are needed for SAF.

### Playback Rules

ABS book:

- May stream or play app-downloaded files.
- May start an ABS playback session.
- May sync progress/bookmarks/listening sessions to the server.

Local book:

- Uses SAF `content://` URIs or app-private cover files.
- Must skip ABS playback session creation and stale-session recovery.
- Saves progress locally only.
- Does not enqueue pending server progress.
- Uses the same ExoPlayer, MediaSession, EQ, speed, sleep timer, chapter UI, and notification path.

## Phase 0: Foundation and Schema

Goal: make the app capable of separating ABS and Local data before adding UI.

Modify:

- `domain/model/AppMode.kt`: new enum.
- `domain/model/AppSettings.kt`: add `appMode` and `selectedLocalLibraryId`.
- `domain/model/AudioBook.kt`: add `isLocal: Boolean = false`.
- `domain/model/Library.kt`: add `isLocal: Boolean = false`, `folderUri: String? = null`.
- `data/local/entity/AudioBookEntity.kt`: add `IsLocal INTEGER NOT NULL DEFAULT 0`.
- `data/local/entity/LibraryEntity.kt`: add `IsLocal INTEGER NOT NULL DEFAULT 0`, `FolderUri TEXT`.
- `data/local/converter/*.kt`: map the new fields.
- `data/local/AppDatabase.kt`: bump Room version.
- `data/local/migration/Migrations.kt`: add `MIGRATION_1_2`.

Repository work:

- `LibraryDao`: add local-specific queries, e.g. `observeLocal()`, `getLocal()`, `deleteLocalLibrary(id)`.
- `AudioBookDao`: add `deleteByLibrary(libraryId)` and queries that can filter by `IsLocal` where needed.
- `LibraryRepository`: add `createLocalLibrary(name, folderUri)`, `observeLocalLibraries()`, `removeLocalLibrary(id)`.
- `AudioBookRepository`: add `importLocalBooks(libraryId, books)` and `removeMissingLocalBooks(libraryId, scannedIds)`.

Acceptance:

- Existing ABS data migrates without loss.
- Existing library selection continues to work.
- Fresh install creates DB v2 cleanly.
- ABS sync never overwrites local libraries/books.

## Phase 1: Local Import and Playback MVP

Goal: user can switch to Local mode, add a folder, see books, and play them.

### 1.1 Settings Mode UI

Modify `ui/settings/SettingsScreen.kt`:

- Add a segmented control at the top: `Audiobookshelf` / `Local Library`.
- In ABS mode, show current server connection, library selector, sync, and cache controls.
- In Local mode, hide server connection controls and show Local Folders.
- Local Folders section includes add, rescan, remove, and unavailable-permission state.
- Own the SAF activity result launcher in the composable and pass selected URI strings to the ViewModel.

Modify `ui/settings/SettingsViewModel.kt`:

- Add `appMode`, `localLibraries`, `selectedLocalLibrary`, and scan state to `UiState`.
- Add `switchMode(AppMode)`.
- Add `onLocalFolderPicked(uriString)`.
- Add `rescanLocalLibrary(libraryId)` and `removeLocalLibrary(libraryId)`.
- Stop playback on mode switch to avoid cross-mode player state.

### 1.2 Local Scanner

New files:

- `service/local/LocalLibraryScanner.kt`
- `service/local/LocalMetadataExtractor.kt`
- `service/local/ScannedLocalBook.kt`

Scanner responsibilities:

- Use `DocumentFile.fromTreeUri(...)` for tree traversal.
- Treat each immediate child directory with audio files as one book.
- Treat each immediate audio file under the selected root as one book.
- Sort multi-file tracks naturally by filename and, when available, by track number metadata.
- Ignore hidden/system files and non-audio files except cover images.
- Return skipped/problem entries so the UI can report partial scan issues.

Supported audio extensions:

- `mp3`, `m4a`, `m4b`, `opus`, `ogg`, `flac`, `aac`, `wma`, `wav`

Cover priority:

1. `cover.jpg`, `cover.jpeg`, `cover.png`, `folder.jpg`, `folder.png`
2. Embedded image from the first playable audio file
3. `null`, allowing the existing placeholder to render

Metadata priority:

1. Embedded title/album/artist/album artist when available
2. Folder or filename parsing
3. `Unknown Author`

Phase 1 chapter policy:

- Multi-file books get synthetic chapters, one chapter per file.
- Single-file books get one synthetic chapter.
- Embedded M4B chapters are a follow-up unless a tested Media3 path is added during implementation.

### 1.3 Import Mapping

Map `ScannedLocalBook` to `AudioBook`:

- `id`: deterministic `local_book_...`
- `libraryId`: local library ID
- `isLocal`: `true`
- `isDownloaded`: `true` only so existing downloaded/offline UI affordances still make sense
- `localPath`: root book URI or primary file URI string
- `audioFiles.localPath`: each track's `content://` URI string
- `audioFiles.duration`: extracted duration where available
- `chapters`: synthetic chapters from track durations
- `coverPath`: app-private copied cover file path, or `null`

Important: keep original SAF audio URIs in `audioFiles.localPath`. Do not copy audio into app storage for Phase 1.

### 1.4 Playback Updates

Modify `service/PlaybackManager.kt`:

- Detect `book.isLocal`.
- Skip `apiService.startPlaybackSession(...)` for local books.
- Skip `apiService.getAudioBook(...)` fallback for local books.
- Update `loadLocalTracks()` to support both:
  - app-private file paths from ABS downloads
  - SAF `content://` URIs from local imports
- Build `MediaItem` from `Uri.parse(localPath)` when the path has a scheme.
- Keep `Uri.fromFile(File(path))` only for scheme-less filesystem paths.
- Do not scan filesystem directories for SAF books at playback time. The import scan should have already populated `audioFiles`.

Modify session/progress behavior:

- `startSessionSync()` should not start for local books, or `syncProgressNow()` should save local progress and return before server work.
- `recoverIfSessionStale()` should ignore local books.
- `closeSession()` is a no-op for local books because no server session exists.

Modify `service/SyncManager.kt`:

- Add an `isLocal` parameter or look up the book before network pushes.
- Local books save `PlaybackProgress` and `AudioBook` progress locally only.
- Local books never call `ProgressRepository.pushProgressToServer(...)`.
- Local books never enqueue `PendingProgressEntity`.

### 1.5 Library/Home Mode Awareness

Modify `ui/library/LibraryViewModel.kt`:

- Observe `settingsManager.settings.map { it.appMode }`.
- In ABS mode, keep existing server sync and selected ABS library behavior.
- In Local mode, load only local libraries/books.
- Do not call `syncFromServer()` or `syncLibraryItems()` in Local mode.
- Hide or relabel server/offline messaging in Local mode.

Modify `ui/home/HomeViewModel.kt`:

- Observe effective library ID based on mode.
- Query recently played books from the selected local library in Local mode.
- Avoid showing ABS recent books while Local mode is active.

Modify `ui/bookdetail/BookDetailViewModel.kt`:

- Hide download/delete-download actions for local books.
- Avoid server fetch fallback for local book IDs.
- Listening history can be empty until Phase 3.

Modify `service/MediaBrowseTree.kt`:

- Respect current mode or expose separate ABS/Local roots.
- Avoid showing local books under ABS libraries in Android Auto.

Acceptance:

- Add a SAF folder and scan it.
- Local books appear in Library.
- A multi-file MP3 book plays in order.
- A single M4B file plays.
- Progress survives app restart.
- Switching to ABS mode shows ABS data again.
- Switching back to Local mode shows the local books and progress.

## Phase 2: Local Bookmarks

Goal: bookmarks work offline and for local books.

New files:

- `data/local/entity/BookmarkEntity.kt`
- `data/local/dao/BookmarkDao.kt`

Entity fields:

- `Id`
- `AudioBookId`
- `Title`
- `TimeSeconds`
- `CreatedAt`
- `IsLocal`
- `NeedsServerSync`

Modify:

- `data/local/AppDatabase.kt`: add entity and DAO, bump/migrate if Phase 1 already shipped.
- `di/AppModule.kt`: provide `BookmarkDao`.
- `data/repository/BookmarkRepository.kt`: use Room as the primary store.
- `ui/player/PlayerViewModel.kt`: load/create/delete bookmarks through repository unchanged if repository hides the source split.

Behavior:

- Local books read/write Room only.
- ABS books read local cache first, then refresh from server when connected.
- ABS bookmark writes update Room immediately and sync to server when possible.

Acceptance:

- Add/delete bookmark on local book.
- Close/reopen app, bookmark remains.
- Add/delete bookmark on ABS book still syncs to server.

## Phase 3: Local Listening Sessions and Dossier

Goal: Nightwatch Dossier and Book Detail listening history work in Local mode.

New files:

- `data/local/entity/ListeningSessionEntity.kt`
- `data/local/dao/ListeningSessionDao.kt`
- `data/repository/ListeningSessionRepository.kt`

Entity fields:

- `Id`
- `AudioBookId`
- `LibraryId`
- `StartedAt`
- `UpdatedAt`
- `CurrentTimeSeconds`
- `TimeListeningSeconds`
- `DisplayTitle`
- `IsLocal`

Modify:

- `service/PlaybackManager.kt`: record local listening session start, periodic updates, pause/stop closeout.
- `ui/dossier/NightwatchDossierViewModel.kt`: in Local mode query local sessions; in ABS mode keep server sessions.
- `ui/bookdetail/BookDetailViewModel.kt`: use repository so local books can show local sessions.
- `di/AppModule.kt`: provide DAO/repository.

Acceptance:

- Play a local book for a few minutes.
- Open Dossier in Local mode and see listening time.
- Open Book Detail history for that local book and see session rows.
- ABS mode Dossier still shows server-backed sessions.

## Phase 4: Polish and Resilience

Work items:

- First-run mode choice: show two paths only when no ABS config and no local folders exist.
- Background rescan: WorkManager job on app launch in Local mode.
- Missing files: mark unavailable books instead of deleting immediately.
- Removed books: after rescan, soft-delete or hide books missing for more than one scan.
- Rename local library folders in Settings.
- Optional import diagnostics: scanned count, skipped count, last scan time.
- Downloads screen: hide in Local mode or repurpose as an import/storage screen.
- Mode indicator: subtle persistent indicator in Settings and Library header.
- Optional M4B embedded chapters after device testing.

## Verification Matrix

Phase 1:

1. Fresh install, connect to ABS, confirm existing flow works.
2. Switch to Local mode; ABS server controls hide.
3. Add a SAF folder; permission persists after app restart.
4. Scan folder with:
   - multi-file MP3 book
   - single M4B book
   - FLAC or OPUS book
   - book without metadata
   - book with folder cover
   - book with embedded cover
5. Play every imported book type.
6. Seek across track boundaries in a multi-file book.
7. Kill and reopen app; local mode and progress persist.
8. Switch ABS -> Local -> ABS repeatedly with playback stopped automatically.
9. Confirm local progress never creates pending server progress rows.

Phase 2:

1. Create/delete local bookmark and verify it survives restart.
2. Create/delete ABS bookmark and verify server sync still works.
3. Test ABS bookmark creation while offline, then reconnect.

Phase 3:

1. Play a local book long enough to create session data.
2. Confirm local Dossier totals.
3. Confirm ABS Dossier remains unchanged.

Regression:

1. Room migration from v1 to v2.
2. Fresh DB install.
3. ABS streaming playback.
4. ABS downloaded playback.
5. Android Auto browse and playback.
6. Media notification controls.
7. Sleep timer, EQ, speed, auto-rewind.

## Estimated Work to Complete

MVP local import/playback (Phases 0 and 1):

- Schema/settings/source split: 0.5-1 day
- Settings Local mode UI and SAF picker: 0.5-1 day
- Scanner and metadata extraction: 1-2 days
- Import mapping and repository work: 0.5-1 day
- Playback and SyncManager local guards: 1 day
- Library/Home/Book Detail mode awareness: 1 day
- Manual device testing and fixes: 1-2 days

Expected MVP total: about 5-8 focused engineering days.

Full v2 with bookmarks, Dossier sessions, background rescan, and polish:

- Phase 2 bookmarks: 1-2 days
- Phase 3 local sessions/Dossier: 2-4 days
- Phase 4 resilience/polish: 2-4 days
- Broader regression pass: 1-2 days

Expected full total: about 2-3 focused engineering weeks, depending on how much embedded metadata and Android Auto polish is included.

## Recommended Build Order

1. Add schema/source/mode foundation and migration.
2. Add Local mode Settings UI plus SAF permission handling.
3. Build scanner and import local books into Room.
4. Update PlaybackManager and SyncManager for local books.
5. Make Library/Home/Book Detail mode-aware.
6. Test Phase 1 thoroughly on device.
7. Add local bookmarks.
8. Add local listening sessions and Dossier.
9. Add background rescan and onboarding polish.
