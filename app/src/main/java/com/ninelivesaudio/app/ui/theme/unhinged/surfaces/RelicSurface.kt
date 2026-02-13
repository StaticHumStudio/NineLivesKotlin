package com.ninelivesaudio.app.ui.theme.unhinged.surfaces

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ninelivesaudio.app.ui.theme.unhinged.ArchiveOutline
import com.ninelivesaudio.app.ui.theme.unhinged.GoldFilamentGlow
import com.ninelivesaudio.app.ui.theme.unhinged.StoneRelic

/**
 * Relic Surface — Surface Language Composable
 *
 * A container panel for primary content areas (e.g., main content sections, feature panels).
 *
 * **Normal mode**: Standard surface container
 * **Unhinged mode**: "Relic" aesthetic — slightly elevated, faint inner shadow,
 * subtle rim highlight on top edge (like light catching aged stone)
 *
 * Design rules:
 * - Relic surfaces: feel old and important, but never busy
 * - Higher elevation than slabs (they're special)
 * - Subtle rim lighting suggests depth
 * - Same layout and spacing in both modes
 *
 * @param modifier Modifier for this surface
 * @param content Surface content
 */
@Composable
fun RelicSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .shadow(
                elevation = 6.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.4f),
                spotColor = Color.Black.copy(alpha = 0.4f)
            )
            .clip(shape)
            .background(StoneRelic)
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        GoldFilamentGlow.copy(alpha = 0.15f),
                        Color.Transparent,
                    ),
                    startY = 0f,
                    endY = 50f
                ),
                shape = shape
            )
            .border(
                width = 0.5.dp,
                color = ArchiveOutline.copy(alpha = 0.4f),
                shape = shape
            ),
        content = content
    )
}

/**
 * Preview variant with standard padding for common use cases
 */
@Composable
fun RelicSurfacePadded(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    RelicSurface(modifier = modifier) {
        Box(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}
