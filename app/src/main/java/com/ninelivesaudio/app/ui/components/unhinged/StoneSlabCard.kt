package com.ninelivesaudio.app.ui.components.unhinged

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * Stone Slab Card - A surface component that adapts based on Unhinged Mode
 *
 * **Normal Mode:**
 * - Clean Material3 Card with standard elevation
 * - No special effects
 *
 * **Unhinged Mode:**
 * - Subtle texture overlay (sparse noise pattern)
 * - Slightly aged/weathered appearance
 * - Uses Archive Beneath colors
 *
 * Design Philosophy:
 * - Texture is VERY subtle (2-5% opacity at most)
 * - Should feel "ancient archive" not "broken UI"
 * - Maintains full readability and accessibility
 *
 * @param modifier Standard modifier
 * @param elevation Card elevation (defaults to 2.dp in normal, 1.dp in unhinged for flatter look)
 * @param content The card content
 */
@Composable
fun StoneSlabCard(
    modifier: Modifier = Modifier,
    elevation: Dp? = null,
    content: @Composable () -> Unit
) {
    val settings = LocalUnhingedSettings.current
    val isUnhinged = settings.isUnhinged

    // Unhinged uses flatter elevation for "slab" feel
    val cardElevation = elevation ?: if (isUnhinged) 1.dp else 2.dp

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElevation)
    ) {
        Box {
            // Content layer
            content()

            // Texture overlay (only in Unhinged mode)
            if (isUnhinged) {
                StoneTexture()
            }
        }
    }
}

/**
 * Subtle stone/aged texture overlay
 * Very sparse noise pattern to suggest weathered archive surfaces
 */
@Composable
private fun StoneTexture() {
    val settings = LocalUnhingedSettings.current
    val seed = settings.sessionSeed.hashCode()

    Canvas(
        modifier = Modifier.fillMaxSize()
    ) {
        val random = Random(seed)
        val noiseColor = Color.White.copy(alpha = 0.02f) // VERY subtle

        // Sparse noise particles (only ~50 per card, very faint)
        repeat(50) {
            val x = random.nextFloat() * size.width
            val y = random.nextFloat() * size.height
            val radius = random.nextFloat() * 1.5f + 0.5f

            drawCircle(
                color = noiseColor,
                radius = radius,
                center = Offset(x, y)
            )
        }
    }
}
