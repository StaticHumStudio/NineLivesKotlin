# v2.0 Pre-Release Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the three defects consciously deferred out of PR #41 (decided 2026-06-02) so the first published 2.0.x build is correct and stable, then cut that release.

**Architecture:** Three independent, separately-shippable fixes — a BookDetail perf fix (no schema), an offline-progress correctness fix (Room v6→v7 migration), and a playback-service teardown fix (Media3 lifecycle, device-verified) — followed by the release cut. Nothing here depends on another task; they can merge in any order, but the plan sequences them lowest-risk-first.

**Tech Stack:** Kotlin, Hilt, Room (currently schema v6), Media3, Coroutines/Flow, Retrofit/OkHttp. Compose UI.

## Global Constraints

- **Repo:** `/projects/NineLivesKotlin` (real path, symlinked from `~/projects`). Branch off `master`; one branch/PR per task.
- **Build/test offline:** `./gradlew :app:testDebugUnitTest --offline` and `:app:assembleDebug --offline`. `adb` at `~/.local/opt/android-sdk/platform-tools/adb` (not on PATH). Test device SM-S948U; debug app id `com.ninelivesaudio.app.debug` (coexists with the Play build).
- **Test style (house convention):** pure decision functions + plain JUnit4, NO mock infra (no MockK/Mockito/Robolectric/mock-web-server). DAO/migration/Compose/service-lifecycle changes are **build + on-device verified**, not unit-tested.
- **CI:** GitHub Actions "build" check (~1m50s) runs per PR and must pass; repo auto-merge is DISABLED — wait for the check, then `gh pr merge <n> --squash --delete-branch`.
- **Room:** `exportSchema = true`; adding a version auto-writes `app/schemas/.../<N>.json` on build — commit it. Migrations live in `data/local/migration/Migrations.kt` and are wired via `ALL_MIGRATIONS` in `AppModule`. Only `fallbackToDestructiveMigrationOnDowngrade` is set — an unmigrated upgrade path crashes, so every version bump needs a migration.
- **Commit trailer (verbatim, every commit):**
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

---

## Task 1: BookDetail — stop re-querying the download row on every progress tick

**Files:**
- Modify: `app/src/main/java/com/ninelivesaudio/app/ui/bookdetail/BookDetailViewModel.kt` (`observeDownloadProgress`, ~lines 171–182)

**Problem:** `observeDownloadProgress` calls `downloadItemDao.getByAudioBookId(bookId)` inside the `progressUpdates.collect { }` — a DB round-trip on **every** progress emission (~10/sec during a download) purely to compare `entity.id == progress.downloadId`. Pure jank, no correctness effect.

**Fix:** `DownloadProgress` already carries `audioBookId` (the `downloadCompleted` collector below filters on `completedItem.audioBookId == bookId`), so filter the progress flow on `progress.audioBookId == bookId` instead of resolving the row each tick. No DAO call in the hot path.

**Interfaces:**
- Consumes: `downloadManager.progressUpdates` (a `Flow<DownloadProgress>`), `DownloadProgress.audioBookId: String`, `DownloadProgress.downloadedBytes`, `DownloadProgress.totalBytes`.
- Produces: nothing new (behavior-preserving perf fix).

- [ ] **Step 1: Confirm `DownloadProgress` exposes `audioBookId`**

Run: `grep -n "class DownloadProgress\|data class DownloadProgress" -A8 app/src/main/java/com/ninelivesaudio/app/service/DownloadManager.kt`
Expected: a field `val audioBookId: String` (and `downloadId`, `downloadedBytes`, `totalBytes`). If `audioBookId` is absent, STOP — fall back to caching the id once at collect start (`val downloadId = downloadItemDao.getByAudioBookId(bookId)?.id` before the `collect`) and compare against it; do not query inside the loop.

- [ ] **Step 2: Replace the per-tick query with a field filter**

In `observeDownloadProgress`, change the first collector from:

```kotlin
downloadManager.progressUpdates.collect { progress ->
    val entity = downloadItemDao.getByAudioBookId(bookId)
    if (entity != null && entity.id == progress.downloadId) {
        val pct = if (progress.totalBytes > 0) {
            (progress.downloadedBytes.toDouble() / progress.totalBytes * 100).toInt().coerceIn(0, 100)
        } else 0
        _uiState.update { it.copy(downloadProgress = pct) }
    }
}
```

