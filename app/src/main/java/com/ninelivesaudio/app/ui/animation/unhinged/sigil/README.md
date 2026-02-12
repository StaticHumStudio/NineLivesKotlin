# Sigil Motion — Animated Progress Indicators

## Overview

Sigil Motion makes progress indicators feel subtly alive in Unhinged Mode. The animations are **so subtle you'd only notice if staring** - they enhance atmosphere without demanding attention.

## Components

### 1. SigilProgress (Circular)

Circular progress indicator with three subtle animations:

**Rotation Drift**:
- 0.5° per second rotation (12 minutes for full circle)
- So slow it's barely perceptible
- Makes the sigil feel like it's slowly turning

**Filament Shimmer**:
- Small highlight crawling along the progress arc
- Like light catching a gold thread
- 6-second cycle

**Breathing Opacity**:
- Gentle pulse between 85% and 100% opacity
- Only active during playback
- 3-second breathing cycle

#### Usage

```kotlin
// Basic usage
SigilProgress(
    progress = currentProgress,
    size = 48.dp,
    strokeWidth = 4.dp
)

// With active state (enables breathing)
SigilProgress(
    progress = playbackProgress,
    size = 64.dp,
    strokeWidth = 6.dp,
    isActive = isPlaying
)
```

---

### 2. SigilProgressBar (Linear)

Linear progress bar with shimmer effect.

**Features**:
- Horizontal shimmer sweep (crawling highlight)
- Breathing opacity during active state
- Impossible accent overlay (thin 1dp line on top)

#### Usage

```kotlin
// Standard progress bar
SigilProgressBar(
    progress = downloadProgress,
    modifier = Modifier.fillMaxWidth(),
    height = 4.dp
)

// Active playback progress
SigilProgressBar(
    progress = currentPosition / totalDuration,
    modifier = Modifier.fillMaxWidth(),
    height = 6.dp,
    isActive = isPlaying
)
```

---

### 3. MiniPlayerProgressFilament

Specialized thin progress bar for mini-player/bottom bar.

**Features**:
- Very thin (2dp)
- Breathing animation during playback
- Optimized for always-visible progress

#### Usage

```kotlin
// Bottom bar / mini-player
MiniPlayerProgressFilament(
    progress = playbackProgress,
    modifier = Modifier.fillMaxWidth(),
    isPlaying = isCurrentlyPlaying
)
```

---

## Behavior by Theme

### Normal Mode
- Standard Material 3 progress indicators
- No rotation, no shimmer, no breathing
- Clean and functional

### Unhinged Mode (Motion Enabled)
- Rotation drift: 0.5°/sec
- Filament shimmer: crawling highlight
- Breathing opacity: 85%-100% pulse
- All three effects combined create subtle "life"

### Reduce Motion
- Static appearance
- No rotation
- No shimmer
- No breathing
- Shows progress value only

---

## Design Guardrails

### ✅ Correct Usage

**Progress must always be instantly readable**:
```kotlin
// Good - progress value is immediately clear
SigilProgress(progress = 0.75f)
```

**Animation enhances, never obscures**:
- Rotation is so slow you barely notice
- Shimmer is subtle, not distracting
- Breathing is gentle, not pulsing

**Respects playback state**:
```kotlin
// Breathing only during active playback
SigilProgress(
    progress = progress,
    isActive = playbackState == PlaybackState.PLAYING
)
```

### ❌ Incorrect Usage

**Don't make rotation noticeable**:
```kotlin
// Wrong - rotation would be too fast
// The component already has the correct speed built in
```

**Don't layer multiple progress indicators**:
```kotlin
// Wrong - too much motion
Row {
    SigilProgress(progress = p1)
    SigilProgress(progress = p2)
    SigilProgress(progress = p3)
}
```

**Don't use during driving/car mode**:
```kotlin
// In car mode, use standard progress instead
if (isCarMode) {
    CircularProgressIndicator(progress = progress)
} else {
    SigilProgress(progress = progress)
}
```

