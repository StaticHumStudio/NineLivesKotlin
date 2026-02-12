# Unhinged Mode Theme System

## Overview

This directory contains the **Archive Beneath** theme for Unhinged Mode. The theme system allows users to toggle between the standard Cosmic Dark theme and the atmospheric Archive Beneath aesthetic.

## Architecture

### Theme Switching

The main `NineLivesAudioTheme` composable now accepts an optional `UnhingedSettings` parameter:

```kotlin
@Composable
fun NineLivesAudioTheme(
    unhingedSettings: UnhingedSettings? = null,
    content: @Composable () -> Unit
)
```

- When `unhingedSettings.unhingedThemeEnabled == true` → Uses `UnhingedTheme` (Archive Beneath)
- Otherwise → Uses the standard `CosmicDarkColorScheme`

### Key Files

- **UnhingedColors.kt** - Complete color palette for Archive Beneath theme
- **UnhingedTheme.kt** - Theme composable and Material 3 color scheme
- **UnhingedSettings.kt** - Master configuration for all unhinged features

## Color Palette

### Archive Beneath Philosophy

- **Deep, not black**: Obsidian/indigo backgrounds (#050810) instead of pure black
- **Gold filament**: Warm metallic accent (#C5A55A) for primary interactive elements
- **Impossible accent**: Verdigris (#3DBFA0) for focus/selection ONLY - never for text or large areas
- **Text tuned for dark**: High contrast whites (#F5F5F7) passing WCAG AA standards

### Usage Guidelines

#### Gold Filament (Primary Accent)
✅ DO use for:
- Buttons and interactive elements
- Selected navigation items
- Progress indicators
- Important CTAs

❌ DON'T use for:
- Body text
- Large background areas

#### Impossible Accent (Focus/Selection)
✅ DO use for:
- Focus visual borders (thin, 1-2dp)
- Selected list item edge highlights (2-3dp left border)
- Progress bar accent line (layered over gold)

❌ DON'T use for:
- Body text foreground
- Full background fills
- More than ~5% of visible screen area
- Any element larger than a thin outline

### Contrast Compliance

All text colors meet WCAG AA standards:
- **Body text** (ArchiveTextPrimary on ArchiveVoidDeep): 4.5:1 minimum
- **Large text** (headings, buttons): 3:1 minimum
- **Flavor text** (ArchiveTextFlavor): Intentionally lower contrast for subtlety, but still readable

## Using the Theme

### In Composables

Access the current theme settings via `LocalUnhingedSettings`:

```kotlin
@Composable
fun MyComposable() {
    val unhingedSettings = LocalUnhingedSettings.current

    // Check if unhinged theme is active
    if (unhingedSettings.isUnhingedThemeActive) {
        // Apply unhinged-specific styling
    }
}
```

### Material 3 Color Mappings

The Archive Beneath color scheme maps to Material 3 semantic colors:

- `primary` → GoldFilament
- `tertiary` → ImpossibleAccent (use sparingly!)
- `background` → ArchiveVoidDeep
- `surface` → ArchiveVoidBase
- `surfaceVariant` → ArchiveVoidSurface

## Reverting Unhinged Mode

The unhinged theme is completely isolated. To revert:

1. **At runtime**: Set `unhingedSettings.unhingedThemeEnabled = false`
2. **In code**: Pass `null` or a disabled `UnhingedSettings` to `NineLivesAudioTheme`
3. **Complete removal**: Delete this `unhinged/` directory and remove the theme switch logic from `Theme.kt`

## Phase Completion

✅ **Phase 1 Complete**: Dual theme foundations
- Normal theme remains untouched
- Unhinged theme fully isolated
- Single boolean flips entire app visual style
- No layout changes, just color/aesthetic

## Next Phases

- **Phase 2**: Surface language composables (StoneSlabCard, RelicSurface, FilamentDivider)
- **Phase 3**: Impossible accent discipline (focus states)
- **Phase 4**: Micro-interaction library (safe motion)
- **Phase 5+**: Sigil motion, anomalies, whispers, narrator