to:

```kotlin
downloadManager.progressUpdates.collect { progress ->
    if (progress.audioBookId == bookId) {
        val pct = if (progress.totalBytes > 0) {
            (progress.downloadedBytes.toDouble() / progress.totalBytes * 100).toInt().coerceIn(0, 100)
        } else 0
        _uiState.update { it.copy(downloadProgress = pct) }
    }
}
```

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew :app:assembleDebug --offline`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ninelivesaudio/app/ui/bookdetail/BookDetailViewModel.kt
git commit -m "perf: filter BookDetail download progress by audioBookId instead of querying per tick"
```

- [ ] **Step 5 (optional, on-device):** Download a book from its detail screen and confirm the progress bar still advances 0→100% and the "Downloaded" state flips on completion.

---

## Task 2: Offline progress queue — carry `duration` (Room v6→v7)

**Files:**
- Modify: `app/src/main/java/com/ninelivesaudio/app/data/local/entity/PendingProgressEntity.kt` (add `Duration` column)
- Modify: `app/src/main/java/com/ninelivesaudio/app/data/local/migration/Migrations.kt` (add `MIGRATION_6_7`, append to `ALL_MIGRATIONS`)
- Modify: `app/src/main/java/com/ninelivesaudio/app/data/local/AppDatabase.kt` (`version = 6` → `7`)
- Modify: `app/src/main/java/com/ninelivesaudio/app/data/repository/ProgressRepository.kt` (`enqueuePendingProgress` param + `flushPendingProgress` push, + `PendingProgressEntry`)
- Modify: `app/src/main/java/com/ninelivesaudio/app/service/PlaybackManager.kt` (~line 1365 enqueue call)
- Modify: `app/src/main/java/com/ninelivesaudio/app/service/SyncManager.kt` (~lines 370, 374 enqueue calls)
- Create (auto-generated, commit it): `app/schemas/com.ninelivesaudio.app.data.local.AppDatabase/7.json`
- Test: `app/src/test/java/com/ninelivesaudio/app/data/repository/PendingProgressDurationTest.kt`

**Problem:** `PendingProgressEntity` has no `duration`, so `flushPendingProgress` calls the 3-arg `apiService.updateProgress(itemId, currentTime, isFinished)` (duration defaults to 0.0). A book finished/advanced **offline** syncs back to the server with duration 0, which Audiobookshelf can read as 0% / not-finished. Duration IS available at both enqueue call sites (`dur` in PlaybackManager, `safeDuration` in SyncManager), it's just dropped.

**Interfaces:**
- Produces: `PendingProgressEntity(..., duration: Double = 0.0)` with `@ColumnInfo(name = "Duration", defaultValue = "0")`; `ProgressRepository.enqueuePendingProgress(itemId, currentTime, isFinished, duration: Double = 0.0)`; `PendingProgressEntry(..., duration: Double)`.
- Consumes: existing `apiService.updateProgress(itemId, currentTime, isFinished, duration)` 4-arg overload (ApiService.kt:416).

- [ ] **Step 1: Write the failing test (pure "latest row → 4-arg push args" mapping)**

The push logic in `flushPendingProgress` is currently inline. Extract the per-item "which fields get pushed" into a pure top-level function so it's testable without the DB, mirroring `mergeSyncedBook`/`idsToArchive`. Create `app/src/test/java/com/ninelivesaudio/app/data/repository/PendingProgressDurationTest.kt`:

```kotlin
package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.local.entity.PendingProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingProgressDurationTest {

    private fun row(id: Long, ts: String, currentTime: Double, duration: Double) =
        PendingProgressEntity(id = id, itemId = "item-1", currentTime = currentTime,
            isFinished = 0, timestamp = ts, duration = duration)

    @Test
    fun `latest row by timestamp supplies currentTime and duration to push`() {
        val rows = listOf(
            row(1, "2026-07-01T10:00:00Z", currentTime = 100.0, duration = 3600.0),
            row(2, "2026-07-01T10:05:00Z", currentTime = 250.0, duration = 3600.0),
        )
        val push = latestPushArgs(rows)!!
        assertEquals(250.0, push.currentTime, 0.0)
        assertEquals(3600.0, push.duration, 0.0) // duration carried, not dropped to 0
    }

    @Test
    fun `empty rows produce no push`() {
        assertEquals(null, latestPushArgs(emptyList()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --offline --tests "com.ninelivesaudio.app.data.repository.PendingProgressDurationTest"`
