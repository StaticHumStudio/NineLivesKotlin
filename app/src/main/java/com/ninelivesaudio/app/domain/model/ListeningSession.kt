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
    /**
     * The library the session was played in, as the server or the local row
     * recorded it. Lets history outlive its book in the cache (issue #66).
     * Null from a server too old to send it.
     */
    val libraryId: String? = null,
)
