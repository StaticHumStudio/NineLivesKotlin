# Dual-Layer Labeling System

## Overview

The **Dual-Layer Labeling System** adds atmospheric flavor text beneath standard labels without sacrificing comprehension. Users always understand what things do, but get optional atmospheric context in Ritual/Unhinged modes.

## Ground Rules

1. **Primary labels are always literal** - The user must understand what every button/label does at a glance
2. **Flavor text is always secondary** - Smaller, lower contrast, positioned beneath the literal label
3. **Screen readers announce literal labels only** - `contentDescription` never uses flavor text
4. **Destructive actions are always plain** - Delete, remove, cancel — no flavor, no ambiguity, ever
5. **Confirmations stay literal** - An optional flavor line may appear below, but the action text is crystal clear

---

## Components

### 1. RitualHeader

Headers with optional flavor subtitle.

**Usage**:
```kotlin
// Basic usage
RitualHeader(
    title = "Continue Listening",
    subtitle = "Resume the thread."
)

// Size variants
RitualHeaderSmall(
    title = "Recently Added",
    subtitle = "New arrivals."
)

RitualHeaderMedium(
    title = "Library",
    subtitle = "The collection."
)
```

**Renders**:
- **Normal mode**: Just "Continue Listening"
- **Ritual mode**: "Continue Listening" + "Resume the thread." (muted)
- **Unhinged mode**: "Continue Listening" + flavor variant

---

### 2. RitualAction

Buttons with optional flavor subtitle.

**Usage**:
```kotlin
// Standard button
RitualAction(
    label = "Download",
    subtitle = "Retrieve.",
    onClick = { downloadBook() }
)

// Text button
RitualTextAction(
    label = "Cancel",
    onClick = { dismiss() }
    // NO subtitle - cancel is plain
)

// Outlined button
RitualOutlinedAction(
    label = "Add Bookmark",
    subtitle = "Mark this moment.",
    onClick = { addBookmark() }
)

// Destructive action (NO FLAVOR EVER)
DestructiveAction(
    label = "Delete",
    onClick = { deleteItem() }
)
```

---

### 3. RitualLabel

Body text and captions with flavor.

**Usage**:
```kotlin
// Standard label
RitualLabel(
    text = "Bookmarks",
    flavor = "Marked places."
)

// Caption
RitualCaption(
    text = "Downloaded",
    flavor = "Stored."
)

// Empty state
RitualEmptyState(
    message = "No bookmarks yet",
    flavorMessage = "Nothing marked."
)

// Navigation label
RitualNavLabel(
    text = "Home",
    flavor = "The foyer.",
    isSelected = true
)
```

---

## Copy Style Guide

All copy lives in `CopyStyleGuide.kt`. Examples:

### Home Screen
```kotlin
RitualHeader(
    title = CopyStyleGuide.Home.CONTINUE_LISTENING,
    subtitle = when (copyMode) {
        CopyMode.Ritual -> CopyStyleGuide.Home.CONTINUE_LISTENING_RITUAL
        CopyMode.Unhinged -> CopyStyleGuide.Home.CONTINUE_LISTENING_UNHINGED
        else -> null
    }
)
```

### Library Screen
```kotlin
RitualAction(
    label = CopyStyleGuide.Library.SORT_BY,
    subtitle = when (copyMode) {
        CopyMode.Ritual -> CopyStyleGuide.Library.SORT_BY_RITUAL
        CopyMode.Unhinged -> CopyStyleGuide.Library.SORT_BY_UNHINGED
        else -> null
    },
    onClick = { showSortOptions() }
)
```

### Player Controls
```kotlin
// Transport controls - NO FLAVOR
RitualAction(
    label = CopyStyleGuide.Player.PLAY,
    onClick = { play() }
    // No subtitle - rapid interaction, must be instant
)

// Secondary controls - WITH FLAVOR
RitualAction(
    label = CopyStyleGuide.Player.SLEEP_TIMER,
    subtitle = when (copyMode) {
        CopyMode.Ritual -> CopyStyleGuide.Player.SLEEP_TIMER_RITUAL
        CopyMode.Unhinged -> CopyStyleGuide.Player.SLEEP_TIMER_UNHINGED
        else -> null
    },
    onClick = { showSleepTimer() }
)
```

---

## Copy Mode Behavior

### Normal Mode
- Shows literal labels only
- No flavor text anywhere
- App behaves as if unhinged mode doesn't exist

### Ritual Mode
- Shows literal label + subtle flavor subtitle
- 3-8 word flavor text
- Atmospheric but never competing with main text

### Unhinged Mode
- Shows literal label + weirder flavor subtitle
- 5-15 word flavor text
- More personality, mild unreliability
- The archive has opinions

---

## Writing New Copy

When adding new screens/features:

