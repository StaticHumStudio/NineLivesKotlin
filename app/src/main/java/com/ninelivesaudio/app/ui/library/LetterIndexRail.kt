package com.ninelivesaudio.app.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninelivesaudio.app.ui.theme.NineLivesTheme
import kotlin.math.roundToInt

/** What TalkBack calls the letter rail. */
internal const val LETTER_RAIL_LABEL = "Jump to letter"

private val RAIL_WIDTH = 28.dp
private val MIN_LETTER_SPACING = 14.dp

/**
 * A strip of letters down the right edge of the Library list. Touch or drag
 * on it jumps the list to the first row under that letter, with a big letter
 * shown while a finger is down. Fill the list's box with it: it only draws
 * and takes touches on the strip itself.
 *
 * A drag costs a lookup in [index] and a scroll request, so nothing here
 * grows with the number of books. TalkBack gets the strip as one adjustable
 * control, where swiping up or down steps through the letters.
 *
 * @param leadingItemCount list items before the first row [index] counts, such
 *   as a sync warning banner
 */
@Composable
internal fun LetterIndexRail(
    index: LetterIndex,
    listState: LazyListState,
    leadingItemCount: Int,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    if (index.isEmpty) return

    val haptics = LocalHapticFeedback.current
    val minSpacingPx = with(LocalDensity.current) { MIN_LETTER_SPACING.toPx() }
    val currentIndex by rememberUpdatedState(index)
    val currentLeading by rememberUpdatedState(leadingItemCount)

    // The letter under a finger, or -1 with none down. The one the list is
    // sitting on is worked out from its scroll position, a binary search that
    // only re-runs the rail when the answer changes.
    var touchedLabel by remember { mutableIntStateOf(-1) }
    // The last letter picked, since a short list cannot always scroll as far
    // as the last few letters and would otherwise never report reaching them.
    var pickedLabel by remember { mutableIntStateOf(-1) }
    var railHeightPx by remember { mutableIntStateOf(0) }

    val scrolledLabel by remember(index, leadingItemCount) {
        derivedStateOf {
            val fromScroll = index.labelIndexAt(listState.firstVisibleItemIndex - leadingItemCount)
            if (!listState.canScrollForward && pickedLabel > fromScroll) {
                pickedLabel.coerceAtMost(index.labels.lastIndex)
            } else {
                fromScroll
            }
        }
    }

    val jumpTo = remember(listState) {
        { labelIdx: Int ->
            val live = currentIndex
            if (labelIdx in live.labels.indices) {
                pickedLabel = labelIdx
                listState.requestScrollToItem(live.offsets[labelIdx] + currentLeading)
            }
        }
    }

    val shownLabel = if (touchedLabel >= 0) touchedLabel else scrolledLabel
    val stride = letterRailStride(index.labels.size, railHeightPx.toFloat(), minSpacingPx)

    Box(modifier = modifier.fillMaxSize()) {
        if (touchedLabel in index.labels.indices) {
            LetterBubble(
                label = index.labels[touchedLabel],
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(top = 8.dp, bottom = bottomInset + 8.dp)
                .fillMaxHeight()
                .width(RAIL_WIDTH)
                .onSizeChanged { railHeightPx = it.height }
                // The strip sits on the screen edge, where the back swipe lives.
                .systemGestureExclusion()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (touchedLabel >= 0) NineLivesTheme.colors.archiveVoidSurface.copy(alpha = 0.85f)
                    else Color.Transparent,
                )
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var last = -1
                        fun pickAt(y: Float) {
                            val count = currentIndex.labels.size
                            val picked = letterIndexAtTouch(y, size.height.toFloat(), count)
                            if (picked != last) {
                                last = picked
                                touchedLabel = picked
                                jumpTo(picked)
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                        pickAt(down.position.y)
                        while (true) {
                            val change = awaitPointerEvent().changes
                                .firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            pickAt(change.position.y)
                            change.consume()
                        }
                        touchedLabel = -1
                    }
                }
                .clearAndSetSemantics {
                    contentDescription = LETTER_RAIL_LABEL
                    stateDescription = letterRailSpokenLabel(index.labels[shownLabel.coerceIn(0, index.labels.lastIndex)])
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = shownLabel.toFloat(),
                        range = 0f..index.labels.lastIndex.toFloat(),
                        steps = (index.labels.size - 2).coerceAtLeast(0),
                    )
                    setProgress { target ->
                        jumpTo(target.roundToInt().coerceIn(0, index.labels.lastIndex))
                        true
                    }
                },
        ) {
            index.labels.forEachIndexed { position, label ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (position % stride == 0) {
                        val active = position == shownLabel
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) NineLivesTheme.colors.goldFilament
                            else NineLivesTheme.colors.archiveTextSecondary,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 10.sp,
                            lineHeight = 10.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

/** The big letter shown in the middle of the list while the rail is held. */
@Composable
private fun LetterBubble(label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = NineLivesTheme.colors.archiveVoidElevated,
        border = BorderStroke(1.dp, NineLivesTheme.colors.goldFilament.copy(alpha = 0.6f)),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
            style = MaterialTheme.typography.headlineLarge,
            color = NineLivesTheme.colors.goldFilament,
            fontWeight = FontWeight.Bold,
        )
    }
}
