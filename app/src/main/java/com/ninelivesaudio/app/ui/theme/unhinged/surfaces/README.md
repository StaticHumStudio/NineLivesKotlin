# Surface Language Composables

## Overview

This directory contains the **Surface Language** system for Unhinged Mode - reusable composables that adapt their aesthetic based on the active theme while maintaining identical layout.

## The Three Surfaces

### 1. StoneSlabCard

**Purpose**: List items, book cards, "Continue Listening" tiles

**Normal Mode**: Standard Material 3 card with elevation/shadow

**Unhinged Mode**: "Slab" aesthetic
- Low elevation (1dp) - slabs sit close to the void
- Matte obsidian surface (`StoneSlab` color)
- Subtle hairline border
- Calm, utilitarian, low visual noise

**Usage**:
```kotlin
StoneSlabCard(onClick = { navigateToBook(book) }) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(book.title, style = MaterialTheme.typography.titleMedium)
        Text(book.author, style = MaterialTheme.typography.bodyMedium)
    }
}

// Or use the padded convenience variant
StoneSlabCardPadded(onClick = { /*...*/ }, padding = 16.dp) {
    Text("Content here")
}
```

---

### 2. RelicSurface

**Purpose**: Primary content areas, feature panels, important sections

**Normal Mode**: Standard surface container with subtle elevation

**Unhinged Mode**: "Relic" aesthetic
- Higher elevation (6dp) - relics are special
- Aged stone surface (`StoneRelic` color)
- Top rim highlight (subtle gold filament glow)
- Faint inner shadow effect
- Feels old and important, never busy

**Usage**:
```kotlin
RelicSurface {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("Important Content", style = MaterialTheme.typography.headlineMedium)
        // Feature content here
    }
}

// Or use the padded convenience variant
RelicSurfacePadded {
    Text("Automatically padded content")
}
```

---

### 3. FilamentDivider

**Purpose**: Horizontal dividers, section breaks

**Normal Mode**: Standard Material 3 divider line

**Unhinged Mode**: "Filament" aesthetic
- Hairline ash or gold line
- Optional faint glow effect (barely perceptible)
- Subtle, never attention-grabbing

**Usage**:
```kotlin
// Standard ash divider (most common)
FilamentDivider()

// With subtle glow
FilamentDivider(withGlow = true)

// Gold accent divider for important section breaks
GoldFilamentDivider()

// Simple ash divider (convenience variant)
AshDivider()

// Custom styling
FilamentDivider(
    thickness = 2.dp,
    color = CustomColor,
    withGlow = true
)
```

---

## Design Philosophy

### Slab Surfaces
- **Calm**: Low visual noise, no unnecessary decoration
- **Utilitarian**: Function over form, but still atmospheric
- **Grounded**: Low elevation, close to the void background

### Relic Surfaces
- **Important**: Higher elevation, subtle rim lighting
- **Aged**: Feels like it's been there a while
- **Never busy**: Atmosphere through subtlety, not complexity

### Void (Backgrounds)
- **Deep**: Obsidian/indigo, not pure black
- **The stage**: Just the background, never interactive
- **Atmospheric**: Sets the mood without competing for attention

---

## Layout Guarantee

All surfaces maintain **identical layout and spacing** in both themes. The only differences are visual styling:

- ✅ Colors
- ✅ Elevation values
- ✅ Border styles
- ✅ Subtle effects (glows, gradients)

- ❌ No padding changes
- ❌ No size changes
- ❌ No layout shifts

This ensures theme switching never breaks layouts or causes visual jumps.

---

## Integration Guide

### Replacing Existing Cards

**Before** (standard Material card):
```kotlin
Card(onClick = { /*...*/ }) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Book Title")
    }
}
```

**After** (theme-aware slab card):
```kotlin
StoneSlabCard(onClick = { /*...*/ }) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Book Title")
    }
}
```

### Replacing Surface Containers

**Before**:
```kotlin
Surface(
    modifier = Modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surfaceContainer
) {
    // content
}
```

**After**:
```kotlin
RelicSurface(modifier = Modifier.fillMaxWidth()) {
    // content
}
```

### Replacing Dividers

**Before**:
```kotlin
HorizontalDivider()
```

**After**:
```kotlin
FilamentDivider()
// or
AshDivider()
```

---

## Testing

All surfaces automatically respond to `LocalUnhingedSettings.current.isUnhingedThemeActive`.

To test both modes:
1. Toggle `unhingedSettings.unhingedThemeEnabled = true` in settings
2. Navigate through screens using these surfaces
3. Verify layout remains identical in both modes
4. Check that visual transitions are smooth

---

## Phase 2 Status

✅ **Complete** - All three surface composables implemented and documented
- StoneSlabCard (with padded variant)
- RelicSurface (with padded variant)
- FilamentDivider (with GoldFilamentDivider and AshDivider variants)
- Full documentation and usage examples
- Layout parity guaranteed between themes

**Next Phase**: Impossible Accent Discipline (focus/selection states)
