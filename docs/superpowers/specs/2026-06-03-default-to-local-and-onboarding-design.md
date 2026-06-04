# Default to Local + First-Run Onboarding

Date: 2026-06-03
Status: Approved design, ready for implementation plan
Branch: `feat/default-to-local-onboarding`

## Goal

Make Local the primary experience for Nine Lives. Local audio targets a broader
audience than self-hosted Audiobookshelf, so the app should default to Local and
guide a brand-new user into either loading local books or connecting a server,
instead of dropping them on an empty Home with no direction.

## Summary of changes

1. Flip the default `appMode` from `AUDIOBOOKSHELF` to `LOCAL`.
2. Add a one-time first-run **Welcome** screen that lets the user pick Local or
   Server. The choice sets the mode and routes them into the existing Settings
   screen (no new connect screens or menus).
3. Add a collapsible "How it works" guide to the Local Folders area in Settings
   that explains how to arrange audiobooks so the scanner picks them up.

There are effectively no existing users to migrate (single-developer app), so no
config-detection migration is built. The new onboarding flag simply defaults to
`false`; the developer may see Welcome once on an existing install, which is
acceptable.

## Current behavior (baseline)

- `AppMode` enum: `AUDIOBOOKSHELF`, `LOCAL`
  (`app/src/main/java/com/ninelivesaudio/app/domain/model/AppMode.kt`).
- `AppSettings.appMode` defaults to `AUDIOBOOKSHELF`
  (`.../domain/model/AppSettings.kt:7`).
- Settings persist via `SettingsManager` to EncryptedSharedPreferences as JSON
  (`.../service/SettingsManager.kt`). Loaded in `NineLivesApp.onCreate()` before
  any UI renders (`.../NineLivesApp.kt`).
- `NineLivesNavHost` has a hardcoded `startDestination = Routes.HOME`; no mode
  branching at startup (`.../ui/navigation/NineLivesNavHost.kt:44`).
- No onboarding / first-run flow exists today.
- Mode is switched at runtime via the "Source" toggle in Settings, handled by
  `SettingsViewModel.switchMode()` (`.../ui/settings/SettingsViewModel.kt:280`),
  which stops playback, updates mode, and selects the right library for the mode.
- ABS connection UI (server URL, token/username/password, Connect) lives only in
  the Settings "Connection" group, shown when `appMode == AUDIOBOOKSHELF`
  (`.../ui/settings/SettingsScreen.kt:161+`). Local Folders group shows when
  `appMode == LOCAL` (`.../ui/settings/SettingsScreen.kt:139+`).

## Scanner rules the guide must reflect

From `.../service/local/LocalLibraryScanner.kt`. The scanner reads the chosen
SAF tree **one level deep**:

- Each immediate **subfolder** containing audio = one book. Title comes from the
  album tag, else the folder name. Author from album-artist/artist tag, else
  "Unknown Author". Tracks ordered by track-number tag, else natural filename
  order (`2.mp3` before `10.mp3`). Cover from `cover.jpg/.jpeg/.png` or
  `folder.jpg/.jpeg/.png` in the folder, else embedded artwork.
- Each immediate **loose audio file** at the root = one single-file book.
- Does **not** recurse below one level (nested book folders are not discovered).
- Dotfiles skipped.
- Supported extensions: `mp3, m4a, m4b, opus, ogg, flac, aac, wma, wav`.

## Detailed design

### 1. Settings model

`AppSettings` (`.../domain/model/AppSettings.kt`):

- `appMode` default → `AppMode.LOCAL`.
- New field `onboardingComplete: Boolean = false`.

Serialization goes through the existing `SettingsManager` JSON path. Old payloads
without the new field deserialize to `false` (default), so no manual migration is
required. This is the only persisted state added.

### 2. Welcome screen

New `ui/onboarding/WelcomeScreen.kt` (stateless composable; wiring via callbacks,
no dedicated ViewModel required).

- Content: app title / short tagline, then two **equal-weight** cards:
  - **Local** — folder icon (match the icon used in the existing
    `SourceModeToggle`).
  - **Server** — server/Dns icon (match `SourceModeToggle`).
