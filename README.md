# Nine Lives Audio

A feature-rich Android audiobook player for [Audiobookshelf](https://www.audiobookshelf.org/) servers. Built with Kotlin, Jetpack Compose, and Media3. Wrapped in **The Archive Beneath** — a dark cosmic vault aesthetic with gold containment frames, progress rings, stone surfaces, and atmospheric anomalies.

**Android 11+ (API 30) | Kotlin | Jetpack Compose | Material 3**

---

## Features

### Playback

- **Background audio** via Media3 ExoPlayer foreground service — keeps playing with the screen off
- **Media notification** with play/pause, skip forward/back, seekable progress bar, and cover art
- **Lock screen controls** via MediaSession
- **Android Auto** support with full media browsing, search, and chapter skip controls
- **Audio focus** handling — automatically pauses for phone calls and other media
- **Headphone disconnect** auto-pause (BECOME_NOISY)
- **Multi-track playlist** support via ExoPlayer concatenation
- **Playback speed** adjustable from 0.5x to 3.0x with real-time control
- **Volume boost** slider (0 to 10 dB) for quiet audiobooks
- **9-band equalizer** (31 Hz to 8 kHz) with per-band gain adjustment and preset reset
- **Chapter navigation** — skip to previous/next chapter, chapter-level seek bar with chapter markers
- **Dual progress rings** — outer gold ring for book progress, inner halo for chapter progress
- **Mini player** persistent bar across all screens with quick controls

### Sleep Timer

- **Configurable countdown timer** with on-screen display
- **Motion-sensing grace window** — when the timer expires, a 60-second grace period detects if you're still awake via accelerometer; extends by 2 minutes if motion is detected
- **Shake-to-reset** — shake your device to reset the timer to its original duration
- **Auto-rewind on expiration** — configurable rewind (0 to 60 seconds) so you don't lose your place when you fall asleep
- All sleep timer settings individually toggleable in Settings

### Nine Lives Home

- **3x3 vault wall grid** of your most recently played books — up to 9 "lives"
- **Fluorescent progress glow** per tile — 4-layer neon-tube animation (outer bleed, mid corona, core tube, hot filament) with continuous breathing effect
- **Life Index badges** (LIFE I through LIFE IX) based on cumulative listening hours
- **Weight indicators** (LIGHT, MEDIUM, HEAVY) showing how much time you've invested
- **Corner sigils** — gold dot for downloaded books, purple dot for bookmarked
- One deterministic misaligned tile for Archive aesthetic
- Last-played timestamps, total listening time per book
- Fast initial sync (500 ms delay, progress-first loading)

### The Archive (Library)

- **Compact list view** with 72 dp cover thumbnails and detailed book information
- **11 sorting options**: Recently Added, Title A-Z / Z-A, Author A-Z / Z-A, Progress High / Low, Duration Long / Short, Recently Played, Unplayed First
- **Stone tab system**: All / In Progress / Completed / Downloaded
- **Grouping modes**: flat list, by Series, by Author, by Genre
- **Search** by title, author, narrator, or series via relic search bar
- **Random atmospheric comments** on each book — deterministically assigned from 20+ variations
- **Enhanced progress rings** with 3D shadow effects and pronounced glow
- Server sync with local Room SQLite caching
- Auto-switches to downloaded-only mode when offline
- Pull-to-refresh

### Book Detail

- Full book info: cover art, title, author, narrator, series, description, genre tags, duration
- Overall progress percentage and chapters completed
- Play/Resume, Download, and Share actions
- **Listening history** — expandable session list showing date, time, and duration per session

### Bookmarks

- **Create bookmarks** at any position with custom titles
- Jump to any bookmark instantly
- Formatted timestamps (HH:MM:SS)
- Server-synced via Audiobookshelf

### Downloads

- **Concurrent download queue** — 2 simultaneous downloads
- Pause, resume, and cancel controls per download
- **Retry with exponential backoff** — per-file retry, never skips on failure
- Progress tracking with throttled UI updates
- **Full offline playback** from locally stored files
- Auto-cover download option
- Proper ResponseBody lifecycle management to prevent connection pool exhaustion

### Progress Sync

- **Bidirectional sync** with Audiobookshelf server
- Throttled position reporting during active playback
- **Offline queue** — queues progress updates and flushes when connectivity returns
- **Race-safe flush** — only deletes synced entries, preserves in-flight writes
- Local progress always preserved during server sync
- Playback session tracking for Audiobookshelf listening statistics

### Nightwatch Dossier (Listening Analytics)

A full listening analytics dashboard with shareable summary cards.

- **6 time periods**: 30 Days, 3 Months, 6 Months, 1 Year, This Year, Last Year
- **Overview stats**: total listening time, session count, unique books, books finished, daily average, best listening day
- **Per-book breakdown**: listening time, sessions, progress, chapter info, completion status
- **Narrator analysis**: top narrators ranked by listening time with book counts
- **Author analysis**: top authors ranked by listening time
- **Genre breakdown**: listening time and book count per genre
- **Temporal patterns**: hourly listening distribution, peak hour, peak day of week
- **Contextual whispers**: atmospheric commentary that adapts to your listening habits
- **Session sanitization**: caps duration at wall-clock time, 4-hour hard cap on unverified sessions, filters noise sessions under 60 seconds
- **Shareable summary card** — capture and share your listening stats via PixelCopy

### Settings

- **Server connection**: URL, username, password, connect/disconnect/test/refresh
- **Self-signed certificate support** — opt-in only, not applied globally
- **Auto sync** with configurable interval (5 to 60 minutes)
- **Auto-rewind on resume** — Smart or Flat mode, configurable from 0 to 120 seconds
- **Sleep timer configuration** — motion sensing, shake-to-reset, and rewind on expiration toggles
- **9-band equalizer** with volume boost
- **Theme controls** — toggle anomalies, whispers, and reduced motion independently
- **Targeted cache clearing** — clears library data only, never touches progress or downloads
- **Diagnostics display** — app version, device model, Android version, API level, build type

### Feedback and Bug Reports

- **In-app bug reports and upgrade requests** with automatic device diagnostics
- Optional logcat inclusion (last 500 lines)
- Launches email intent pre-filled with device info, app version, and build details

### Android Auto

- **Full media browsing tree**: Recently Played and Libraries at the root, drill into any library or book
- **Search**: find books by title, author, narrator, or genre
- **Playback controls**: play/pause, skip forward/back, seek
- **Chapter skip buttons** for navigating between chapters hands-free
- Cover art, title, and author displayed on car screen
- Notification with full playback controls

### Offline Mode

- **Automatic network detection** — monitors connectivity state in real-time
- Library auto-filters to downloaded books when server is unreachable
- Offline progress queue syncs automatically when connection returns
- Periodic server reachability checks (60-second interval)
- Connection pool cleanup and stale TCP eviction on foreground recovery

### Security and Privacy

- **AES-256-GCM encrypted storage** for server credentials and auth tokens via EncryptedSharedPreferences
- **Unique device ID** — randomly generated UUID per device, never based on hardware identifiers
- **Self-signed certificate trust** only when explicitly enabled by the user
- **ACRA crash reporting** — optional crash dialog with user comments, safe field collection (no auth tokens or preferences), delivered via email
- **No analytics or tracking** — your listening data stays between you and your Audiobookshelf server

---

## Theme: The Archive Beneath

There is no "normal mode." The cosmic vault aesthetic is the identity of the app.

### Palette

| Token | Hex | Role |
|---|---|---|
| `ArchiveVoidDeep` | `#050810` | Primary background |
| `ArchiveVoidBase` | `#0A0E1A` | Elevated surfaces |
| `GoldFilament` | `#C5A55A` | Primary accent, progress rings, interactive elements |
| `ImpossibleAccent` | `#8B5CF6` | Chapter progress, secondary accent |
| `ArchiveTextPrimary` | `#E8E2D6` | Primary text |
| `ArchiveTextMuted` | `#6B6560` | Secondary text |
| `ArchiveOutline` | `#2A2520` | Borders, dividers |

### Visual System

- **ContainmentFrame** — dual-stroke gold border around tiles
- **ContainmentProgressRing** — Canvas-drawn circular progress with glow, sweep gradient, and 3D shadow
- **FluorescentSquareProgress** — rounded-square neon-tube progress with 4-layer glow and breathing animation
- **SigilProgressBar** — linear progress with shimmer clipped to fill
- **StoneSlabCard / RelicSurface** — stone-textured surface components
- **FilamentDivider** — gold-accented section dividers
- **AnomalyHost** — overlay system for visual anomalies (ink bleed, tear veil, crack whisper) on cooldown schedules
- **CopyEngine** — 3-tier copy system (Normal / Ritual / Unhinged) for screen titles and flavor text
- **WhisperService** — atmospheric text overlays triggered by screen entry and user actions

---

## Architecture

| Layer | Technology |
|---|---|
| UI | Jetpack Compose, Material 3, Archive Beneath custom theme |
| State | StateFlow, SharedFlow, collectAsStateWithLifecycle |
| ViewModel | Hilt `@HiltViewModel`, viewModelScope |
| DI | Hilt / Dagger 2.59.1 |
| Media | Media3 ExoPlayer 1.5.1, MediaSession, MediaSessionService |
| Network | Retrofit 2.11.0, OkHttp 4.12.0, Kotlinx Serialization 1.8.1 |
| Database | Room 2.7.1 with SQLite, explicit migrations |
| Crypto | EncryptedSharedPreferences (AES-256-GCM) |
| Async | Kotlin Coroutines 1.10.2, SupervisorJob, Dispatchers |
| Image Loading | Coil 2.7.0 |
| Background | WorkManager 2.10.0 |

---

## Build

### Prerequisites

- Android Studio (2024.2.1+) or JDK 21
- Android SDK 36
- Kotlin 2.2.10
- An Audiobookshelf server

### Instructions

```bash
git clone https://github.com/StaticHumStudio/NineLivesKotlin.git
cd NineLivesKotlin

# Build
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

### Connect to Your Server

1. Open Nine Lives Audio
2. Go to Settings
3. Enter your Audiobookshelf server URL, username, and password
4. Tap Connect

---

## License

MIT

---

Built by **StaticHum Studio** — [@StaticHumStudio](https://github.com/StaticHumStudio)
