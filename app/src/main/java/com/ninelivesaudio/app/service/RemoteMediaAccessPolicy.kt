package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.remote.validatedServerBaseUrl
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import com.ninelivesaudio.app.service.download.sanitizeDownloadFileName
import java.io.File
import java.net.URI

sealed interface RemoteMediaAccessDecision {
    val mayReplaceCurrentPlayerItem: Boolean

    data object LocalFile : RemoteMediaAccessDecision {
        override val mayReplaceCurrentPlayerItem: Boolean = true
    }

    data class Remote(val serverBaseUrl: String) : RemoteMediaAccessDecision {
        override val mayReplaceCurrentPlayerItem: Boolean = true
    }

    data class Blocked(val message: String) : RemoteMediaAccessDecision {
        override val mayReplaceCurrentPlayerItem: Boolean = false
    }
}

fun RemoteMediaAccessDecision.usesLocalTracks(): Boolean =
    this is RemoteMediaAccessDecision.LocalFile

fun remoteMediaAccessDecision(
    book: AudioBook,
    serverUrl: String,
    connectionStatus: ConnectionStatus,
    localDownloadAvailable: Boolean = hasUsableLocalDownload(book),
): RemoteMediaAccessDecision {
    if (book.isLocal || localDownloadAvailable) {
        return RemoteMediaAccessDecision.LocalFile
    }

    val validatedUrl = validatedServerBaseUrl(serverUrl)
        ?: return RemoteMediaAccessDecision.Blocked(
            "Connect a valid Audiobookshelf server in Settings to use this book."
        )

    return when (connectionStatus) {
        ConnectionStatus.CONNECTED, ConnectionStatus.SYNCING ->
            RemoteMediaAccessDecision.Remote(validatedUrl.toString())

        ConnectionStatus.OFFLINE -> RemoteMediaAccessDecision.Blocked(
            "You are offline. Reconnect, or download this book before going offline."
        )

        ConnectionStatus.SERVER_UNREACHABLE -> RemoteMediaAccessDecision.Blocked(
            "The Audiobookshelf server is unreachable. Check the connection in Settings."
        )
    }
}

fun hasUsableLocalDownload(book: AudioBook): Boolean {
    if (!book.isDownloaded || book.localPath.isNullOrBlank()) return false
    val path = book.localPath
    return try {
        val uri = URI(path)
        val file = when (uri.scheme?.lowercase()) {
            null -> File(path)
            "file" -> File(uri)
            else -> return false
        }
        file.containsPlayableAudio(book)
    } catch (_: Exception) {
        File(path).containsPlayableAudio(book)
    }
}

private val PLAYABLE_AUDIO_EXTENSIONS = setOf(
    "mp3", "m4a", "m4b", "opus", "ogg", "flac", "aac", "wma", "wav",
)

private fun File.containsPlayableAudio(book: AudioBook): Boolean {
    val expectedNames = book.audioFiles.mapIndexed { index, audioFile ->
        sanitizeDownloadFileName(audioFile.filename.ifEmpty { "track_${index + 1}" })
    }.toSet()
    fun File.isPlayableAudio(): Boolean =
        isFile && length() > 0L &&
            (extension.lowercase() in PLAYABLE_AUDIO_EXTENSIONS || name in expectedNames)

    return when {
        isFile -> isPlayableAudio()
        isDirectory -> {
            val downloadedFiles = listFiles()?.filter { it.isFile && it.length() > 0L }.orEmpty()
            if (expectedNames.isNotEmpty()) {
                expectedNames.all { expected -> downloadedFiles.any { it.name == expected } }
            } else {
                downloadedFiles.any { it.extension.lowercase() in PLAYABLE_AUDIO_EXTENSIONS }
            }
        }
        else -> false
    }
}