- Cards are clean: label + icon only. No subtitle/hint copy.
- Reuses existing Cosmic/theme components for visual consistency.
- Exposes `onChooseLocal()` and `onChooseServer()` callbacks.

### 3. Navigation

`NineLivesNavHost` (`.../ui/navigation/NineLivesNavHost.kt`):

- Add `Routes.WELCOME = "welcome"`.
- `startDestination` becomes conditional on the loaded settings:
  `if (!settings.onboardingComplete) Routes.WELCOME else Routes.HOME`.
  Settings load asynchronously (`loadSettings()` runs off the main thread), so
  `MainActivity` must wait for a "settings loaded" signal before choosing the
  start destination, otherwise a returning user could briefly route to Welcome.
  Add a `SettingsManager.isLoaded` StateFlow (set true when `loadSettings()`
  finishes) and render only the background until it is true.
- Add `composable(Routes.WELCOME)` wiring:
  - `onChooseLocal`: persist `appMode = LOCAL` + `onboardingComplete = true`,
    then `navigate(Routes.SETTINGS)` with `popUpTo(Routes.WELCOME) { inclusive = true }`.
  - `onChooseServer`: persist `appMode = AUDIOBOOKSHELF` + `onboardingComplete = true`,
    then `navigate(Routes.SETTINGS)` with `popUpTo(Routes.WELCOME) { inclusive = true }`.

### 4. Settings write path for the onboarding choice

The onboarding choice must persist the mode through the **same path** the in-app
Source toggle uses, so library-selection side effects stay consistent. Reuse
`SettingsViewModel.switchMode()` (or the underlying `settingsManager.updateSettings`
that it relies on) rather than writing `appMode` directly, and set
`onboardingComplete = true` in the same update. Avoid divergence between the two
entry points.

**Guard caveat:** `switchMode()` early-returns when the chosen mode equals the
current mode (`.../ui/settings/SettingsViewModel.kt:281`). Because the default is
now `LOCAL`, tapping **Local** on Welcome would match the current mode and the
early-return would skip persisting `onboardingComplete`. The onboarding path must
therefore persist `onboardingComplete = true` unconditionally (e.g. via a direct
`settingsManager.updateSettings`), independent of whether the mode actually
changed. Do not rely on `switchMode()` alone for the Local choice.

### 5. Local-loading guide (Settings → Local Folders)

A **collapsible** "How it works" section rendered at the top of the Local Folders
group in `SettingsScreen.kt` (above "Add folder").

- Default expanded when no local libraries exist yet; collapsed once at least one
  local library is present. User can toggle within the screen.
- Default-expanded is derived from existing local-library state (e.g. empty list),
  so no new persisted flag is needed for the collapse state.
- Copy:
  > **Bringing in your books**
  > Point Nine Lives at one folder that holds your audiobooks. Two ways to arrange
  > what's inside:
  > - **A folder per book** — each book gets its own folder of audio files. The
  >   folder name becomes the title. For multi-part books, tag the tracks or name
  >   them `01`, `02`… so they play in order. Drop a `cover.jpg` in for art.
  > - **Single files** — a lone `.m4b` or `.mp3` sitting loose in that folder
  >   becomes its own book.
  > Only the top folder is read, one level down. Supported: m4b, m4a, mp3, opus,
  > ogg, flac, aac, wma, wav.

## Testing

- `AppSettings` default is `appMode == LOCAL` and `onboardingComplete == false`.
- Deserializing an older settings JSON that lacks `onboardingComplete` yields
  `false`.
- Onboarding callbacks persist the correct `appMode` and set
  `onboardingComplete = true` (via the shared `switchMode`/update path).
- Start-destination selection returns `WELCOME` when `onboardingComplete == false`
  and `HOME` when `true`.
- Local guide defaults to expanded when no local libraries exist and collapsed
  otherwise.
- Welcome composable render/click test if the project has Compose UI tests;
  otherwise cover at the callback/ViewModel level.

## Out of scope

- No dedicated server-connect or login screen (Server path reuses existing
  Settings Connection group).
- No config-detection migration for existing installs.
- No changes to the scanner itself or supported formats.
- No store-listing / marketing copy changes.
