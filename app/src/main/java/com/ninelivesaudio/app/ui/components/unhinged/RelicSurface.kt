package com.ninelivesaudio.app.ui.components.unhinged

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Relic Surface - An elevated surface with optional edge glow in Unhinged Mode
 *
 * **Normal Mode:**
 * - Standard Material3 Surface
 * - Clean, minimal appearance
 *
 * **Unhinged Mode:**
 * - Subtle gold edge glow (Archive filament aesthetic)
 * - Very faint radial gradient from edges
 * - Suggests "artifact under glass" or "preserved relic"
 *
 * Design Philosophy:
 * - Edge glow is BARELY perceptible (1-3% opacity)
 * - Should feel like ancient preservation, not neon signs
 * - Only visible when you're looking for it
 *
 * @param modifier Standard modifier
 * @param tonalElevation Material3 tonal elevation
 * @param showEdgeGlow Whether to show edge glow in Unhinged mode (default true)
 * @param content The surface content
 */
@Composable
fun RelicSurface(
    modifier: Modifier = Modifier,
    tonalElevation: Dp = 0.dp,
    showEdgeGlow: Boolean = true,
    content: @Composable () -> Unit
) {
    val settings = LocalUnhingedSettings.current
    val isUnhinged = settings.isUnhinged

    Surface(
        modifier = if (isUnhinged && showEdgeGlow) {
            modifier.drawBehind {
                // Subtle gold edge glow (Archive filament)
                val glowColor = Color(0xFFE8C468).copy(alpha = 0.03f)

                // Top edge glow
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            glowColor,
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = size.height * 0.15f
                    )
                )

                // Bottom edge glow
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            glowColor
                        ),
                        startY = size.height * 0.85f,
                        endY = size.height
                    )
                )

                // Left edge glow
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            glowColor,
                            Color.Transparent
                        ),
                        startX = 0f,
                        endX = size.width * 0.15f
                    )
                )

                // Right edge glow
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            glowColor
                        ),
                        startX = size.width * 0.85f,
                        endX = size.width
                    )
                )
            }
        } else modifier,
        tonalElevation = tonalElevation,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        content()
    }
}
