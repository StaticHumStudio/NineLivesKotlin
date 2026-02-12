# Impossible Accent System

## Overview

The **Impossible Accent** is the "weird" color channel in Unhinged Mode - a muted verdigris (`#3DBFA0`) used **exclusively** for focus and selection states. This is what makes the UI feel slightly off while remaining functional and accessible.

## The Rule

**Gold is the primary UI language. Impossible accent is the exception.**

- **Gold** (`GoldFilament`) → Buttons, icons, CTAs, primary accents
- **Impossible Accent** (`ImpossibleAccent`) → Focus rings, selection indicators, active states

---

## Approved Use Cases

### 1. Focus Indication (Keyboard Navigation)

Use `Modifier.archiveFocusIndication()` to show the impossible accent when an element is focused:

```kotlin
Button(
    onClick = { },
    modifier = Modifier.archiveFocusIndication()
) {
    Text("Focusable Button")
}
```

**Result**:
- Normal mode: Gold focus ring
- Unhinged mode: Impossible accent focus ring (thin 2dp border)

---

### 2. Selection Edge Highlight (List Items)

Use `Modifier.selectionEdgeHighlight()` for selected list items:

```kotlin
StoneSlabCard(
    onClick = { select() },
    modifier = Modifier
        .fillMaxWidth()
        .selectionEdgeHighlight(isSelected = isSelected)
) {
    Text("List Item")
}
```

**Result**:
- Small 3dp edge highlight on the left side
- Uses impossible accent in unhinged mode, gold in normal mode
- Never a full background fill

---

### 3. Active Tab/Nav Indicator

Use `rememberActiveIndicatorColor()` for tab indicators:

```kotlin
TabRow(
    selectedTabIndex = selectedTab,
    indicator = { tabPositions ->
        Box(
            modifier = Modifier
                .tabIndicatorOffset(tabPositions[selectedTab])
                .height(3.dp)
                .background(rememberActiveIndicatorColor(isActive = true))
        )
    }
) {
    // tabs here
}
```

**Result**:
- Thin underline using impossible accent when active
- Combined with gold tab content

---

### 4. Progress Accent Overlay (Rare)

Use `rememberProgressAccentColor()` for subtle progress overlays:

```kotlin
// Only for special progress indicators where extra visual interest is needed
val accentColor = rememberProgressAccentColor(
    progress = currentProgress,
    showAccent = true
)

if (accentColor != null) {
    // Layer this OVER the gold progress, don't replace it
    Box(
        modifier = Modifier
            .fillMaxWidth(currentProgress)
            .height(2.dp)
            .background(accentColor)
    )
}
```

**Result**:
- Very subtle (20% alpha) overlay on top of gold progress
- Enhances the "alive" feeling without being overwhelming

---

## Forbidden Use Cases

### ❌ NEVER for Text Color

```kotlin
// ❌ WRONG
Text("Content", color = ImpossibleAccent)

// ✅ CORRECT
Text("Content", color = MaterialTheme.colorScheme.onSurface)
```

The impossible accent doesn't have sufficient contrast for text. Always use standard text colors.

---

### ❌ NEVER for Full Backgrounds

```kotlin
// ❌ WRONG
Box(
    modifier = Modifier
        .fillMaxWidth()
        .background(ImpossibleAccent)
)

// ✅ CORRECT
Box(
    modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surface)
)
```

The impossible accent should be **rare**. Full backgrounds would make it overwhelming.

---

### ❌ NEVER for More Than ~5% Screen Area

If the impossible accent covers more than ~5% of the visible screen, you're using too much. It should feel like a surprise, not a dominant color.

---

### ❌ NEVER for Primary Actions

```kotlin
// ❌ WRONG - primary buttons should use gold
Button(
    onClick = { },
    colors = ButtonDefaults.buttonColors(
        containerColor = ImpossibleAccent
    )
)

// ✅ CORRECT - primary buttons use gold
Button(
    onClick = { },
    colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary // Gold
    )
)
```

Buttons and CTAs should use gold. The impossible accent is for **selection and focus**, not primary actions.

---

## Design Philosophy

### "Did that just happen?"

The impossible accent should be rare enough that users consciously notice it. When you see that verdigris highlight, it should feel deliberate and slightly unexpected.

### Scalpel, Not Paintbrush

Use the impossible accent surgically:
- Thin borders (1-3dp)
- Small highlights
- Subtle overlays (low alpha)

Never use it for large surfaces or as a dominant color.

### Enhances Gold, Doesn't Compete

Gold is warm, inviting, primary. Impossible accent is cool, subtle, secondary. They should work together:
- Gold says "click here"
- Impossible accent says "you are here"

---

## Code Review Checklist

When reviewing code that uses `ImpossibleAccent`:

- [ ] Used only for focus/selection/active states
- [ ] Never used as text color
- [ ] Never used as a full background fill
- [ ] Covers less than ~5% of screen area
- [ ] Gold remains the dominant accent color
- [ ] Usage matches one of the approved patterns above

If any item is unchecked, reject the PR and request changes.

---

## Phase 3 Status

✅ **Complete** - Impossible accent system fully implemented
- `AccentRules.md` - Comprehensive usage guidelines
- `FocusIndication.kt` - Modifiers and composable helpers
- `AccentExamples.kt` - Correct usage examples and anti-patterns
- `README.md` - Integration guide and philosophy

**Acceptance Criteria Met**:
- ✅ Focus/selection states faster to identify than before
- ✅ Accent feels like deliberate design choice, not error
- ✅ Screenshots still look premium
- ✅ Gold remains primary UI language

**Next Phase**: Micro-Interaction Library (safe motion)
