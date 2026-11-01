package com.ninelivesaudio.app.ui.copy.unhinged.catalog

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.ninelivesaudio.app.ui.animation.unhinged.motion.MotionTokens
import com.ninelivesaudio.app.ui.theme.NineLivesTheme
import com.ninelivesaudio.app.ui.theme.unhinged.ArchiveTextFlavor
import com.ninelivesaudio.app.ui.theme.unhinged.ArchiveVoidElevated
import com.ninelivesaudio.app.ui.components.unhinged.LocalUnhingedSettings
import com.ninelivesaudio.app.ui.theme.unhinged.shouldShowWhispers
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whisper Display — Transient Text Overlay
 *
 * A small, transient text element that fades in, stays for ~4 seconds,
 * and fades out. Non-interactive, doesn't block anything.
 *
 * Usage:
 * ```
 * WhisperHost {
 *     // Your screen content
 * }
 * ```
 */
@Composable
fun WhisperHost(
    modifier: Modifier = Modifier,
    whisperService: WhisperService = WhisperService.instance,
    content: @Composable () -> Unit
) {
    val unhingedSettings = LocalUnhingedSettings.current
    val shouldShow = unhingedSettings.shouldShowWhispers
    val reduceMotion = unhingedSettings.reduceMotionRequested

    val currentWhisper by whisperService.currentWhisper.collectAsState()

    Box(modifier = modifier) {
        content()

        // Whisper overlay
        AnimatedVisibility(
            visible = shouldShow && currentWhisper != null,
            enter = if (reduceMotion) {
                fadeIn(animationSpec = tween(MotionTokens.DurationQuick))
            } else {
                fadeIn(animationSpec = MotionTokens.standardTween()) +
                        slideInVertically(
                            animationSpec = MotionTokens.standardTween(),
                            initialOffsetY = { it / 4 }
                        )
            },
            exit = if (reduceMotion) {
                fadeOut(animationSpec = tween(MotionTokens.DurationQuick))
            } else {
                fadeOut(animationSpec = MotionTokens.decelerateExit()) +
                        slideOutVertically(
                            animationSpec = MotionTokens.decelerateExit(),
                            targetOffsetY = { it / 4 }
                        )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp) // Above bottom nav
        ) {
            WhisperCard(text = currentWhisper ?: "")
        }
    }

    // Auto-dismiss after duration
    LaunchedEffect(currentWhisper) {
        if (currentWhisper != null) {
            delay(4000) // 4 seconds
            whisperService.dismissWhisper()
        }
    }
}

/**
 * Whisper Card — The visual container for whisper text
 */
@Composable
internal fun WhisperCard(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = NineLivesTheme.colors.archiveTextFlavor,
    borderColor: Color? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .shadow(
                elevation = 4.dp,
                shape = shape
            )
            .background(
                color = NineLivesTheme.colors.archiveVoidElevated,
                shape = shape
            )
            .let { if (borderColor != null) it.border(1.dp, borderColor, shape) else it }
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = textColor
        )
    }
}

/**
 * Trigger whisper manually
 *
 * Use this to trigger whispers at specific moments in your app.
 */
@Composable
fun TriggerWhisper(
    context: WhisperContext,
    whisperService: WhisperService = WhisperService.instance
) {
    val unhingedSettings = LocalUnhingedSettings.current
    val shouldShow = unhingedSettings.shouldShowWhispers
    val reduceMotion = unhingedSettings.reduceMotionRequested

    LaunchedEffect(context) {
        whisperService.tryShowWhisper(
            context = context,
            enabled = shouldShow,
            reduceMotion = reduceMotion
        )
    }
}

/**
 * Whisper on screen enter
 *
 * Shows a whisper when the composable enters the composition.
 * Perfect for screen-level whispers.
 *
 * Usage:
 * ```
 * @Composable
 * fun HomeScreen() {
 *     WhisperOnEnter(WhisperContext.APP_OPENED)
 *
 *     // Screen content
 * }
 * ```
 */
@Composable
fun WhisperOnEnter(
    context: WhisperContext,
    whisperService: WhisperService = WhisperService.instance
) {
    // Read after the delay, not at composition: the stored settings can arrive after the first frame.
    val settings by rememberUpdatedState(LocalUnhingedSettings.current)

    LaunchedEffect(Unit) {
        delay(500) // Small delay before showing
        val shown = whisperService.tryShowWhisper(
            context = context,
            enabled = settings.shouldShowWhispers,
            reduceMotion = settings.reduceMotionRequested
        )
        if (shown) Log.d("Whisper", "shown context=$context")
    }
}

/**
 * Whisper on event
 *
 * Shows a whisper when a specific event occurs.
 *
 * Usage:
 * ```
 * var chapterFinished by remember { mutableStateOf(false) }
 *
 * WhisperOnEvent(
 *     trigger = chapterFinished,
 *     context = WhisperContext.CHAPTER_FINISHED,
 *     onShown = { chapterFinished = false }
 * )
 * ```
 */
@Composable
fun WhisperOnEvent(
    trigger: Boolean,
    context: WhisperContext,
    whisperService: WhisperService = WhisperService.instance,
    onShown: () -> Unit = {}
) {
    val unhingedSettings = LocalUnhingedSettings.current
    val shouldShow = unhingedSettings.shouldShowWhispers
    val reduceMotion = unhingedSettings.reduceMotionRequested

    LaunchedEffect(trigger) {
        if (trigger) {
            val shown = whisperService.tryShowWhisper(
                context = context,
                enabled = shouldShow,
                reduceMotion = reduceMotion
            )
            if (shown) {
                onShown()
            }
        }
    }
}

/**
 * Listens for the playback and download moments that can whisper.
 *
 * Collects only while the app is on screen (STARTED), so nothing that happened in the
 * background shows up later. [events] receives the wall clock time the collection began, so a
 * replayed old event can be told apart from a new one. The toggle and Reduced Motion are read
 * at the moment an event arrives, and [WhisperService] holds the budgets.
 */
@Composable
fun WhisperEventTriggers(
    events: (sinceMillis: Long) -> Flow<WhisperContext>,
    whisperService: WhisperService = WhisperService.instance
) {
    val settings by rememberUpdatedState(LocalUnhingedSettings.current)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            events(System.currentTimeMillis()).collect { context ->
                val shown = whisperService.tryShowWhisper(
                    context = context,
                    enabled = settings.shouldShowWhispers,
                    reduceMotion = settings.reduceMotionRequested
                )
                if (shown) Log.d("Whisper", "shown context=$context")
            }
        }
    }
}

/**
 * Whispers once if the person sits on Home for a minute without touching the screen.
 * At most once per Home visit. Runs only while the app is in the foreground.
 */
@Composable
fun IdleWhisperTrigger(
    onHome: Boolean,
    lastInteractionAt: StateFlow<Long>,
    whisperService: WhisperService = WhisperService.instance
) {
    val settings by rememberUpdatedState(LocalUnhingedSettings.current)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(onHome, lifecycle) {
        if (!onHome) return@LaunchedEffect
        var fired = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (fired) return@repeatOnLifecycle awaitCancellation()
            awaitIdle(
                startedAt = SystemClock.elapsedRealtime(),
                lastInteractionAt = lastInteractionAt,
                clock = SystemClock::elapsedRealtime
            )
            fired = true
            val shown = whisperService.tryShowWhisper(
                context = WhisperContext.IDLE,
                enabled = settings.shouldShowWhispers,
                reduceMotion = settings.reduceMotionRequested
            )
            if (shown) Log.d("Whisper", "shown context=${WhisperContext.IDLE}")
            awaitCancellation()
        }
    }
}
