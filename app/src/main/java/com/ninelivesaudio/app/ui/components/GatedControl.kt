package com.ninelivesaudio.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ninelivesaudio.app.ui.theme.NineLivesTheme

/**
 * Wraps a control that the free tier cannot use.
 *
 * **Greyed, never hidden.** A hidden feature reads as a feature the app does not
 * have, which is the worst possible outcome for a free tier whose entire job is
 * to show what the unlock buys. A greyed one with a lock on it reads as a choice
 * the user has not made yet.
 *
 * When locked, the wrapped content is dimmed, its own interactions are swallowed
 * by an overlay, and a tap goes to [onLockedTap] instead. When unlocked this is
 * a plain pass-through with no wrapper cost.
 *
 * The lock sigil sits in the top-end corner deliberately, matching where
 * [CornerSigils] puts its markers, so gated state reads as part of the same
 * vocabulary rather than a bolted-on badge.
 */
@Composable
fun GatedControl(
    locked: Boolean,
    onLockedTap: () -> Unit,
    modifier: Modifier = Modifier,
    /** Named in the tap target for screen readers, e.g. "Playback speed". */
    label: String? = null,
    content: @Composable () -> Unit,
) {
    if (!locked) {
        Box(modifier = modifier) { content() }
        return
    }

    Box(modifier = modifier) {
        // The content still lays out at full size and stays readable. Dimmed
        // enough to be obviously inactive, not so far that it becomes unreadable
        // and stops advertising what it does.
        Box(modifier = Modifier.alpha(DISABLED_ALPHA)) { content() }

        // Above the content on purpose. Without this the wrapped control keeps
        // its own clickable and a locked toggle would still toggle.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClick = onLockedTap)
                .semantics {
                    contentDescription = label
                        ?.let { "$it, locked. Tap to unlock." }
                        ?: "Locked. Tap to unlock."
                },
        )

        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            tint = NineLivesTheme.colors.goldFilamentDim,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(14.dp),
        )
    }
}

private const val DISABLED_ALPHA = 0.38f
