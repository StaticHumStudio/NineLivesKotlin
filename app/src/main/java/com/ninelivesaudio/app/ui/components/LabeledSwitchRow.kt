package com.ninelivesaudio.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ninelivesaudio.app.ui.theme.NineLivesTheme

/**
 * A Settings row whose text and switch are one accessible control. The parent
 * owns the sole toggle action so the visual [Switch] cannot double-toggle when
 * a person taps its label area or uses a screen reader.
 */
@Composable
internal fun LabeledSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    verticalPadding: Dp = 0.dp,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    titleTakesRemainingWidth: Boolean = true,
    switchColors: SwitchColors = SwitchDefaults.colors(),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement,
    ) {
        Column(
            modifier = if (titleTakesRemainingWidth) {
                Modifier.weight(1f)
            } else {
                Modifier
            },
        ) {
            androidx.compose.material3.Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = NineLivesTheme.colors.archiveTextPrimary,
            )
            androidx.compose.material3.Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = NineLivesTheme.colors.archiveTextMuted,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = switchColors,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}
