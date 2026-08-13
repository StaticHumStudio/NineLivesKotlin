package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.isInActiveLibrary
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import kotlin.time.Duration

data class PlaybackRestorePlan(
    val book: AudioBook,
    val position: Duration,
    val playWhenReady: Boolean = false,
)

fun resolvePlaybackRestore(
    persistedBookId: String,
    storedBook: AudioBook?,
    settings: AppSettings,
    savedPosition: Duration?,
): PlaybackRestorePlan? {
    val book = storedBook
        ?.takeIf { it.id == persistedBookId }
        ?.takeIf { !it.isArchived }
        ?.takeIf { it.isInActiveLibrary(settings) }
        ?: return null

    val position = listOfNotNull(book.currentTime, savedPosition).maxOrNull()
        ?: Duration.ZERO
    return PlaybackRestorePlan(
        book = book.copy(currentTime = position),
        position = position,
        playWhenReady = false,
    )
}

fun shouldProbeServerBeforeRestore(
    book: AudioBook,
    connectionStatus: ConnectionStatus,
    localDownloadAvailable: Boolean = hasUsableLocalDownload(book),
): Boolean =
    !book.isLocal &&
        !localDownloadAvailable &&
        connectionStatus != ConnectionStatus.CONNECTED &&
        connectionStatus != ConnectionStatus.SYNCING
