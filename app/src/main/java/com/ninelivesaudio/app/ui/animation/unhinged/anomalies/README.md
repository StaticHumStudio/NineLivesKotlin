# Anomaly Overlay System

## Overview

The **Anomaly Overlay System** enables rare, subtle visual anomalies that enhance atmosphere without interfering with interaction. Anomalies are controlled weird - they feel special precisely because they're so rare.

## Core Principle

**"Did that just happen?"**

Anomalies should be rare enough that users consciously notice them. Not annoying, not constant - just surprising enough to feel like the archive is slightly alive.

## Components

### 1. IAnomalyEffect Interface

Defines the contract for anomaly effects:

```kotlin
interface IAnomalyEffect {
    val id: String
    val durationMs: Long
    val supportsReducedMotion: Boolean
    val maxOpacity: Float

    fun drawAnimated(drawScope, progress, width, height)
    fun drawStatic(drawScope, width, height)
}
```

---

### 2. Built-in Effects

#### Tear Veil
Faint vertical streaks (2-6% opacity):
- 3-6 random vertical lines
- Looks like scratches on old film
- 8 second duration
- Slow fade in/out

#### Ink Bleed Vignette
Dark gradients from corners (2-8% opacity):
- 1-2 random corners
- Looks like ink seeping in
- 12 second duration (very slow)
- Radial gradient spread

#### Crack Whisper
Hairline fractures from edges (2-4% opacity):
- 2-4 random crack patterns
- Starts from screen edges
- 10 second duration
- Branching line segments

---

### 3. AnomalyHost Composable

The main wrapper that manages anomaly display:

```kotlin
AnomalyHost(currentContext = AnomalyTriggerContext.HOME) {
    // Your main content here
    YourScreen()
}
```

**Features**:
- Transparent overlay on top of content
- Never blocks interaction (Canvas is non-interactive)
- Respects `UnhingedSettings.anomaliesEnabled`
- Respects reduce motion
- Manages timing and scheduling

---

### 4. Anomaly Scheduler

Pseudo-random scheduling with cooldowns:

**Default Configuration**:
- Min cooldown: 3 minutes
- Max cooldown: 10 minutes
- Deterministic seed (same session = same sequence)

**Allowed Contexts**:
- `HOME` - Safe to show anomalies
- `LIBRARY` - Safe to show anomalies
- `IDLE` - App idle for 60+ seconds

**Blocked Contexts** (anomalies NEVER appear):
- Active playback controls
- Modal dialogs
- Error states
- Settings pages
- During user interaction

---

## Usage

### Basic Integration

Wrap your main content with `AnomalyHost`:

```kotlin
@Composable
fun HomeScreen() {
    AnomalyHost(currentContext = AnomalyTriggerContext.HOME) {
        Column {
            Text("Home Screen Content")
            // ... rest of your UI
        }
    }
}
```

### Context-Aware Usage

Pass the appropriate context based on current screen:

```kotlin
@Composable
fun AppNavigation() {
    val currentRoute = navController.currentBackStackEntry?.destination?.route

    val anomalyContext = when (currentRoute) {
        "home" -> AnomalyTriggerContext.HOME
        "library" -> AnomalyTriggerContext.LIBRARY
        else -> AnomalyTriggerContext.HOME // Default
    }

    AnomalyHost(currentContext = anomalyContext) {
        NavHost(navController) {
            // Your navigation graph
        }
    }
}
```

### Debug Mode

Force a specific anomaly for testing:

```kotlin
@Composable
fun DebugScreen() {
    var selectedEffect by remember { mutableStateOf<IAnomalyEffect?>(null) }

    Column {
        Button(onClick = { selectedEffect = TearVeilEffect() }) {
            Text("Test Tear Veil")
        }
        Button(onClick = { selectedEffect = InkBleedEffect() }) {
            Text("Test Ink Bleed")
        }
        Button(onClick = { selectedEffect = CrackWhisperEffect() }) {
            Text("Test Crack Whisper")
        }

        AnomalyHostDebug(
            currentContext = AnomalyTriggerContext.HOME,
            forcedAnomaly = selectedEffect
        ) {
            // Your test content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            )
        }
    }
}
```

---

## Creating Custom Anomalies

Implement `IAnomalyEffect`:

