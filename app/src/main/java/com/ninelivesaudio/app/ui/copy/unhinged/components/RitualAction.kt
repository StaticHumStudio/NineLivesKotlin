package com.ninelivesaudio.app.ui.copy.unhinged.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ninelivesaudio.app.settings.unhinged.CopyMode
import com.ninelivesaudio.app.ui.theme.unhinged.ArchiveTextFlavor
import com.ninelivesaudio.app.ui.components.unhinged.LocalUnhingedSettings

/**
 * Ritual Action — Dual-Layer Button Component
 *
 * A button with a literal label and optional flavor subtitle.
 * Same pattern as RitualHeader but for interactive elements.
 *
 * **Design rules**:
 * - Primary label is ALWAYS literal (action must be instantly clear)
 * - Flavor text is ALWAYS secondary (smaller, muted, below label)
 * - Screen readers announce ONLY the primary label
 * - Destructive actions NEVER show flavor text (delete, remove, cancel)
 *
 * @param label Primary button text (literal, always visible)
 * @param onClick Click handler
 * @param modifier Modifier for this button
 * @param subtitle Flavor text (only shown in Ritual/Unhinged mode)
 * @param enabled Whether button is enabled
 * @param colors Button colors (defaults to primary)
 */
@Composable
fun RitualAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors()
) {
    val unhingedSettings = LocalUnhingedSettings.current
    val showFlavor = unhingedSettings.copyMode != CopyMode.Normal && subtitle != null

    Button(
        onClick = onClick,
        modifier = modifier.semantics {
            // Screen readers announce ONLY the literal label
            contentDescription = label
        },
        enabled = enabled,
        colors = colors,
        contentPadding = if (showFlavor) {
            PaddingValues(horizontal = 24.dp, vertical = 12.dp)
        } else {
            ButtonDefaults.ContentPadding
        }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Primary label - always visible, always literal
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge
            )

            // Flavor text - only in Ritual/Unhinged mode
            if (showFlavor) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = ArchiveTextFlavor.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * Ritual Text Button variant
 * For less prominent actions
 */
@Composable
fun RitualTextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    val unhingedSettings = LocalUnhingedSettings.current
    val showFlavor = unhingedSettings.copyMode != CopyMode.Normal && subtitle != null

    TextButton(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = label
        },
        enabled = enabled
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge
            )

            if (showFlavor) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = ArchiveTextFlavor.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * Ritual Outlined Action variant
 * For secondary actions with borders
 */
@Composable
fun RitualOutlinedAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    val unhingedSettings = LocalUnhingedSettings.current
    val showFlavor = unhingedSettings.copyMode != CopyMode.Normal && subtitle != null

    OutlinedButton(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = label
        },
        enabled = enabled,
        contentPadding = if (showFlavor) {
            PaddingValues(horizontal = 24.dp, vertical = 12.dp)
        } else {
            ButtonDefaults.ContentPadding
        }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge
            )

            if (showFlavor) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = ArchiveTextFlavor.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * Destructive Action (NO FLAVOR)
 *
 * For destructive actions (delete, remove, cancel), flavor text is NEVER shown.
 * These actions must be completely unambiguous.
 *
 * @param label Literal action label (e.g., "Delete", "Remove", "Cancel")
 * @param onClick Click handler
 * @param modifier Modifier for this button
 * @param enabled Whether button is enabled
 */
@Composable
fun DestructiveAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = label
        },
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error
        )
    ) {
        // No flavor text - just literal label
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/**
 * Ritual Icon Action
 *
 * Icon button with optional tooltip that includes flavor text
 */
@Composable
fun RitualIconAction(
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    val unhingedSettings = LocalUnhingedSettings.current
    val showFlavor = unhingedSettings.copyMode != CopyMode.Normal && subtitle != null

    val tooltipText = if (showFlavor) {
        "$label\n$subtitle"
    } else {
        label
    }

    IconButton(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = label // Screen reader uses literal label only
        },
        enabled = enabled
    ) {
        icon()
    }
}
