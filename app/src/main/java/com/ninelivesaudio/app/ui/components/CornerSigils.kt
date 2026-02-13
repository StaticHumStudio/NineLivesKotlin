package com.ninelivesaudio.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ninelivesaudio.app.ui.theme.unhinged.GoldFilament
import com.ninelivesaudio.app.ui.theme.unhinged.ImpossibleAccent

/**
 * Small corner indicator dots overlaid on tiles.
 *
 * - TopEnd: Downloaded dot (GoldFilament)
 * - TopStart: Bookmarked dot (ImpossibleAccent)
 *
 * Place with Modifier.matchParentSize() inside a Box.
 */
@Composable
fun CornerSigils(
    downloaded: Boolean,
    bookmarked: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (downloaded) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(GoldFilament),
            )
        }
        if (bookmarked) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(ImpossibleAccent),
            )
        }
    }
}
