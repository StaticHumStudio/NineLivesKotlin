package com.ninelivesaudio.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import com.ninelivesaudio.app.ui.theme.*

/**
 * Connection status indicator pill, matching the Windows app's status dot + label.
 * Shows: Connected (green), Syncing (gold), Server Unreachable (warning), Offline (gray).
 */
@Composable
fun StatusPill(
    connectionStatus: ConnectionStatus,
    modifier: Modifier = Modifier,
) {
    val (label, dotColor) = when (connectionStatus) {
        ConnectionStatus.CONNECTED -> "Connected" to CosmicSuccess
        ConnectionStatus.SYNCING -> "Syncing" to SigilGold
        ConnectionStatus.SERVER_UNREACHABLE -> "Server Unreachable" to CosmicWarning
        ConnectionStatus.OFFLINE -> "Offline" to MistFaint
    }

    val animatedDotColor by animateColorAsState(
        targetValue = dotColor,
        animationSpec = tween(durationMillis = 300),
        label = "statusDotColor"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(VoidSurface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Status dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(animatedDotColor)
        )

        // Status label
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = animatedDotColor,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
        )
    }
}