---

## Animation Specifications

### Rotation Drift
- **Duration**: 720,000ms (12 minutes)
- **Easing**: Linear
- **Range**: 0° to 360°
- **Speed**: ~0.5°/second

### Filament Shimmer
- **Duration**: 6,000ms (6 seconds)
- **Easing**: Linear
- **Pattern**: Crawls from start to end of arc
- **Visibility**: 60% alpha max

### Breathing Opacity
- **Duration**: 3,000ms (3 seconds)
- **Easing**: Standard cubic-bezier
- **Range**: 85% to 100%
- **Trigger**: Only when `isActive = true`

---

## Integration Examples

### Chapter Progress Ring
```kotlin
@Composable
fun ChapterProgressIndicator(
    currentPosition: Long,
    duration: Long,
    isPlaying: Boolean
) {
    val progress = (currentPosition.toFloat() / duration).coerceIn(0f, 1f)

    Box(contentAlignment = Alignment.Center) {
        SigilProgress(
            progress = progress,
            size = 120.dp,
            strokeWidth = 8.dp,
            isActive = isPlaying
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = formatTime(currentPosition),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = formatTime(duration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
```

### Download Progress with Percentage
```kotlin
@Composable
fun DownloadProgressCard(
    downloadProgress: Float,
    isDownloading: Boolean
) {
    StoneSlabCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Downloading...")
                Text("${(downloadProgress * 100).toInt()}%")
            }

            SigilProgressBar(
                progress = downloadProgress,
                modifier = Modifier.fillMaxWidth(),
                height = 6.dp,
                isActive = isDownloading
            )
        }
    }
}
```

### Playback Scrubber
```kotlin
@Composable
fun PlaybackScrubber(
    currentPosition: Long,
    duration: Long,
    onSeek: (Float) -> Unit,
    isPlaying: Boolean
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf(0f) }

    val displayProgress = if (isDragging) {
        dragPosition
    } else {
        currentPosition.toFloat() / duration
    }

    Column {
        SigilProgressBar(
            progress = displayProgress,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { isDragging = true },
                        onDragEnd = {
                            isDragging = false
                            onSeek(dragPosition)
                        },
                        onDrag = { change, _ ->
                            dragPosition = (change.position.x / size.width)
                                .coerceIn(0f, 1f)
                        }
                    )
                },
            height = 8.dp,
            isActive = isPlaying && !isDragging
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatTime(currentPosition))
            Text(formatTime(duration))
        }
    }
}
```

---

## Performance Notes

### Optimizations
- Uses `Canvas` for efficient drawing
- Animations run on composition clock
- No layout recomposition during animation
- Shimmer uses simple gradient, not blur

### Memory
- Minimal allocation (just float states)
- No bitmap caching needed
- Suitable for always-visible progress

### Battery Impact
- Negligible when using reduce motion
- Minimal when active (simple transforms)
- Pauses when app is backgrounded (automatic)

---

## Testing Checklist

- [ ] Progress value is instantly readable in both themes
- [ ] Rotation is barely noticeable (not consciously tracked)
- [ ] Shimmer enhances without distracting
- [ ] Breathing only occurs during active playback
- [ ] Reduce motion shows static progress
- [ ] Smooth performance on low-end devices
- [ ] No frame drops during scrubbing

---

## Phase 5 Status

✅ **Complete** - Sigil motion progress indicators implemented
- Circular progress with rotation drift + shimmer
- Linear progress bar with shimmer sweep
- Mini-player filament variant
- Full reduce motion support
- Breathing animation tied to playback state

**Acceptance Criteria Met**:
- ✅ Progress instantly readable at a glance
- ✅ Shimmer adds atmosphere without drawing attention
- ✅ Works smoothly alongside progress updates
- ✅ Rotation so slow it's barely perceptible

**Next Phase**: Anomaly Overlay System
