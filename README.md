# Nine Lives Audio

A feature-rich Android audiobook player for audiobooks on your own device and on your own [Audiobookshelf](https://www.audiobookshelf.org/) server. Built with Kotlin, Jetpack Compose, and Media3. Wrapped in **The Archive Beneath**, a deep indigo cosmic vault aesthetic with gold filament accents, fluorescent progress rings, stone surfaces, vintage book placeholders, and atmospheric anomalies that manifest when the Archive grows restless.

There is no "normal mode." The vault is the app. The app is the vault.

**v2.1.0 | Android 11+ (API 30) | Kotlin 2.2.10 | Jetpack Compose | Material 3**

Free, with a single one-time in-app unlock. The free tier plays everything, with
no time limit and no ads: your local folders, your Audiobookshelf server,
chapters, bookmarks, and a sleep timer. The unlock opens every playback speed,
unlimited offline books, full sleep-timer control, silence skipping, the
equalizer, every theme, and the Nightwatch Dossier. Anyone who bought the app
while it was paid keeps everything, permanently.

---

## Features

### Playback

- Background audio via Media3 ExoPlayer foreground service. Keeps playing with the screen off, as all good hauntings should.
- Media notification with play/pause, skip forward/back, seekable progress bar, and cover art
- Lock screen controls via MediaSession
- Android Auto support with full media browsing, search, and chapter skip controls
- Audio focus handling. Automatically pauses for phone calls and other media (the Archive yields to no one except telephony).
- Multi-track playlist support via ExoPlayer concatenation
- Playback speed from 0.5x to 3.0x with real-time control
- Volume boost slider (0 to 10 dB) for quiet audiobooks that whisper too softly
- 5-band equalizer (60 Hz to 14 kHz) with per-band gain and preset reset
- Chapter navigation... skip to previous/next chapter, chapter-level seek bar with markers, works even on finished books
- Fluorescent square progress. Rounded-square neon glow around book covers with 4-layer animation (outer bleed, mid corona, core tube, hot filament) and a continuous breathing effect.
- Mini player bar persistent across all screens with quick controls
- Finished book recovery. Replaying or navigating chapters on completed books works seamlessly (STATE_ENDED recovery).

### Sleep Timer

- Configurable countdown timer with on-screen display
- Motion-sensing grace window. When the timer expires, a 60-second grace period checks the accelerometer to see if you're still awake. Detects motion and extends by 2 minutes.
- Shake-to-reset. Shake your device to reset the timer to its original duration.
- Auto-rewind on expiration (0 to 60 seconds configurable) so you don't lose your place
- All sleep timer settings individually toggleable in Settings

### Nine Lives Home

- 3x3 vault wall grid of your most recently played books (up to 9 "lives")
- Fluorescent progress glow per tile. 4-layer neon-tube animation with continuous breathing effect.
- Life Index badges (LIFE I through LIFE IX) based on cumulative listening hours
- Weight indicators (LIGHT, MEDIUM, HEAVY) showing how much time you've invested
- Corner sigils. Gold dot for downloaded books, purple dot for bookmarked.
- One deterministic misaligned tile for Archive aesthetic (it knows what it did)
- Last-played timestamps, total listening time per book
- Fast initial sync (500 ms delay, progress-first loading)
- Library-aware... scoped to the currently selected library

### The Archive (Library)

- Compact list view with 72 dp cover thumbnails and detailed book info
- Vintage book placeholders. Procedurally drawn worn leather book covers via Canvas when no cover art is available (or fails to load). Each book gets a unique visual variation from its ID seed... leather grain, spine shadow, gold decorative lines, serif title text, corner wear, aging vignette, and scuff marks. They look like they've been sitting in a forgotten wing of the Archive for decades.
- 11 sorting options: Recently Added, Title A-Z / Z-A, Author A-Z / Z-A, Progress High / Low, Duration Long / Short, Recently Played, Unplayed First
- Stone tab system: All / In Progress / Completed / Downloaded
- Grouping modes: flat list, by Series, by Author, by Genre (with expandable/collapsible group headers)
- Search by title, author, narrator, or series via relic search bar with gold focus glow
- Book whispers. Atmospheric commentary on ~50% of books, re-rolled each time you enter the Library. Deterministically assigned from 20+ time-aware and progress-aware variations.
- Fluorescent square glow progress indicators on cover thumbnails with corner sigils
- Server sync with local Room SQLite caching
- Auto-switches to downloaded-only mode when offline
- Pull-to-refresh
- Tightened spacing for maximum density without claustrophobia

### Book Detail

- Full book info: cover art (or vintage placeholder), title, author, narrator, series, description, genre tags, duration
- Overall progress percentage and chapters completed
- Play/Resume, Download, and Share actions
- Listening history. Expandable session list showing date, time, and duration per session.

### Bookmarks

- Create bookmarks at any position with custom titles
- Jump to any bookmark instantly
- Formatted timestamps (HH:MM:SS)
- Server-synced via Audiobookshelf

### Downloads

- Concurrent download queue (2 simultaneous downloads)
- Pause, resume, and cancel controls per download
- Retry with exponential backoff. Per-file retry, never skips on failure.
- Progress tracking with throttled UI updates
- Full offline playback from locally stored files
- Auto-cover download option
- Proper ResponseBody lifecycle management to prevent connection pool exhaustion

### Progress Sync

- Bidirectional sync with Audiobookshelf server
- Throttled position reporting during active playback
- Offline queue. Progress updates get queued locally and flush when connectivity returns.
- Race-safe flush. Only deletes synced entries, preserves in-flight writes.
- Local progress always preserved during server sync
- Playback session tracking for Audiobookshelf listening statistics

### Nightwatch Dossier (Listening Analytics)

A full listening analytics dashboard with shareable summary cards. The Nightwatch sees all.

- 6 time periods: 30 Days, 3 Months, 6 Months, 1 Year, This Year, Last Year
- Library-scoped. Filters all stats to the currently selected library.
- Overview stats: total listening time, session count, unique books, books finished, daily average, best listening day
- Per-book breakdown: listening time, sessions, progress, chapter info, completion status
- Narrator analysis: top narrators ranked by listening time with book counts
- Author analysis: top authors ranked by listening time
- Genre breakdown: listening time and book count per genre
- Temporal patterns: hourly listening distribution, peak hour, peak day of week
- Contextual whispers that adapt to your listening habits
- Session sanitization: caps duration at wall-clock time, 4-hour hard cap on unverified sessions, filters noise sessions under 60 seconds
- Shareable summary card. Capture and share your listening stats via PixelCopy.

### Settings

- Server connection: URL, username/password or API token authentication, connect/disconnect/test/refresh
- API token login. Paste your Audiobookshelf API token directly, bypassing username/password.
- Multi-library support. Library selector dropdown appears when connected to a server with multiple libraries. Persisted selection propagates across Home, Library, and Dossier.
- Self-signed certificate support (opt-in only, not applied globally)
- Auto sync with configurable interval (5 to 60 minutes)
- Auto-rewind on resume. Smart or Flat mode, configurable from 0 to 120 seconds.
- Sleep timer configuration: motion sensing, shake-to-reset, and rewind on expiration toggles
- 5-band equalizer with volume boost
- Theme controls. Toggle anomalies, whispers, and reduced motion independently.
- Targeted cache clearing. Clears library data only, never touches progress or downloads.
- Diagnostics display: app version, device model, Android version, API level, build type
- Privacy policy link

### Feedback and Bug Reports

- In-app bug reports and upgrade requests with automatic device diagnostics
- Optional logcat inclusion (last 500 lines)
- Launches email intent pre-filled with device info, app version, and build details

### Android Auto

- Full media browsing tree: Recently Played and Libraries at the root, drill into any library or book
- Search by title, author, narrator, or genre
- Playback controls: play/pause, skip forward/back, seek
- Chapter skip buttons for navigating between chapters hands-free
- Cover art, title, and author displayed on car screen
- Notification with full playback controls

### Offline Mode

- Automatic network detection. Monitors connectivity state in real-time.
- Library auto-filters to downloaded books when the server is unreachable
- Offline progress queue syncs automatically when connection returns
- Periodic server reachability checks (60-second interval)
- Connection pool cleanup and stale TCP eviction on foreground recovery

### Security and Privacy

- AES-256-GCM encrypted storage for server credentials and auth tokens via EncryptedSharedPreferences
- Unique device ID. Randomly generated UUID per device, never based on hardware identifiers.
- Self-signed certificate trust only when explicitly enabled by the user
- ACRA crash reporting. Optional crash dialog with user comments, safe field collection (no auth tokens or preferences), delivered via email.
- No analytics or tracking. Your listening data stays between you and your Audiobookshelf server, and local libraries never leave the device at all. The Archive keeps its own counsel.
- No ads, in either tier. The in-app unlock is a one-time purchase, never a subscription.
- No AI features. No chatbot, no summarizer, and nothing that ships your listening anywhere to train one.
- Purchases are handled entirely by Google Play Billing. The app never sees or stores payment details.

---

## Theme: The Archive Beneath

There is no light mode. There never was. The Archive exists in deep indigo void, illuminated only by gold filament and the occasional verdigris flicker of the Impossible Accent. Every spine twitches if you stare long enough.

### Palette

| Token | Hex | Role |
|---|---|---|
| `ArchiveVoidDeep` | `#0A0520` | Deepest background (the indigo void) |
| `ArchiveVoidBase` | `#110A28` | Base surface (indigo obsidian) |
| `ArchiveVoidSurface` | `#1A1135` | Cards and panels |
| `ArchiveVoidElevated` | `#251945` | Modals and overlays |
| `GoldFilament` | `#E8C468` | Primary accent. Warm metallic gold for all interactive elements. |
| `GoldFilamentBright` | `#F5D97A` | Hover states and emphasis |
| `ImpossibleAccent` | `#3DBFA0` | Muted verdigris. Focus borders, selection highlights only. Never more than 5% of screen. |
| `ArchiveTextPrimary` | `#F5F5F7` | Primary text (high contrast) |
| `ArchiveTextMuted` | `#707080` | Placeholders and hints |
| `ArchiveOutline` | `#242933` | Borders and dividers |
| `VintageLeather` | `#3B2415` | Placeholder book covers (aged leather) |
| `VintageTextGold` | `#C4A96A` | Placeholder book title text (faded gold-cream) |

All colors tuned for WCAG AA contrast (4.5:1 body text, 3:1 large text).

### Visual System

- **ContainmentFrame** ... dual-stroke gold border around tiles
- **FluorescentSquareProgress** ... rounded-square neon-tube progress with 4-layer glow and breathing animation
- **SigilProgressBar** ... linear progress with shimmer clipped to fill
- **StoneSlabCard / RelicSurface** ... stone-textured surface components
- **FilamentDivider** ... gold-accented section dividers
- **VintageBookPlaceholder** ... procedural Canvas composable drawing a worn leather book cover with 10 layers: leather gradient, spine line, grain texture, border frame, decorative gold lines, title text, corner wear, aging vignette, and scuff marks. Seed-based deterministic variation per book.
- **BookCoverImage** ... centralized cover-or-placeholder wrapper using SubcomposeAsyncImage with loading, error, and success states. Broken server URLs show the vintage placeholder instead of blank rectangles.
- **AnomalyHost** ... overlay system for visual anomalies (ink bleed, tear veil, crack whisper) on cooldown schedules
- **CopyEngine** ... 3-tier copy system (Normal / Ritual / Unhinged) for screen titles and flavor text
- **WhisperService** ... atmospheric text overlays triggered by screen entry and user actions
- **BookWhisperCatalog** ... time-aware and progress-aware atmospheric phrases for library items, re-rolled on each Library visit

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
| Image Loading | Coil 2.7.0, SubcomposeAsyncImage with fallback states |
| Crypto | EncryptedSharedPreferences (AES-256-GCM) |
| Async | Kotlin Coroutines 1.10.2, SupervisorJob, Dispatchers |
| Build | AGP 9.0.0, KSP 2.2.10, Compose BOM 2025.06.00 |

---

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).

The Google Play build links against the proprietary Play Billing and Play
In-App Review libraries for the one-time unlock and the ratings prompt. The
[LICENSE](LICENSE) file opens with an additional permission under GPLv3
section 7 covering exactly those two libraries, so the Play build (and forks
of the material this permission covers) can be conveyed under the GPL
without ambiguity. Nothing else is affected, and any recipient may drop the
permission from their own copy.

Third-party dependency licenses are listed in [THIRD_PARTY_LICENSES](THIRD_PARTY_LICENSES).

---

## Legal

Nine Lives Audio is a trademark of Static Hum Studio. See [TRADEMARK.md](./TRADEMARK.md)
for the full notice.

What that reserves is the NAMES. This repository is GPLv3, so a fork may take the
source and ship it ... it just cannot ship under the Nine Lives Audio or Static Hum
Studio names, or anything confusingly similar. Trademark is a separate right the
GPL never granted, which is why the name restriction holds. Nothing here retracts
the GPL grant over anything already published under it.

© Static Hum Studio — [statichum.studio](https://statichum.studio)
