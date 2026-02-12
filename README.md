# Nine Lives Audio

An Android audiobook player for [Audiobookshelf](https://www.audiobookshelf.org/) servers. Built with Kotlin, Jetpack Compose, and Media3.

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
- **Playback speed** 0.5x - 3.0x
- **Chapter navigation**
- **Sleep timer**
- **Mini player** persistent bar across all screens

### Library
- Server sync with local caching (Room SQLite)
- Search by title, author, narrator, series
- View modes: All / Series / Author / Genre
- Sort by title, author, progress, recently played
- Filter: hide finished, show downloaded only
- Auto-switches to downloaded-only when offline
- Pull-to-refresh

### Downloads
- Concurrent download queue (2 simultaneous)
- Pause / resume / cancel
- Retry with exponential backoff
- Progress tracking
- Offline playback from local files

### Progress Sync
- Bidirectional sync with Audiobookshelf server
- Throttled position reporting during playback
- Offline queue — progress pushes when connectivity returns
- Local progress preserved during server sync (offline playback safe)
- Playback session tracking (Audiobookshelf listening stats)

### Nine Lives Home
- 9 active "lives" — recently played books with cosmic energy gradients
- Progress rings, weight badges, last-played timestamps

### Settings
- Server URL + username/password authentication
- Self-signed certificate support (conditional, not blanket)
- Encrypted token storage (EncryptedSharedPreferences)
- Cache clearing (preserves progress and downloads)

---

## Architecture

| Layer | Tech |
|-------|------|
| UI | Jetpack Compose, Material 3 |
| State | StateFlow, SharedFlow, collectAsStateWithLifecycle |
| ViewModel | Hilt `@HiltViewModel`, viewModelScope |
| DI | Hilt / Dagger |
| Media | Media3 ExoPlayer, MediaSession, MediaSessionService |
| Network | Retrofit, OkHttp, Kotlinx Serialization |
| Database | Room (SQLite), DAOs, Entity mappers |
| Storage | EncryptedSharedPreferences, JSON settings file |
| Async | Kotlin Coroutines, SupervisorJob, Dispatchers |

### Key Design Decisions
- **MediaSession owned by PlaybackManager**, not the Service — keeps PlaybackService thin (~90 lines)
- **MediaController.Builder** to start the service (not `startForegroundService()`) — avoids `ForegroundServiceDidNotStartInTimeException`
- **MediaSession created before `player.prepare()`** — ensures Media3 captures all state transitions for the notification
- **No `fallbackToDestructiveMigration`** — explicit Room migrations required as schema evolves
- **Self-signed cert trust manager** only installed when the user opts in, not unconditionally

---

## Theme

Cosmic dark aesthetic:
- **VoidDeep** `#050810` — background
- **SigilGold** `#C5A55A` — accents
- **Starlight** `#FFFFFF` — text

Optional "Unhinged Mode" with anomaly effects, whisper copy engine, and ritual UI components.

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

27 bugs found and fixed across the playback, sync, download, and UI systems:

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

---

## License

MIT

---

Built by **StaticHum Studio** — [@StaticHumStudio](https://github.com/StaticHumStudio)
