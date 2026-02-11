package com.ninelivesaudio.app.domain.model

import kotlin.time.Duration

data class UserProgress(
    val libraryItemId: String = "",
    val episodeId: String? = null,
    val currentTime: Duration = Duration.ZERO,
    val progress: Double = 0.0,
    val isFinished: Boolean = false,
    val lastUpdate: Long? = null, // epoch millis
)
