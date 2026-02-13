# Nine Lives Audio

An Android audiobook player for [Audiobookshelf](https://www.audiobookshelf.org/) servers. Built with Kotlin, Jetpack Compose, and Media3. Wrapped in the **Archive Beneath** — a dark cosmic vault aesthetic with gold containment frames, progress rings, stone surfaces, and atmospheric anomalies.

---

## Features

### Playback
- **Background audio** via Media3 ExoPlayer foreground service
- **Media notification** with play/pause, skip, seek bar, and cover art
- **Lock screen controls** via MediaSession
- **Android Auto** support
- **Audio focus** handling (pauses for phone calls, other media)
- **Headphone disconnect** auto-pause (BECOME_NOISY)
- **Multi-track** playlist support (ExoPlayer concatenation)
- **Playback speed** 0.5x – 3.0x
- **Chapter navigation** — skip previous/next chapter, chapter-level seek bar
- **Dual progress rings** — outer gold ring = book progress, inner halo = chapter progress
- **Sleep timer**
- **Mini player** persistent bar across all screens

### Nine Lives Home
- **3×3 vault wall grid** of recently played books (up to 9 "lives")
- Gold containment frames with cosmic progress rings per tile
- Corner sigils for download and bookmark status
- One deterministic misaligned tile for Archive aesthetic
- Weight badges, last-played timestamps, listening time summaries
- Fast initial sync (500ms delay, progress-first)

### The Archive (Library)
- **2-column grid** with containment frames and progress rings
- **Stone tab system**: All / In Progress / Completed / Downloaded
- Relic search bar with stone surface aesthetic
- Server sync with local caching (Room SQLite)
- Search by title, author, narrator, series
- Sort by title, author, progress, recently played
- Auto-switches to downloaded-only when offline
- Pull-to-refresh

### Downloads
- Concurrent download queue (2 simultaneous)
- Pause / resume / cancel
- Retry with exponential backoff (per-file retry, not skip-on-failure)
- Progress tracking with throttled UI updates
- Offline playback from local files
- Proper ResponseBody lifecycle management

### Progress Sync
- Bidirectional sync with Audiobookshelf server
- Throttled position reporting during playback
- Offline queue — progress pushes when connectivity returns
- Race-safe flush: only deletes synced entries, preserves in-flight writes
- Local progress preserved during server sync (offline playback safe)
- Playback session tracking (Audiobookshelf listening stats)

### Settings
- Server URL + username/password authentication
- Self-signed certificate support (conditional, not blanket)
- Encrypted token storage (EncryptedSharedPreferences)
- Cache clearing (preserves progress and downloads)
- **Anomalies** toggle — screen tears, ink bleed, crack whisper effects
- **Whispers** toggle — atmospheric contextual text overlays
- **Reduced Motion** toggle — accessibility, disables all animations

---

## Theme: The Archive Beneath

There is no "normal mode." The cosmic archive aesthetic is the app's sole identity.

### Palette
| Token | Hex | Role |
|-------|-----|------|
| `ArchiveVoidDeep` | `#050810` | Primary background |
| `ArchiveVoidBase` | `#0A0E1A` | Elevated surfaces |
| `GoldFilament` | `#C5A55A` | Primary accent, progress rings, interactive elements |
| `ImpossibleAccent` | `#8B5CF6` | Chapter progress, secondary accent |
| `ArchiveTextPrimary` | `#E8E2D6` | Primary text |
| `ArchiveTextMuted` | `#6B6560` | Secondary/muted text |
| `ArchiveOutline` | `#2A2520` | Borders, dividers |

### Visual System
- **ContainmentFrame** — dual-stroke gold border (outer faint + inner bright) around tiles
- **CornerSigils** — small indicator dots for downloaded (gold) and bookmarked (purple) state
- **CosmicProgressRing** — Canvas-drawn circular progress with glow, sweep gradient, optional end-cap dot
- **SigilProgressBar** — linear progress bar with shimmer animation (clipped to fill)
- **StoneSlabCard / RelicSurface** — stone-textured surface components
- **FilamentDivider** — gold-accented section dividers
- **AnomalyHost** — overlay system for ink bleed, tear veil, and crack whisper effects on cooldown schedules
- **CopyEngine** — 3-tier copy system (Normal / Ritual / Unhinged) for screen titles and flavor text
- **WhisperService** — atmospheric text overlays triggered by screen entry and user actions

---

## Architecture

| Layer | Tech |
|-------|------|
| UI | Jetpack Compose, Material 3, Archive Beneath theme |
| State | StateFlow, SharedFlow, collectAsStateWithLifecycle |
| ViewModel | Hilt `@HiltViewModel`, viewModelScope |
| DI | Hilt / Dagger |
| Media | Media3 ExoPlayer, MediaSession, MediaSessionService |
| Network | Retrofit, OkHttp, Kotlinx Serialization |
| Database | Room (SQLite), DAOs, Entity mappers |
| Storage | EncryptedSharedPreferences, DataStore (unhinged prefs), JSON settings |
| Async | Kotlin Coroutines, SupervisorJob, Dispatchers |
| Theme | CompositionLocal (`LocalUnhingedSettings`), 40+ Archive color tokens |

### Key Design Decisions
- **MediaSession owned by PlaybackManager**, not the Service — keeps PlaybackService thin (~90 lines)
- **MediaController.Builder** to start the service (not `startForegroundService()`) — avoids `ForegroundServiceDidNotStartInTimeException`
- **MediaSession created before `player.prepare()`** — ensures Media3 captures all state transitions for the notification
- **No `fallbackToDestructiveMigration`** — explicit Room migrations required as schema evolves
- **Self-signed cert trust manager** only installed when the user opts in, not unconditionally
- **No dual theme** — Archive Beneath is always active; `UnhingedSettings` controls feature intensity (anomalies, whispers, motion), not theme switching
- **Navigation uses `popUpTo(findStartDestination)`** everywhere — consistent back stack behavior between bottom nav tabs and deep links
- **BookId URL-encoded** in navigation routes — safe for IDs containing special characters

---

## Build

### Prerequisites
- Android Studio (2024.2.1+) or JDK 21
- Android SDK 35
- Kotlin 2.1.0
- An Audiobookshelf server

### Instructions
```bash
git clone https://github.com/StaticHumStudio/NineLivesKotlin.git
cd NineLivesKotlin

# Set JAVA_HOME (adjust for your system)
export JAVA_HOME="/path/to/jdk-21"

# Build
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

### Connect to Server
1. Open Nine Lives Audio
2. Go to Settings
3. Enter your Audiobookshelf server URL, username, and password
4. Tap Connect

---

## Bug Fix History

### Round 1 — Initial Audit (27 bugs)

**Critical (service crashes, no notification):**
- Media3 notification system — replaced manual notification with `DefaultMediaNotificationProvider`
- `ForegroundServiceDidNotStartInTimeException` — replaced `startForegroundService()` with `MediaController.Builder`
- MediaSession lifecycle — created before `player.prepare()` so state transitions are captured
- `POST_NOTIFICATIONS` runtime permission (Android 13+)
- Notification channel mismatch between manual channel and Media3's default

**High (playback, audio, security):**
- No AudioAttributes / audio focus — phone calls now pause playback
- No BECOME_NOISY handling — headphone disconnect now pauses
- SSL trust manager installed unconditionally — now conditional on user setting
- Service session refresh on book reload (`addSession()`)
- Slider seek storm — seek only fires on drag release, not 60x/sec during drag
- Server sync overwrites local progress — offline playback positions preserved

**Medium (data integrity, sync, UX):**
- `clearCache()` wiped all tables (progress, downloads, offline queue) — now targeted
- `clearCompleted()` left orphaned files on disk — now deletes files too
- SyncManager throttle operator precedence bug
- Download retry byte tracking was a no-op
- `accumulatedListenTime` drifted from wall-clock time
- Position polling spawned unbounded coroutines
- Settings race condition on startup
- Zombie PlaybackService after `stop()`
- `isAuthenticated` always returned `true`

**Low:**
- No cover art in notification — `setArtworkUri()` added
- Notification icon tint color
- Destructive database migration in production removed

### Round 2 — Deep Code Review (5 bugs)

- **AnomalyScheduler immediate fire** — `lastTriggerTime` initialized to `0L` caused anomalies to trigger on every screen entry; now initializes to `System.currentTimeMillis()`
- **ProgressRepository flush race condition** — `deleteAll()` wiped entries inserted during network round-trips; now captures fetched IDs and deletes only those via `deleteByIds()`
- **DownloadManager retry skips failed file** — `continue` in for-loop advanced to next file instead of retrying; restructured to inner `while (!fileSuccess)` retry loop per file
- **ConnectivityMonitor unbounded reachability checks** — network flaps spawned concurrent `checkServerReachable()` coroutines; added cancel-and-replace `reachabilityJob` pattern
- **SigilProgressBar shimmer bleed** — `drawRect` used full `barWidth` instead of `progressWidth`; shimmer now clipped to filled portion

### Round 3 — User-Reported Issues (4 fixes)

- **Slow initial sync** — reduced delay from 3s to 500ms, changed to sequential progress-first sync so home grid populates immediately
- **Player progress bars** — bottom slider now seeks within current chapter (was book-level); outer gold ring remains whole-book progress
- **Chapter skip buttons unwired** — `SkipPrevious`/`SkipNext` had empty `onClick = {}`; now wired to `seekToChapter()` with bounds checking and visual dimming at boundaries
- **Navigation stuck on Player** — MiniPlayer and BookDetail pushed Player without `popUpTo`; now uses `popUpTo(findStartDestination)` matching BottomNavBar pattern

### Round 4 — Final Review (7 bugs)

- **SyncManager connectivity listener leak** — coroutine leaked on every `start()` call; now tracked as `connectivityJob` and cancelled in `stop()`
- **DownloadManager ResponseBody leak** — `response.body()` not closed on error paths (connection pool exhaustion); wrapped in `body.use { }`
- **BookId not URL-encoded** — navigation route broke on IDs with special characters; added `Uri.encode()`
- **PlaybackManager `getCurrentPosition()` bounds mismatch** — condition allowed spurious offset for single-track books; added `trackDurations.size > 1` guard
- **PlaybackManager `stop()` player race** — released ExoPlayer while background coroutine still syncing; reordered to capture values first, release immediately, flush async
- **HomeViewModel progress percent range** — assumed 0–1 but API can return 0–100; added conditional normalization
- **MainActivity lifecycle collection** — `collectAsState()` → `collectAsStateWithLifecycle()` for lifecycle-safe flow collection

---

## License

MIT

---

Built by **StaticHum Studio** — [@StaticHumStudio](https://github.com/StaticHumStudio)
