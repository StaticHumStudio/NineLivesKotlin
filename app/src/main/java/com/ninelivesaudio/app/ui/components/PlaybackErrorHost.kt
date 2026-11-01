package com.ninelivesaudio.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ninelivesaudio.app.ui.copy.unhinged.catalog.WhisperCard

/**
 * Where a failed Play says why. It keeps Material's SnackbarHost, which gives
 * TalkBack its polite live-region announcement, and dresses the message in the
 * whisper card's look. It does not go through the whisper system, because a
 * user can turn whispers off and an error must still show.
 */
@Composable
fun PlaybackErrorHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(
        hostState = state,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) { data -> WhisperCard(text = data.visuals.message) }
}
