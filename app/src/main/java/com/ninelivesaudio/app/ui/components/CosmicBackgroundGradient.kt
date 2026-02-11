package com.ninelivesaudio.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.ninelivesaudio.app.ui.theme.CosmicEnergy1
import com.ninelivesaudio.app.ui.theme.GradientBlue
import com.ninelivesaudio.app.ui.theme.VoidDeep

@Composable
fun CosmicBackgroundGradient(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        // Base solid background
        drawRect(color = VoidDeep) // #070A10

        // Radial gradient overlay (subtle, restrained)
        val gradientCenter = Offset(size.width * 0.5f, size.height * 0.3f)
        val gradientRadius = size.maxDimension * 0.6f

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    CosmicEnergy1.copy(alpha = 0.12f), // Violet core (subtle)
                    GradientBlue.copy(alpha = 0.06f),  // Blue mid
                    Color.Transparent                   // Fade to base
                ),
                center = gradientCenter,
                radius = gradientRadius
            )
        )
    }
}