Expected: FAIL — `latestPushArgs` unresolved, and `PendingProgressEntity` has no `duration` param.

- [ ] **Step 3: Add the `Duration` column to the entity**

In `PendingProgressEntity.kt`, add after the `IsFinished` column:

```kotlin
    @ColumnInfo(name = "Duration", defaultValue = "0")
    val duration: Double = 0.0,
```

- [ ] **Step 4: Add the pure helper + use it in the flush**

In `ProgressRepository.kt`, add a top-level data class + function (next to `PendingProgressEntry`):

```kotlin
/** The fields pushed for one item's queued progress: the latest row wins. */
data class PendingPushArgs(
    val currentTime: Double,
    val isFinished: Boolean,
    val duration: Double,
)

/** Pure: pick the latest row (by parsed instant) and map it to push args. */
internal fun latestPushArgs(rows: List<PendingProgressEntity>): PendingPushArgs? {
    val latest = rows.maxByOrNull { it.timestamp.toEpochMillis() ?: 0L } ?: return null
    return PendingPushArgs(latest.currentTime, latest.isFinished == 1, latest.duration)
}
```

(`toEpochMillis` is already imported in this file.) Then rewrite the flush loop body to use it — replace the `for` loop in `flushPendingProgress`:

```kotlin
        for ((itemId, rows) in entriesByItem) {
            val push = latestPushArgs(rows) ?: continue
            val success = apiService.updateProgress(
                itemId,
                push.currentTime,
                push.isFinished,
                push.duration,
            )
            if (success) {
                rows.forEach { row -> deletableRowIds.add(row.id) }
            } else {
                allSuccess = false
            }
        }
```

Delete the now-unused `latestByItem` block above it.

- [ ] **Step 5: Thread duration through the enqueue API + entry mapping**

In `ProgressRepository.kt`:
- `enqueuePendingProgress` — add `duration: Double = 0.0` param and pass `duration = duration` into `PendingProgressEntity(...)`.
- `getPendingProgressEntries` — map `duration = entity.duration`.
- `PendingProgressEntry` data class — add `val duration: Double = 0.0`.

- [ ] **Step 6: Pass real duration at the two call sites**

`PlaybackManager.kt` (~line 1365) enqueue call — add the duration arg (the `dur` Duration is in scope in this block):

```kotlin
                progressRepository.enqueuePendingProgress(
                    book.id,
                    pos.toDouble(kotlin.time.DurationUnit.SECONDS),
                    isFinished = false,
                    duration = dur.toDouble(kotlin.time.DurationUnit.SECONDS),
                )
```

`SyncManager.kt` (~lines 370 and 374) — both enqueue calls have `safeDuration` in scope:

```kotlin
                progressRepository.enqueuePendingProgress(itemId, safeCurrentTime, computedFinished, safeDuration)
```

- [ ] **Step 7: Add the migration and bump the version**

In `Migrations.kt`, add after `MIGRATION_5_6`:

```kotlin
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE PendingProgressUpdates ADD COLUMN Duration REAL NOT NULL DEFAULT 0")
    }
}
```

Append `MIGRATION_6_7` to the `ALL_MIGRATIONS` array. In `AppDatabase.kt`, change `version = 6` to `version = 7`.

- [ ] **Step 8: Run the test to verify it passes + build**

Run: `./gradlew :app:testDebugUnitTest --offline --tests "com.ninelivesaudio.app.data.repository.PendingProgressDurationTest"` → PASS.
Run: `./gradlew :app:assembleDebug --offline` → BUILD SUCCESSFUL (Room writes `7.json`; confirm `git status` shows the new schema file).

- [ ] **Step 9: Commit (include the exported schema)**

