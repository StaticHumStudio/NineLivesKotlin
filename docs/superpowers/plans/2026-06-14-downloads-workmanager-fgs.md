# Downloads: sequential WorkManager engine with a foreground-service notification

Status: planned (2026-06-14). Sibling work to the offline library-switch fix (PR #46).

## Problem

Two things are wrong with downloads today.

1. They die the moment you switch away. `DownloadManager` runs every download on a
   plain in-process `CoroutineScope(SupervisorJob() + Dispatchers.IO)` (`service/DownloadManager.kt:50`)
   with no foreground service and no notification. Once the app is backgrounded the
   process is a kill candidate and the scope (with the in-flight download) goes with it.
2. They run two at a time. Concurrency is `Semaphore(MAX_CONCURRENT)` with
   `MAX_CONCURRENT = 2` (`DownloadManager.kt:43,53`). We want strictly one at a time.

## Goal

Move downloads onto WorkManager, running as a long-running worker that promotes itself
to a `dataSync` foreground service with an ongoing progress notification. This is the
Google-preferred shape for user-initiated downloads on Android 14+, and it gives us, for
free: survives app-switch (FGS keeps the process), survives full process death and reboot
(WorkManager reschedules), trivial sequential execution, and built-in constraint handling.

Target device is Android 16 (API 36). The Android 15/16 `dataSync` FGS cumulative cap
(6h per 24h) is a non-issue at per-book granularity (a book is minutes).

## Current architecture (what we keep vs replace)

Keep:
- Room queue: `DownloadItemDao` / `DownloadItemEntity`, status ordinals
  (Queued/Downloading/Paused/Completed/Failed/Cancelled).
- The careful streaming logic in `processDownload` (`DownloadManager.kt:228-409`):
  `.part` file then atomic rename, skip already-finished files, per-file retry with
  exponential backoff, cancellation cleanup.
- `DownloadManager` as the public facade the UI calls
  (`queueDownload` / `pauseDownload` / `resumeDownload` / `cancelDownload` / `deleteDownload`).
  `DownloadsViewModel` and `BookDetailViewModel` keep calling it.

Replace:
- The in-process coroutine scope + `Semaphore(2)` + `activeJobs` map. WorkManager owns
  execution and concurrency now.

## Design

Chosen shape: **one worker per book, serialized via a single unique-work chain.** This is
the most WorkManager-idiomatic option, makes "sequential" trivial, and isolates retry,
progress, and the FGS notification per book.

### Pieces

1. `DownloadEngine` (new, plain injectable class). Lift the streaming/retry/rename logic
   out of `processDownload` into a pure-ish class with injected deps (`AudiobookshelfApi`,
   `DownloadItemDao`, `AudioBookDao`, `SettingsManager`). Exposes
   `suspend fun download(downloadId, audioBook, onProgress): Result`. This stays unit
   testable without WorkManager and reuses the existing per-file logic verbatim.

2. `DownloadWorker : CoroutineWorker` (new, `@HiltWorker`). `doWork()`:
   - reads the `downloadId` from `inputData`, loads the `DownloadItem` + `AudioBook` from Room,
   - calls `setForeground(getForegroundInfo())` to promote to FGS,
   - runs `DownloadEngine.download(...)`, updating the notification + Room on each progress tick,
   - returns `Result.success()` / `Result.failure()` (engine handles per-file retries internally;
     use `Result.retry()` only for whole-book transient network failures if we want WM backoff).
   - `getForegroundInfo()` returns `ForegroundInfo(NOTIF_ID, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)`.

3. `DownloadManager` becomes a thin facade over WorkManager:
   - `queueDownload`: keep the validation/insert-Queued-row logic, then
     `WorkManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)`
     where `request` is tagged with the `downloadId`. `APPEND_OR_REPLACE` on one shared name
     is what enforces strict sequential order.
   - `pauseDownload`: `cancelAllWorkByTag(downloadId)` + set Room status Paused.
   - `resumeDownload`: re-enqueue (APPEND) with the same `downloadId` tag. The engine skips
     already-finished files and re-pulls the interrupted one (current behavior). Note: a
     resumed book goes to the back of the queue, which is acceptable.
   - `cancelDownload` / `deleteDownload`: cancel by tag + existing Room/file cleanup.
   - Constraint: `NetworkType.CONNECTED` (later: a wifi-only setting via `UNMETERED`).

4. Notification. New "Downloads" channel (mirror the `PlaybackService` channel setup).
   Ongoing notification: title `Downloading <book>`, determinate progress bar, optional
   `(n of m files)`. Updated from the worker via `NotificationManagerCompat.notify(NOTIF_ID, ...)`.
   `POST_NOTIFICATIONS` is already declared and requested (for playback). FGS runs even if
   the user denied the notification; the notification just will not show.

5. Progress to the UI. Drive it from Room (`DownloadItemDao.observeActive()` is already a
   Flow the UI can collect) so progress survives process death. The in-process
   `progressUpdates` SharedFlow can stay for liveliness but is no longer the source of truth.

### Wiring (the fiddly part)

- Deps (add to `gradle/libs.versions.toml` + `app/build.gradle.kts`):
  `androidx.work:work-runtime-ktx`, `androidx.hilt:hilt-work`,
  ksp `androidx.hilt:hilt-compiler`, and `androidx.work:work-testing` (testImplementation).
- Hilt + WorkManager: `NineLivesApp` (`@HiltAndroidApp`) must implement
  `Configuration.Provider`, inject `HiltWorkerFactory`, and expose it via
  `workManagerConfiguration`. Disable the default WorkManager initializer in the manifest
  (remove the `androidx.startup` `WorkManagerInitializer` provider node) so the Hilt factory
  is used. This is the most common breakage point. Verify with a smoke enqueue.
- Manifest: add `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>`.
  WorkManager provides its own `SystemForegroundService`, so no new `<service>` for us, but
  confirm the merged manifest carries `dataSync` once we set the FGS type in `ForegroundInfo`.

## TDD order

1. `DownloadEngine` extracted, existing per-file behavior covered by unit tests first
   (skip-already-downloaded, retry-same-file-not-skip, sanitizeFileName, atomic rename).
   Pure refactor, behavior identical to current `processDownload`.
2. Sequential-policy decision + tag/work-name helpers as small pure functions, unit tested.
3. `DownloadWorker` via `TestListenableWorkerBuilder` (work-testing): asserts it runs the
   engine and reports foreground info with the `dataSync` type.
4. `DownloadManager` facade: assert enqueue uses the shared unique name with APPEND_OR_REPLACE
   and tags by id; pause cancels by tag.
5. Manual device pass: queue 3 books, confirm one-at-a-time, ongoing notification, switch away
   and lock, confirm the active download keeps going and the queue drains.

## Risks / open questions

- Hilt WorkManager initializer wiring (Configuration.Provider + disabling the default
  initializer). Budget time for this.
- Notification permission denied on 13+: FGS still runs, but verify no crash and graceful
  no-notification path.
- Resume-to-back-of-queue ordering: confirm that is acceptable UX (likely yes).
- Whether to keep the SharedFlow progress path at all, or fully commit to Room as the single
  source. Lean Room.

## Files (anticipated)

- new `service/download/DownloadEngine.kt`
- new `service/download/DownloadWorker.kt`
- new `service/download/DownloadNotifications.kt` (channel + builder)
- edit `service/DownloadManager.kt` (facade over WorkManager, drop scope/semaphore)
- edit `NineLivesApp.kt` (Configuration.Provider + HiltWorkerFactory)
- edit `AndroidManifest.xml` (FGS_DATA_SYNC permission, disable default WM initializer)
- edit `gradle/libs.versions.toml`, `app/build.gradle.kts` (deps)
- new tests under `app/src/test/.../service/download/`
