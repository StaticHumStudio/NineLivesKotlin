package com.ninelivesaudio.app.ui.copy.unhinged.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
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
 * Ritual Header — Dual-Layer Text Component
 *
 * Shows a primary label (always literal and clear) with an optional
 * flavor subtitle that appears in Ritual or Unhinged copy mode.
 *
 * **Design rules**:
 * - Primary label is ALWAYS literal (user must understand at a glance)
 * - Flavor text is ALWAYS secondary (smaller, lower contrast, below primary)
 * - Screen readers announce ONLY the primary label
 * - Flavor text never replaces or obscures the primary label
 *
 * @param title Primary label (literal, always visible)
 * @param subtitle Flavor text (only shown in Ritual/Unhinged mode)
 * @param modifier Modifier for this component
 * @param titleStyle Text style for primary label
 * @param subtitleStyle Text style for flavor text
 */
@Composable
fun RitualHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.headlineMedium,
    subtitleStyle: TextStyle = MaterialTheme.typography.bodySmall
) {
    val unhingedSettings = LocalUnhingedSettings.current
    val showFlavor = unhingedSettings.copyMode != CopyMode.Normal && subtitle != null

    Column(
        modifier = modifier.semantics {
            // Screen readers announce ONLY the literal label
            contentDescription = title
        }
    ) {
        // Primary label - always visible, always literal
        Text(
            text = title,
            style = titleStyle,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Flavor text - only in Ritual/Unhinged mode
        if (showFlavor && subtitle != null) {
            Text(
                text = subtitle,
                style = subtitleStyle,
                color = ArchiveTextFlavor, // Lower contrast for subtlety
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * Small Ritual Header variant
 * Uses smaller text styles for less prominent headings
 */
@Composable
fun RitualHeaderSmall(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    RitualHeader(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        titleStyle = MaterialTheme.typography.titleLarge,
        subtitleStyle = MaterialTheme.typography.bodySmall
    )
}

/**
 * Medium Ritual Header variant
 * Default size for most section headers
 */
@Composable
fun RitualHeaderMedium(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    RitualHeader(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        titleStyle = MaterialTheme.typography.headlineSmall,
        subtitleStyle = MaterialTheme.typography.bodySmall
    )
}