```kotlin
class CustomAnomaly : IAnomalyEffect {
    override val id = "custom_anomaly"
    override val durationMs = 5000L
    override val supportsReducedMotion = true
    override val maxOpacity = 0.05f

    override fun drawAnimated(
        drawScope: DrawScope,
        progress: Float,
        canvasWidth: Float,
        canvasHeight: Float
    ) {
        // Draw your effect
        // progress ranges from 0f to 1f over durationMs
        val alpha = when {
            progress < 0.3f -> progress / 0.3f
            progress > 0.7f -> (1f - progress) / 0.3f
            else -> 1f
        }

        // Your drawing code here
    }

    override fun drawStatic(
        drawScope: DrawScope,
        canvasWidth: Float,
        canvasHeight: Float
    ) {
        // Static version for reduce motion
        drawAnimated(drawScope, 0.5f, canvasWidth, canvasHeight)
    }
}
```

---

## Configuration

### Anomaly Settings

Control via `UnhingedSettings`:

```kotlin
// Enable/disable anomalies
unhingedSettings.anomaliesEnabled = true

// Anomalies auto-disable when reduce motion is on
val shouldShow = unhingedSettings.shouldShowAnomalies
// Returns: anomaliesEnabled && !reduceMotionRequested
```

### Cooldown Customization

Modify `AnomalyConfig`:

```kotlin
val customConfig = AnomalyConfig(
    minCooldownMs = 60_000L,  // 1 minute
    maxCooldownMs = 300_000L, // 5 minutes
    allowedContexts = setOf(
        AnomalyTriggerContext.HOME,
        AnomalyTriggerContext.IDLE
    ),
    randomSeed = myCustomSeed
)
```

---

## Design Guidelines

### Opacity Rules

**Keep it subtle**:
- Most effects: 2-6% opacity maximum
- Never exceed 10% opacity
- If you can clearly see it without looking for it, it's too strong

### Duration Rules

**Slow and deliberate**:
- Minimum: 5 seconds
- Typical: 8-12 seconds
- Maximum: 20 seconds
- Fade in/out should take 20-30% of total duration

### Frequency Rules

**Rare is special**:
- Default: 3-10 minute intervals
- Never more frequent than once per minute
- Never during active user interaction
- Maximum one anomaly at a time

### Safety Rules

**Never interfere**:
- ✅ Overlays must be non-interactive
- ✅ Never reduce text contrast below WCAG AA
- ✅ Never obscure critical UI elements
- ✅ Respect reduce motion completely
- ✅ Independent kill switch (anomaliesEnabled)

---

## Testing

### Manual Testing

1. Enable anomalies in settings
2. Navigate to home or library screen
3. Wait 3-10 minutes for first anomaly
4. Verify overlay doesn't block interaction
5. Check reduce motion disables anomalies

### Debug Testing

Use `AnomalyHostDebug` to force effects:

```kotlin
// Test each effect individually
AnomalyHostDebug(
    currentContext = AnomalyTriggerContext.HOME,
    forcedAnomaly = TearVeilEffect()
) {
    YourContent()
}
```

### Automated Testing

Check configuration:

```kotlin
@Test
fun `anomalies respect reduce motion`() {
    val settings = UnhingedSettings(
        anomaliesEnabled = true,
        reduceMotionRequested = true
    )

    assertFalse(settings.shouldShowAnomalies)
}

@Test
fun `anomaly opacity stays within bounds`() {
    val effects = listOf(
        TearVeilEffect(),
        InkBleedEffect(),
        CrackWhisperEffect()
    )

    effects.forEach { effect ->
        assertTrue(effect.maxOpacity <= 0.1f)
    }
}
```

---

## Performance

### Optimization

- Uses Canvas for efficient drawing
- No bitmap allocation
- Minimal recomposition
- Pauses when app is backgrounded

### Memory

- Negligible (just float states and path data)
- No textures or images
- Effects are procedurally generated

### Battery

- Minimal impact (60fps only during anomaly)
- No continuous animation when inactive
- Respects system battery saver

---

## Removal

To completely remove anomaly system:

1. **Runtime**: Set `anomaliesEnabled = false`
2. **Remove from UI**: Don't wrap content in `AnomalyHost`
3. **Clean removal**: Delete `anomalies/` folder

---

## Phase 6 Status

✅ **Complete** - Anomaly overlay system implemented
- `IAnomalyEffect` interface
- Three built-in effects (Tear Veil, Ink Bleed, Crack Whisper)
- `AnomalyHost` composable wrapper
- Pseudo-random scheduler with cooldowns
- Full reduce motion support
- Debug mode for testing

**Acceptance Criteria Met**:
- ✅ Anomalies feel rare and special
- ✅ Toggling off removes all anomalies instantly
- ✅ One-line removal from visual tree
- ✅ Never reduce contrast below WCAG AA
- ✅ Overlays never block interaction

**Next Phase**: Dual-Layer Labeling System
