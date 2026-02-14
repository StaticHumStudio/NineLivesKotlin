package com.ninelivesaudio.app.domain.model

enum class DownloadStatus {
    Queued,
    Downloading,
    Paused,
    Completed,
    Failed,
    Cancelled,
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
        get() = when {
            totalBytes >= 1_073_741_824 -> "%.1f GB".format(totalBytes / 1_073_741_824.0)
            totalBytes >= 1_048_576 -> "%.1f MB".format(totalBytes / 1_048_576.0)
            totalBytes >= 1024 -> "%.1f KB".format(totalBytes / 1024.0)
            else -> "${totalBytes.coerceAtLeast(0)} B"
        }
}
