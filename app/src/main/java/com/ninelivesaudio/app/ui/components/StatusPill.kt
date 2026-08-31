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
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import com.ninelivesaudio.app.ui.theme.NineLivesTheme
import com.ninelivesaudio.app.ui.theme.unhinged.*

enum class ConnectionStatusPresentation {
    LOCAL,
    SIGNED_OUT,
    CONNECTED,
    SYNCING,
    SERVER_UNREACHABLE,
    OFFLINE,
}

internal fun connectionStatusPresentation(
    appMode: AppMode,
    hasAuthToken: Boolean?,
    connectionStatus: ConnectionStatus,
): ConnectionStatusPresentation = when {
    appMode == AppMode.LOCAL -> ConnectionStatusPresentation.LOCAL
    hasAuthToken == false -> ConnectionStatusPresentation.SIGNED_OUT
    else -> when (connectionStatus) {
        ConnectionStatus.CONNECTED -> ConnectionStatusPresentation.CONNECTED
        ConnectionStatus.SYNCING -> ConnectionStatusPresentation.SYNCING
        ConnectionStatus.SERVER_UNREACHABLE -> ConnectionStatusPresentation.SERVER_UNREACHABLE
        ConnectionStatus.OFFLINE -> ConnectionStatusPresentation.OFFLINE
    }
}

/**
 * Connection status indicator pill, matching the Windows app's status dot + label.
 * Shows the local source, signed-out session, or current server reachability.
 */
@Composable
fun StatusPill(
    presentation: ConnectionStatusPresentation,
    modifier: Modifier = Modifier,
) {
    val (label, dotColor) = when (presentation) {
        ConnectionStatusPresentation.LOCAL -> "Local" to NineLivesTheme.colors.archiveLocalAccent
        ConnectionStatusPresentation.SIGNED_OUT -> "Signed out" to NineLivesTheme.colors.archiveTextMuted
        ConnectionStatusPresentation.CONNECTED -> "Connected" to NineLivesTheme.colors.archiveSuccess
        ConnectionStatusPresentation.SYNCING -> "Syncing" to NineLivesTheme.colors.goldFilament
        ConnectionStatusPresentation.SERVER_UNREACHABLE ->
            "Server Unreachable" to NineLivesTheme.colors.archiveWarning
        ConnectionStatusPresentation.OFFLINE -> "Offline" to NineLivesTheme.colors.archiveTextMuted
    }

    val animatedDotColor by animateColorAsState(
        targetValue = dotColor,
        animationSpec = tween(durationMillis = 300),
        label = "statusDotColor"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(NineLivesTheme.colors.archiveVoidSurface)
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
