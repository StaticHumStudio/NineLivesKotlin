package com.ninelivesaudio.app.domain.model

import kotlin.time.Duration

data class ListeningSession(
    val id: String,
    val libraryItemId: String,
    val currentTime: Duration,
    val timeListening: Duration,
    val startedAt: Long,
    val updatedAt: Long,
    val displayTitle: String?,
)
