package com.ninelivesaudio.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninelivesaudio.app.ui.theme.unhinged.*

/**
 * Unified screen header used by Library, Downloads, and Settings.
 *
 * Gold title sitting directly on the void. No card, no gradient, no box.
 * Anchored by a thin gold filament line underneath.
 */
@Composable
fun ArchiveScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = GoldFilament,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp,
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = ArchiveTextMuted,
                        fontSize = 12.sp,
                    )
                }
            }
            if (trailing != null) {
                Spacer(modifier = Modifier.width(12.dp))
                trailing()
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Thin gold filament line
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
        ) {
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        GoldFilament.copy(alpha = 0.6f),
                        GoldFilament.copy(alpha = 0.25f),
                        Color.Transparent,
                    ),
                    startX = 0f,
                    endX = size.width,
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 1f,
                cap = StrokeCap.Butt,
            )
        }
    }
}
