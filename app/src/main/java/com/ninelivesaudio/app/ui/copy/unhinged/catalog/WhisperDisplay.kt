package com.ninelivesaudio.app.ui.copy.unhinged.catalog

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.ninelivesaudio.app.ui.animation.unhinged.motion.MotionTokens
import com.ninelivesaudio.app.ui.theme.unhinged.ArchiveTextFlavor
import com.ninelivesaudio.app.ui.theme.unhinged.ArchiveVoidElevated
import com.ninelivesaudio.app.ui.components.unhinged.LocalUnhingedSettings
import com.ninelivesaudio.app.ui.theme.unhinged.shouldShowWhispers
import kotlinx.coroutines.delay

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
private fun WhisperCard(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(12.dp)
            )
            .background(
                color = ArchiveVoidElevated,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = ArchiveTextFlavor
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
    val unhingedSettings = LocalUnhingedSettings.current
    val shouldShow = unhingedSettings.shouldShowWhispers
    val reduceMotion = unhingedSettings.reduceMotionRequested

    LaunchedEffect(Unit) {
        delay(500) // Small delay before showing
        whisperService.tryShowWhisper(
            context = context,
            enabled = shouldShow,
            reduceMotion = reduceMotion
        )
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