```bash
git add app/src/main/java/com/ninelivesaudio/app/data/local/entity/PendingProgressEntity.kt \
        app/src/main/java/com/ninelivesaudio/app/data/local/migration/Migrations.kt \
        app/src/main/java/com/ninelivesaudio/app/data/local/AppDatabase.kt \
        app/src/main/java/com/ninelivesaudio/app/data/repository/ProgressRepository.kt \
        app/src/main/java/com/ninelivesaudio/app/service/PlaybackManager.kt \
        app/src/main/java/com/ninelivesaudio/app/service/SyncManager.kt \
        app/src/test/java/com/ninelivesaudio/app/data/repository/PendingProgressDurationTest.kt \
        app/schemas/com.ninelivesaudio.app.data.local.AppDatabase/7.json
git commit -m "fix: carry duration through the offline progress queue (Room v6->v7)"
```

- [ ] **Step 10 (on-device, gates release):** Airplane-mode a downloaded book to ~90% + finish it → re-enable network → confirm the server receives the finished/near-complete state (not 0%). Verify migration on the real DB: `run-as` pull the `.db` (+`-wal`/`-shm`), check `PRAGMA user_version = 7` and the `Duration` column exists, existing rows intact.

---

## Task 3: `stopPlaybackService` — stop fighting Media3's foreground-service teardown

**Files:**
- Modify: `app/src/main/java/com/ninelivesaudio/app/service/PlaybackManager.kt` (`stopPlaybackService` ~1706; call sites at ~848 `stop()`, ~1132 `STATE_ENDED`, ~1190 `onPlayerError`, ~1490 `releaseAll`)

**Problem:** `stopPlaybackService()` calls `context.stopService(intent)` by hand. Media3's `MediaLibraryService` owns the foreground-service lifecycle; a manual `stopService` races its teardown and can throw `ForegroundServiceDidNotStartInTimeException` on Android 12+ if a controller (Auto, a notification) reconnects mid-teardown. The app also keeps a **persistent** `MediaLibrarySession` alive for Android Auto browsing, so aggressively killing the service on every stop/STATE_ENDED is doubly wrong.

**Approach (behavior decision — verify on device, NOT unit-testable):** Let Media3 manage the FGS. On stop / STATE_ENDED / onPlayerError, stop the *player* (`player.stop()` / `playWhenReady = false`) and release the `MediaController`, but do **not** call `context.stopService()` — Media3 drops out of foreground when playback is no longer active while keeping the session available for Auto. Only true shutdown (`releaseAll`, app teardown) releases the `MediaSession`, which lets Media3 stop the service itself. This is a genuine behavior change and MUST clear the device matrix below before merge.

**⚠️ Implementation note:** Before writing the final diff, spend ~30 min confirming the exact Media3 1.5.x teardown call for "session stays for Auto, service leaves foreground" vs "full stop" (candidates: relying on automatic FGS management after `player.stop()`, `MediaSessionService.pauseAllPlayersAndStopSelf()` for full stop, or `mediaSession?.release()` on shutdown only). Do this as a short spike at the top of the task; the steps below encode the recommended shape but the precise call may adjust.

- [ ] **Step 1: Reproduce the current risk on device (baseline)**

Play a downloaded book, background the app, let it reach the end (STATE_ENDED) while backgrounded; and separately reconnect Android Auto right as playback stops. Capture logcat filtered for `ForegroundServiceDidNotStartInTimeException` / `PlaybackService` to document current behavior.

Run: `~/.local/opt/android-sdk/platform-tools/adb -s <serial> logcat -d | grep -iE "ForegroundService|PlaybackService|onDestroy"`

- [ ] **Step 2: Remove the hand-rolled `stopService`; keep controller release**

Rewrite `stopPlaybackService()` so it releases the `MediaController` and its future but does NOT call `context.stopService()`:

```kotlin
    private fun stopPlaybackService() {
        try {
            mediaController?.release()
            mediaController = null
            mediaControllerFuture?.cancel(true)
            mediaControllerFuture = null
            Log.d(TAG, "Released media controller; Media3 manages FGS teardown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release media controller: ${e.message}", e)
        }
    }
```

- [ ] **Step 3: Ensure the player is actually stopped at the stop sites**

