package com.ninelivesaudio.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.ui.theme.*

/**
 * Library grid item card showing cover art, title, author, progress bar, and download badge.
 * Matches the Windows app's library card design.
 */
@Composable
fun BookCard(
    book: AudioBook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            // Cover art
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(VoidElevated),
            ) {
                if (!book.coverPath.isNullOrEmpty()) {
                    AsyncImage(
                        model = book.coverPath,
                        contentDescription = book.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }

                // Download badge
                if (book.isDownloaded) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                        color = CosmicInfo.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Icon(
                            Icons.Outlined.CloudDownload,
                            contentDescription = "Downloaded",
                            modifier = Modifier
                                .padding(4.dp)
                                .size(14.dp),
                            tint = Starlight,
                        )
                    }
                }
            }

            // Title + Author + Progress
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = Starlight,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp,
                )

                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = Mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp,
                )

                // Progress bar
                if (book.hasProgress) {
                    Spacer(modifier = Modifier.height(4.dp))

                    LinearProgressIndicator(
                        progress = { (book.progress.coerceIn(0.0, 1.0)).toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = SigilGold,
                        trackColor = ProgressTrack,
                    )

                    Text(
                        text = book.progressText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MistFaint,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}
