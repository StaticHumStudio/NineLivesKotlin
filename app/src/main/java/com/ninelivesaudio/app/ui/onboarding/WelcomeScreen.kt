package com.ninelivesaudio.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninelivesaudio.app.ui.theme.unhinged.ArchiveTextMuted
import com.ninelivesaudio.app.ui.theme.unhinged.ArchiveTextPrimary
import com.ninelivesaudio.app.ui.theme.unhinged.ArchiveVoidSurface
import com.ninelivesaudio.app.ui.theme.unhinged.GoldFilament

@Composable
fun WelcomeScreen(
    onChooseLocal: () -> Unit,
    onChooseServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Nine Lives",
            style = MaterialTheme.typography.headlineMedium,
            color = GoldFilament,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Where do your audiobooks live?",
            style = MaterialTheme.typography.bodyMedium,
            color = ArchiveTextMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SourceCard(
                icon = Icons.Outlined.FolderOpen,
                label = "Local",
                onClick = onChooseLocal,
                modifier = Modifier.weight(1f),
            )
            SourceCard(
                icon = Icons.Outlined.Dns,
                label = "Server",
                onClick = onChooseServer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SourceCard(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ArchiveVoidSurface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = GoldFilament,
                modifier = Modifier.size(36.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = ArchiveTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
