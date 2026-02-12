package com.ninelivesaudio.app.ui.theme.unhinged.surfaces

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ninelivesaudio.app.ui.theme.unhinged.ArchiveOutline
import com.ninelivesaudio.app.ui.components.unhinged.LocalUnhingedSettings
import com.ninelivesaudio.app.ui.theme.unhinged.StoneSlab
import com.ninelivesaudio.app.ui.theme.unhinged.isUnhingedThemeActive

/**
 * Stone Slab Card — Surface Language Composable
 *
 * A card component for list items (e.g., "Continue Listening" tiles, book cards).
 *
 * **Normal mode**: Standard Material 3 card with elevation/shadow
 * **Unhinged mode**: "Slab" aesthetic — low elevation, matte surface, subtle stone-like texture
 *
 * Design rules:
 * - Slab surfaces: calm, utilitarian, low visual noise
 * - Same layout and spacing in both modes
 * - Never busy or overwhelming
 *
 * @param modifier Modifier for this card
 * @param onClick Optional click handler
 * @param enabled Whether the card is enabled for interaction
 * @param content Card content
 */
@Composable
fun StoneSlabCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val unhingedSettings = LocalUnhingedSettings.current
    val isUnhinged = unhingedSettings.isUnhingedThemeActive

    // Theme-dependent styling
    val containerColor = if (isUnhinged) {
        StoneSlab // Matte obsidian surface
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    val elevation = if (isUnhinged) {
        1.dp // Low elevation - slabs sit close to the void
    } else {
        4.dp // Standard elevation for normal cards
    }

    val border = if (isUnhinged) {
        BorderStroke(
            width = 0.5.dp,
            color = ArchiveOutline.copy(alpha = 0.3f)
        )
    } else {
        null
    }

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = CardDefaults.cardColors(
                containerColor = containerColor
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = elevation
            ),
            border = border,
            content = content
        )
    } else {
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(
                containerColor = containerColor
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = elevation
            ),
            border = border,
            content = content
        )
    }
}

/**
 * Preview variant with standard padding for common use cases
 */
@Composable
fun StoneSlabCardPadded(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    StoneSlabCard(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled
    ) {
        Column(modifier = Modifier.padding(padding)) {
            content()
        }
    }
}