Confirm each caller (`stop()` ~848, `STATE_ENDED` ~1132, `onPlayerError` ~1190) sets `exoPlayer?.playWhenReady = false` / calls `exoPlayer?.stop()` before `stopPlaybackService()` so Media3 sees playback as inactive and leaves foreground. Add `exoPlayer?.stop()` where missing (STATE_ENDED / onPlayerError paths).

- [ ] **Step 4: Full teardown only in `releaseAll`**

Verify `releaseAll()` (~1465) releases the session (`mediaSession?.release()`) and player so Media3 stops the service on real shutdown. It already calls `stopPlaybackService()`; the session release is what now ends the service.

- [ ] **Step 5: Build**

Run: `./gradlew :app:assembleDebug --offline` → BUILD SUCCESSFUL.

- [ ] **Step 6: On-device matrix (all must pass; gates merge)**

Install the debug build and verify no `ForegroundServiceDidNotStartInTimeException` / ANR, and correct behavior, for:
1. Backgrounded playback → book reaches the end (STATE_ENDED).
2. `onPlayerError` (e.g. yank a streaming source) while backgrounded.
3. Android Auto connected, then stop playback, then re-browse — session stays, no crash.
4. Notification stop → the media notification clears and no service leak.
5. Foreground stop from the Player screen → clean stop, replay works.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/ninelivesaudio/app/service/PlaybackManager.kt
git commit -m "fix: let Media3 drive foreground-service teardown instead of hand-rolled stopService"
```

---

## Task 4: Cut the first published 2.0.x release

**Context:** Nothing has shipped to Play since **v1.0.1** (2026-04-17). All of v2 (local mode, archive shelf, sweep, and Tasks 1–3 above) is on `master`; a `v2.0.0` git tag exists but was never published. This task publishes the first 2.0.x build carrying everything.

- [ ] **Step 1: Decide the version.** The unpublished `v2.0.0` tag predates the archive/sweep/hardening work. Bump `versionName`/`versionCode` in `app/build.gradle.kts` to a fresh value (e.g. `2.0.1` / next code) so the store build is unambiguous and higher than the stale tag. Confirm the value with Static before tagging.
- [ ] **Step 2: Final full verification on `master`:** `./gradlew :app:testDebugUnitTest :app:assembleDebug --offline` green; run the deferred device checks (Task 2 Step 10, Task 3 Step 6) on the release-candidate build.
- [ ] **Step 3: Build the release AAB** (`./gradlew :app:bundleRelease`) with the upload key. NOTE: Play App Signing is enabled — Google re-signs; the local upload key is correct for the AAB.
- [ ] **Step 4: Upload the AAB to Play Console** (internal testing track first), write the "What's New" (local-first mode, Archive shelf, permanent-remove sweep, offline-progress fix). Static drives Play Console.
- [ ] **Step 5: Tag + GitHub release** matching the shipped version, with notes. (The stale `v2.0.0` tag can stay or be deleted — the new tag is the source of truth.)
- [ ] **Step 6: Housekeeping (optional):** delete the long-merged stale branches (`feat/app-themes`, `fix/offline-library-switch`, `fix/offline-sync-and-covers`, local + remote).

---

## Self-Review

- **Coverage:** All three deferred defects from `[[project_ninelives_prerelease_todo]]` (stopPlaybackService race, offline duration column, BookDetail per-tick query) each have a task; the release cut is Task 4. ✓
- **Placeholders:** Task 1/2 steps carry exact code. Task 3 is deliberately approach-plus-device-matrix with an explicit spike note, because it's a Media3-lifecycle behavior change that the repo convention verifies on-device rather than by unit test — not a hidden placeholder.
- **Type consistency:** `enqueuePendingProgress(itemId, currentTime, isFinished, duration)`, `PendingProgressEntity(..., duration)`, `PendingProgressEntry(..., duration)`, `latestPushArgs(rows): PendingPushArgs?`, and `PendingPushArgs(currentTime, isFinished, duration)` are used consistently across Task 2. Task 1 relies on `DownloadProgress.audioBookId` (verified in Step 1, with a documented fallback).
- **Sequencing:** independent tasks, ordered low-risk → high-risk → release. Task 2 (v7) and any future migration must not collide — v7 is the only new version here.
</content>