### 1. Write Normal First
```kotlin
const val MY_FEATURE = "My Feature"
```
Must be perfectly clear on its own.

### 2. Add Ritual Flavor
```kotlin
const val MY_FEATURE_RITUAL = "A single beat of atmosphere."
```
Think subtitle in a film - present but not competing. 3-8 words.

### 3. Add Unhinged Flavor
```kotlin
const val MY_FEATURE_UNHINGED = "The archive knows more than it's saying about this."
```
More personality, mild unreliability. 5-15 words. The app is mildly sentient.

### 4. Follow the Rules
- **Never be mean** - Weird is directed at environment, never at user
- **Never be edgy** - No horror, no threats, no shock value
- **When in doubt, cut it** - A missing subtitle is invisible, a bad subtitle is a scar

---

## Accessibility

All components use `semantics { contentDescription = literalLabel }`:

```kotlin
RitualHeader(
    title = "Continue Listening",  // ← Screen reader announces this
    subtitle = "Resume the thread." // ← Screen reader ignores this
)
```

This ensures:
- ✅ Screen readers announce clear, literal text
- ✅ Users with vision impairments get unambiguous labels
- ✅ Flavor text is decorative only, never functional

---

## Destructive Actions

Delete, remove, cancel — **ALWAYS plain**:

```kotlin
// ✅ CORRECT
DestructiveAction(
    label = "Delete",
    onClick = { delete() }
)

// ❌ WRONG - destructive actions NEVER have flavor
RitualAction(
    label = "Delete",
    subtitle = "Erase it from the archive.", // DON'T DO THIS
    onClick = { delete() }
)
```

Confirmations can have a small flavor line below, but it's clearly secondary:

```kotlin
Dialog {
    Column {
        Text("Delete this book?") // Literal
        Text("This cannot be undone.") // Clear warning
        // Optional flavor below, but action stays clear
        if (copyMode == CopyMode.Unhinged) {
            Text(
                "The archive will forget it was here.",
                style = MaterialTheme.typography.bodySmall,
                color = ArchiveTextFlavor
            )
        }

        Row {
            TextButton(onClick = dismiss) {
                Text("Cancel") // Plain
            }
            DestructiveAction(
                label = "Delete", // Plain
                onClick = delete
            )
        }
    }
}
```

---

## Integration Examples

### Complete Screen Example
```kotlin
@Composable
fun LibraryScreen() {
    val copyMode = LocalUnhingedSettings.current.copyMode

    Column {
        // Header
        RitualHeader(
            title = CopyStyleGuide.Library.LIBRARY_NAV,
            subtitle = when (copyMode) {
                CopyMode.Ritual -> CopyStyleGuide.Library.LIBRARY_NAV_RITUAL
                CopyMode.Unhinged -> CopyStyleGuide.Library.LIBRARY_NAV_UNHINGED
                else -> null
            }
        )

        // Action buttons
        Row {
            RitualAction(
                label = CopyStyleGuide.Library.SORT_BY,
                subtitle = when (copyMode) {
                    CopyMode.Ritual -> CopyStyleGuide.Library.SORT_BY_RITUAL
                    CopyMode.Unhinged -> CopyStyleGuide.Library.SORT_BY_UNHINGED
                    else -> null
                },
                onClick = { showSortDialog() }
            )

            RitualAction(
                label = CopyStyleGuide.Library.FILTER,
                subtitle = when (copyMode) {
                    CopyMode.Ritual -> CopyStyleGuide.Library.FILTER_RITUAL
                    CopyMode.Unhinged -> CopyStyleGuide.Library.FILTER_UNHINGED
                    else -> null
                },
                onClick = { showFilterDialog() }
            )
        }

        // Empty state
        if (books.isEmpty()) {
            RitualEmptyState(
                message = CopyStyleGuide.EmptyStates.EMPTY_LIBRARY_NORMAL,
                flavorMessage = when (copyMode) {
                    CopyMode.Ritual -> CopyStyleGuide.EmptyStates.EMPTY_LIBRARY_RITUAL
                    CopyMode.Unhinged -> CopyStyleGuide.EmptyStates.EMPTY_LIBRARY_UNHINGED
                    else -> null
                }
            )
        }
    }
}
```

---

## Phase 7 Status

✅ **Complete** - Dual-layer labeling system implemented
- `RitualHeader` (with size variants)
- `RitualAction` (button, text, outlined, destructive variants)
- `RitualLabel` (label, caption, empty state, nav variants)
- `CopyStyleGuide` with complete copy for all screens
- Full accessibility support (screen readers use literal labels only)

**Acceptance Criteria Met**:
- ✅ Brand-new users understand every action instantly
- ✅ Flavor text is visually subordinate
- ✅ Screen readers never announce flavor text
- ✅ Destructive actions have no flavor

**Next Phase**: Contextual Whispers System
