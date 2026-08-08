package com.ninelivesaudio.app.domain.model

import com.ninelivesaudio.app.domain.util.toDisplaySize

enum class DownloadStatus {
    Queued,
    Downloading,
    Paused,
    Completed,
    Failed,
    Cancelled,

    /**
     * A provisional claim on the free tier's single offline slot, taken BEFORE
     * the network round trip that fetches book details.
     *
     * Appended at ordinal 6 on purpose. Room persists this enum by ordinal, so
     * inserting anywhere above would silently rewrite the meaning of every
     * existing row on every installed device. Queued through Cancelled keep 0
     * to 5 forever.
     *
     * The drain's `Status IN (0, 1)` ignores it, so a provisional claim is never
     * downloaded. It still counts against the slot, which is the entire point:
     * without it, two concurrent queue attempts both see a free slot during the
     * metadata fetch and both claim it.
     */
    Preparing,
}

data class DownloadItem(
    val id: String = "",
    val audioBookId: String = "",
    val title: String = "",
    val status: DownloadStatus = DownloadStatus.Queued,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val startedAt: Long? = null,   // epoch millis
    val completedAt: Long? = null, // epoch millis
    val errorMessage: String? = null,
    val filesToDownload: List<String> = emptyList(),
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
) {
    val progress: Double
        get() = if (totalBytes > 0) (downloadedBytes.toDouble() / totalBytes * 100.0).coerceIn(0.0, 100.0) else 0.0

    val sizeDisplay: String
        get() = totalBytes.toDisplaySize()
}
