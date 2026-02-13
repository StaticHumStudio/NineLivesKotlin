package com.ninelivesaudio.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.ui.theme.unhinged.*

/**
 * Library list item with a compact cover on the left and metadata/status on the right.
 */
@Composable
fun BookListItem(
    book: AudioBook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = ArchiveVoidSurface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, ArchiveOutline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ArchiveVoidElevated),
            ) {
                if (!book.coverPath.isNullOrEmpty()) {
                    AsyncImage(
                        model = book.coverPath,
                        contentDescription = book.title,
                        modifier = Modifier
                            .fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = ArchiveTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = ArchiveTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusTag(
                        label = if (book.isDownloaded) "Downloaded" else "Not downloaded",
                        isHighlighted = book.isDownloaded,
                    )
                    StatusTag(
                        label = if (book.isFinished) "Completed" else if (book.hasProgress) "In progress" else "Not started",
                        isHighlighted = book.isFinished || book.hasProgress,
                    )
                }

                if (book.hasProgress) {
                    Spacer(modifier = Modifier.height(3.dp))
                    LinearProgressIndicator(
                        progress = { (book.progressPercent / 100.0).coerceIn(0.0, 1.0).toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = GoldFilament,
                        trackColor = ArchiveOutline,
                    )
                    Text(
                        text = book.progressText,
                        style = MaterialTheme.typography.labelSmall,
                        color = ArchiveTextMuted,
                    )
                }
            }

            if (book.isDownloaded) {
                Surface(
                    color = ArchiveInfo.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Icon(
                        Icons.Outlined.CloudDownload,
                        contentDescription = "Downloaded",
                        modifier = Modifier
                            .padding(8.dp)
                            .size(16.dp),
                        tint = ArchiveInfo,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusTag(
    label: String,
    isHighlighted: Boolean,
) {
    Surface(
        color = if (isHighlighted) GoldFilament.copy(alpha = 0.16f) else ArchiveVoidSurface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isHighlighted) GoldFilament.copy(alpha = 0.5f) else ArchiveOutline,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isHighlighted) GoldFilament else ArchiveTextSecondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
