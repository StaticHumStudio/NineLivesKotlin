# Micro-Interaction Library

## Overview

The **Micro-Interaction Library** provides a reusable animation toolkit for Unhinged Mode. All animations are:
- **Snappy and responsive** - Never floaty or laggy
- **Subtle and atmospheric** - Never distracting
- **Accessibility-aware** - Respects reduce motion preferences
- **No infinite loops** - Except for specific ambient effects

## Components

### 1. Motion Tokens (`MotionTokens.kt`)

Central animation constants used throughout the app.

#### Duration Constants
```kotlin
MotionTokens.DurationQuick        // 120ms - immediate feedback
MotionTokens.DurationStandard     // 250ms - most transitions
MotionTokens.DurationSlow         // 600ms - deliberate moments
MotionTokens.DurationVerySlow     // 1200ms - ambient effects
MotionTokens.DurationUltraSlow    // 3000ms - barely perceptible
```

#### Easing Functions
```kotlin
MotionTokens.EasingStandard      // Material emphasized easing
MotionTokens.EasingEmphasized    // More pronounced deceleration
MotionTokens.EasingDecelerate    // For exits and fades
MotionTokens.EasingLinear        // For continuous effects
```

#### Pre-configured Specs
```kotlin
MotionTokens.quickTween<T>()         // 120ms standard easing
MotionTokens.standardTween<T>()      // 250ms standard easing
MotionTokens.slowTween<T>()          // 600ms emphasized easing
MotionTokens.emphasizedEntrance<T>() // For important reveals
MotionTokens.decelerateExit<T>()     // For fade outs
MotionTokens.standardSpring<T>()     // Responsive spring
MotionTokens.gentleSpring<T>()       // Soft spring
```

#### Animation Values
```kotlin
MotionTokens.PressScale      // 0.98 (2% smaller)
MotionTokens.HoverScale      // 1.02 (2% larger)
MotionTokens.GlowPulseMin    // 0.3 alpha
MotionTokens.GlowPulseMax    // 0.6 alpha
```

---

### 2. Micro-Interactions (`MicroInteractions.kt`)

Reusable modifier functions for common animations.

#### Press Feedback
```kotlin
Button(
    onClick = { },
    modifier = Modifier.pressFeedback()
) {
    Text("Press Me")
}
```
**Effect**: Scales to 0.98 when pressed, springs back to 1.0 on release

---

#### Selection Glow Pulse
```kotlin
Box(
    modifier = Modifier
        .background(ImpossibleAccent.copy(alpha = 0.3f))
        .selectionGlowPulse(isSelected = true)
) {
    // Content
}
```
**Effect**: Glow opacity pulses between 30% and 60% when selected

---

#### Shimmer Effect
```kotlin
Box(
    modifier = Modifier
        .shimmerEffect(enabled = isLoading)
        .background(MaterialTheme.colorScheme.surfaceVariant)
) {
    // Loading placeholder
}
```
**Effect**: Subtle shimmer sweep across the element (for loading states)

---

#### Breathing Effect
```kotlin
Icon(
    imageVector = Icons.Default.Star,
    contentDescription = "Special",
    modifier = Modifier.breathingEffect(enabled = isSpecial)
)
```
**Effect**: Very subtle scale pulse (0.99 → 1.01) - use sparingly!

---

### 3. Archive Transitions (`ArchiveTransitions`)

Pre-configured enter/exit transitions.

#### Fade and Slide
```kotlin
AnimatedVisibility(
    visible = isVisible,
    enter = ArchiveTransitions.fadeSlideInEnter(),
    exit = ArchiveTransitions.fadeSlideOutExit()
) {
    // Content
}
```

#### Expand and Fade
```kotlin
AnimatedVisibility(
    visible = isExpanded,
    enter = ArchiveTransitions.expandFadeInEnter(),
    exit = ArchiveTransitions.shrinkFadeOutExit()
) {
    // Expandable content
}
```

#### Convenience Wrapper
```kotlin
ArchiveAnimatedVisibility(visible = isVisible) {
    // Automatically uses Archive transitions
    Text("Animated content")
}
```

---

## Reduce Motion Support

All animations respect `UnhingedSettings.reduceMotionRequested`:

```kotlin
val unhingedSettings = LocalUnhingedSettings.current
val duration = MotionTokens.getDuration(
    durationMs = MotionTokens.DurationStandard,
    reduceMotionRequested = unhingedSettings.reduceMotionRequested
)
// Returns 0 if reduce motion is enabled, 250 otherwise
```

**When reduce motion is enabled**:
- Duration → 0 (instant)
- Animations → snap() or static state
- Slides/scales → fade only
- Infinite loops → static appearance

---

## Usage Examples

### Interactive Card with Press Feedback
```kotlin
StoneSlabCard(
    onClick = { },
    modifier = Modifier
        .pressFeedback()
        .selectionEdgeHighlight(isSelected = isSelected)
) {
    Text("Interactive Card")
}
```

### Animated Panel Reveal
```kotlin
var isExpanded by remember { mutableStateOf(false) }

Column {
    Button(onClick = { isExpanded = !isExpanded }) {
        Text("Toggle Panel")
    }

    ArchiveAnimatedVisibility(visible = isExpanded) {
        RelicSurface {
            Text("Panel content", Modifier.padding(16.dp))
        }
    }
}
```

### Pulsing Selection Indicator
```kotlin
Row(
    modifier = Modifier
        .selectionEdgeHighlight(isSelected = true)
) {
    Box(
        modifier = Modifier
            .size(4.dp)
            .background(
                ImpossibleAccent,
                shape = CircleShape
            )
            .selectionGlowPulse(isSelected = true)
    )
    Text("Selected Item")
}
```

---

## Performance Guidelines

### ✅ Good Practices
- Use springs for interactive elements (responsive feel)
- Use tweens for predetermined animations (predictable timing)
- Apply animations to small, isolated elements
- Prefer `graphicsLayer` over layout modifiers for transforms

### ❌ Avoid
- Animating layout properties in lists (causes recomposition)
- Infinite loops on many elements simultaneously
- Heavy animations during scrolling
- Animating large images or complex compositions

---

## Testing

### Test reduce motion behavior:
1. Enable reduce motion in system settings
2. Verify all animations either skip or use static fallbacks
3. Check that no content becomes inaccessible

### Test performance:
1. Use Layout Inspector to check recomposition counts
2. Profile animations on low-end devices
3. Verify 60fps during animations
4. Test with many animated elements on screen

---

## Phase 4 Status

✅ **Complete** - Micro-interaction library fully implemented
- `MotionTokens.kt` - Central animation constants and specs
- `MicroInteractions.kt` - Reusable modifier functions
- `ArchiveTransitions` - Pre-configured enter/exit animations
- Full reduce motion support
- Comprehensive documentation

**Acceptance Criteria Met**:
- ✅ Animations feel snappy and responsive
- ✅ No animation loops indefinitely by default
- ✅ Reduce motion disables all motion gracefully
- ✅ Animations work smoothly on low-end hardware

**Next Phase**: Sigil Motion (progress indicators with subtle life)
