package com.ninelivesaudio.app.ui.copy.unhinged.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.ninelivesaudio.app.settings.unhinged.CopyMode
import com.ninelivesaudio.app.ui.theme.unhinged.ArchiveTextFlavor
import com.ninelivesaudio.app.ui.components.unhinged.LocalUnhingedSettings

/**
 * Ritual Label — Dual-Layer Text for Body Content
 *
 * Like RitualHeader but for smaller text elements (labels, captions, etc.).
 * Same dual-layer pattern: literal primary + optional flavor subtitle.
 *
 * @param text Primary text (literal, always visible)
 * @param modifier Modifier for this component
 * @param flavor Flavor text (only shown in Ritual/Unhinged mode)
 * @param style Text style for primary text
 */
@Composable
fun RitualLabel(
    text: String,
    modifier: Modifier = Modifier,
    flavor: String? = null,
    style: TextStyle = MaterialTheme.typography.bodyMedium
) {
    val unhingedSettings = LocalUnhingedSettings.current
    val showFlavor = unhingedSettings.copyMode != CopyMode.Normal && flavor != null

    if (showFlavor && flavor != null) {
        Column(
            modifier = modifier.semantics {
                contentDescription = text
            }
        ) {
            Text(
                text = text,
                style = style,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = flavor,
                style = MaterialTheme.typography.bodySmall,
                color = ArchiveTextFlavor,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    } else {
        // Normal mode or no flavor - just show primary text
        Text(
            text = text,
            modifier = modifier.semantics {
                contentDescription = text
            },
            style = style,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Ritual Caption
 * Small text with flavor for captions and metadata
 */
@Composable
fun RitualCaption(
    text: String,
    modifier: Modifier = Modifier,
    flavor: String? = null
) {
    RitualLabel(
        text = text,
        modifier = modifier,
        flavor = flavor,
        style = MaterialTheme.typography.bodySmall
    )
}

/**
 * Empty State Message
 *
 * Special variant for empty states with more prominent flavor text.
 * Empty states can afford to be more atmospheric since they're not critical.
 */
@Composable
fun RitualEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    flavorMessage: String? = null
) {
    val unhingedSettings = LocalUnhingedSettings.current
    val showFlavor = unhingedSettings.copyMode != CopyMode.Normal && flavorMessage != null

    Column(
        modifier = modifier.semantics {
            contentDescription = message
        }
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (showFlavor && flavorMessage != null) {
            Text(
                text = flavorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = ArchiveTextFlavor,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * Navigation Label
 *
 * For navigation items (tabs, bottom nav, etc.)
 * Keeps flavor text very short (3-5 words max)
 */
@Composable
fun RitualNavLabel(
    text: String,
    modifier: Modifier = Modifier,
    flavor: String? = null,
    isSelected: Boolean = false
) {
    val unhingedSettings = LocalUnhingedSettings.current
    val showFlavor = unhingedSettings.copyMode == CopyMode.Unhinged && flavor != null && isSelected

    Column(
        modifier = modifier.semantics {
            contentDescription = text
        }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )

        // Only show flavor for selected nav items in Unhinged mode
        if (showFlavor && flavor != null) {
            Text(
                text = flavor,
                style = MaterialTheme.typography.labelSmall,
                color = ArchiveTextFlavor.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
