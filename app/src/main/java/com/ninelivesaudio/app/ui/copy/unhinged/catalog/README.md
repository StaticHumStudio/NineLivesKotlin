# Contextual Whispers System

## Overview

**Whispers** are brief, atmospheric text snippets that appear at safe moments. They're rare enough to surprise users and atmospheric enough to enhance the "archive beneath" feeling.

**Tone**: Dungeon Crawler Carl meets John Dies at the End. Odd, not edgy. Atmospheric, not tryhard.

---

## Core Concept

### "The app knows something you don't"

Whispers give the impression that the app/archive is mildly sentient and has its own perspective. But it's not mean or hostile - just slightly off.

**Examples**:
- ✅ "The archive recognizes your footsteps." (Atmospheric, mildly alive)
- ✅ "Still here?" (Dry, patient, not judging)
- ❌ "You're taking too long." (Mean, hostile - don't do this)
- ❌ "The darkness consumes..." (Edgy, tryhard - don't do this)

---

## Components

### 1. WhisperCatalog

Contains all whisper text organized by context.

**Contexts**:
- `APP_OPENED` - When user opens the app
- `PLAYBACK_RESUMED` - When playback starts after pause
- `CHAPTER_FINISHED` - When a chapter completes
- `DOWNLOAD_COMPLETED` - When a download finishes
- `BOOK_FINISHED` - When an entire book completes
- `IDLE` - When user is idle for 60+ seconds

Each context has 5-10 variants so repetition is rare.

---

### 2. WhisperService

Manages display logic, cooldowns, and selection.

**Rules**:
- Max 1 whisper per 15-minute window
- Max 1 whisper per screen/session section
- Deterministic selection (same seed = same sequence)
- Respects reduce motion (whispers are disabled if reduce motion is on)

**Usage**:
```kotlin
val whisperService = WhisperService.instance

// Try to show whisper
whisperService.tryShowWhisper(
    context = WhisperContext.APP_OPENED,
    enabled = unhingedSettings.whispersEnabled,
    reduceMotion = unhingedSettings.reduceMotionRequested
)

// Manually dismiss
whisperService.dismissWhisper()

// Start new session section (screen nav)
whisperService.newSessionSection()
```

---

### 3. WhisperHost

Composable wrapper that displays whispers.

**Usage**:
```kotlin
@Composable
fun MyApp() {
    WhisperHost {
        // Your app content
        NavHost(navController) {
            // Navigation graph
        }
    }
}
```

Whispers appear at the bottom center, above the navigation bar, and auto-dismiss after 4 seconds.

---

## Integration Patterns

### Pattern 1: Screen Entry Whisper

Show a whisper when a screen appears:

```kotlin
@Composable
fun HomeScreen() {
    WhisperOnEnter(WhisperContext.APP_OPENED)

    Column {
        // Screen content
    }
}
```

---

### Pattern 2: Event-Triggered Whisper

Show a whisper when an event occurs:

```kotlin
@Composable
fun PlayerScreen(viewModel: PlayerViewModel) {
    val playbackState by viewModel.playbackState.collectAsState()

    var chapterJustFinished by remember { mutableStateOf(false) }

    LaunchedEffect(playbackState) {
        if (playbackState.chapterFinished) {
            chapterJustFinished = true
        }
    }

    WhisperOnEvent(
        trigger = chapterJustFinished,
        context = WhisperContext.CHAPTER_FINISHED,
        onShown = { chapterJustFinished = false }
    )

    // Player UI
}
```

---

### Pattern 3: Manual Trigger

Trigger whispers programmatically:

```kotlin
@Composable
fun DownloadScreen() {
    val whisperService = remember { WhisperService.instance }
    val unhingedSettings = LocalUnhingedSettings.current

    LaunchedEffect(downloadComplete) {
        if (downloadComplete) {
            whisperService.tryShowWhisper(
                context = WhisperContext.DOWNLOAD_COMPLETED,
                enabled = unhingedSettings.whispersEnabled,
                reduceMotion = unhingedSettings.reduceMotionRequested
            )
        }
    }
}
```

---

## Whisper Examples

### App Opened
> "The archive recognizes your footsteps."
> "Welcome back. The shelves didn't move. Much."
> "You returned. The bookmark held."

### Playback Resumed
> "The telling continues."
> "It was mid-sentence. It waited."
> "Resuming from the last known position. The last *admitted* position."

### Chapter Finished
> "Something finished. Something else began."
> "That chapter is behind you now."
> "The page turned itself."

### Download Completed
> "Retrieved. Stored. Yours now."
> "Download complete. The vault grows."
> "It made it through. Intact, we think."

### Book Finished
> "The telling is complete. The silence that follows is part of it."
> "You reached the end. The archive notes your persistence."
> "Complete. The spine relaxes."

### Idle
> "Still here?"
> "The shelves are quiet tonight."
> "Take your time. The books aren't going anywhere. Probably."

---

## Writing New Whispers

When adding new whispers:

### 1. Pick the Right Context
Where/when should this whisper appear?

### 2. Write in Archive Voice
- Dry observation, not commentary
- Mild unreliability ("probably", "we think", "almost")
- The archive has opinions but isn't pushy
- Never mean, never edgy

### 3. Keep It Short
- 3-15 words maximum
- One sentence or fragment
- Punchy, not rambling

### 4. Test the Tone
Ask yourself:
- ✅ Would this fit in Dungeon Crawler Carl?
- ✅ Is it odd but not trying too hard?
- ✅ Does it enhance atmosphere without being distracting?
- ❌ Is it mean to the user?
- ❌ Is it trying to be horror/creepy?
- ❌ Is it smug or condescending?

---

## Configuration

### Enable/Disable Whispers

```kotlin
// In settings
unhingedSettings.whispersEnabled = true

// Check if should show
val shouldShow = unhingedSettings.shouldShowWhispers
// Returns: whispersEnabled (no other conditions)
```

### Adjust Timing

Modify `WhisperConfig`:

```kotlin
val customConfig = WhisperConfig(
    displayDurationMs = 5000L,  // 5 seconds
    cooldownMs = 10 * 60 * 1000L, // 10 minutes
    blockedStates = setOf(
        BlockedState.ERROR_SCREEN,
        BlockedState.MODAL_DIALOG
    )
)
```

---

## Blocked States

Whispers **NEVER** appear during:

- Error screens
- Modal dialogs
- Car mode (if implemented)
- Settings screens
- Active user interaction

This ensures whispers don't interfere with critical moments.

---

## Accessibility

### Reduce Motion
Whispers are considered motion, so they're automatically disabled when `reduceMotionRequested = true`.

### Screen Readers
Whispers are decorative only and don't convey critical information. They're not announced by screen readers.

---

## Testing

### Manual Testing

1. Enable whispers in settings
2. Navigate through app and trigger contexts
3. Verify whispers appear but don't repeat too often
4. Check reduce motion disables whispers
5. Verify whispers don't block interaction

### Debug Mode

Force a specific whisper:

```kotlin
@Composable
fun DebugWhisperScreen() {
    val whisperService = remember { WhisperService.instance }

    Column {
        Button(onClick = {
            whisperService.tryShowWhisper(
                context = WhisperContext.APP_OPENED,
                enabled = true,
                reduceMotion = false
            )
        }) {
            Text("Show App Opened Whisper")
        }

        WhisperHost {
            // Content
        }
    }
}
```

---

## Performance

### Memory
- Minimal (just string lists)
- No dynamic allocation during display

### UI
- Uses standard Compose animations
- No custom rendering
- Minimal recomposition

### Battery
- Negligible (just text display)
- No continuous animation

---

## Phase 8 Status

✅ **Complete** - Contextual whispers system implemented
- `WhisperCatalog` with 6 contexts, 40+ whispers total
- `WhisperService` with cooldown management
- `WhisperHost` composable display
- Helper composables: `WhisperOnEnter`, `WhisperOnEvent`, `TriggerWhisper`
- Full reduce motion support
- Independent toggle (can disable while keeping unhinged theme)

**Acceptance Criteria Met**:
- ✅ Whispers never interrupt active tasks
- ✅ Users encounter them rarely enough to be surprised
- ✅ Disabling whispers doesn't affect other unhinged features
- ✅ Tone is odd but not edgy, atmospheric but not tryhard

**Next Phase**: Narrator Copy Engine (3-tier system)
