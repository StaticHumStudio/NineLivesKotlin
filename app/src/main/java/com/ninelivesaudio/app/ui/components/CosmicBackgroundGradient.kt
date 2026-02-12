package com.ninelivesaudio.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

@Composable
fun CosmicBackgroundGradient(modifier: Modifier = Modifier) {
    // Use theme-aware colors so background changes with theme
    val backgroundColor = MaterialTheme.colorScheme.background
    val primaryGradient = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    val secondaryGradient = MaterialTheme.colorScheme.secondary.copy(alpha = 0.05f)

    Canvas(modifier = modifier.fillMaxSize()) {
        // Base solid background - uses theme's background color
        drawRect(color = backgroundColor)

        // Radial gradient overlay (subtle, restrained)
        val gradientCenter = Offset(size.width * 0.5f, size.height * 0.3f)
        val gradientRadius = size.maxDimension * 0.6f

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryGradient,   // Theme primary with alpha
                    secondaryGradient, // Theme secondary with alpha
                    Color.Transparent  // Fade to base
                ),
                center = gradientCenter,
                radius = gradientRadius
            )
        )
    }
}
