package com.ninelivesaudio.app.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.dao.DownloadItemDao
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus
import com.ninelivesaudio.app.service.ConnectivityMonitor
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import com.ninelivesaudio.app.service.DownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadManager: DownloadManager,
    private val downloadItemDao: DownloadItemDao,
    private val audioBookDao: AudioBookDao,
    private val connectivityMonitor: ConnectivityMonitor,
) : ViewModel() {

    // ─── UI State ────────────────────────────────────────────────────────────

    data class DownloadUiItem(
        val download: DownloadItem,
        val coverPath: String? = null,
    )

    data class UiState(
        val activeDownloads: List<DownloadUiItem> = emptyList(),
        val completedDownloads: List<DownloadUiItem> = emptyList(),
        val showEmptyState: Boolean = true,
        val connectionStatus: ConnectionStatus = ConnectionStatus.OFFLINE,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // Observe active downloads
        viewModelScope.launch {
            downloadItemDao.observeActive().collect { entities ->
                val items = entities.map { entity ->
                    val item = entity.toDomain()
                    val book = audioBookDao.getById(item.audioBookId)
                    DownloadUiItem(
                        download = item,
                        coverPath = book?.coverPath,
                    )
                }
                _uiState.update {
                    it.copy(
                        activeDownloads = items,
                        showEmptyState = items.isEmpty() && it.completedDownloads.isEmpty(),
                    )
                }
            }
        }

        // Observe completed downloads
        viewModelScope.launch {
            downloadItemDao.observeCompleted().collect { entities ->
                val items = entities.map { entity ->
                    val item = entity.toDomain()
                    val book = audioBookDao.getById(item.audioBookId)
                    DownloadUiItem(
                        download = item,
                        coverPath = book?.coverPath,
                    )
                }
                _uiState.update {
                    it.copy(
                        completedDownloads = items,
                        showEmptyState = items.isEmpty() && it.activeDownloads.isEmpty(),
                    )
                }
            }
        }

        // Observe progress updates
        viewModelScope.launch {
            downloadManager.progressUpdates.collect { progress ->
                _uiState.update { state ->
                    state.copy(
                        activeDownloads = state.activeDownloads.map { uiItem ->
                            if (uiItem.download.id == progress.downloadId) {
                                uiItem.copy(
                                    download = uiItem.download.copy(
                                        downloadedBytes = progress.downloadedBytes,
                                        totalBytes = progress.totalBytes,
                                    )
                                )
                            } else uiItem
                        }
                    )
                }
            }
        }

        // Observe connection status
        viewModelScope.launch {
            connectivityMonitor.connectionStatus.collect { status ->
                _uiState.update { it.copy(connectionStatus = status) }
            }
        }
    }

    // ─── Actions ─────────────────────────────────────────────────────────────

    fun pauseDownload(downloadId: String) {
        viewModelScope.launch {
            downloadManager.pauseDownload(downloadId)
        }
    }

    fun resumeDownload(downloadId: String) {
        viewModelScope.launch {
            downloadManager.resumeDownload(downloadId)
        }
    }

    fun cancelDownload(downloadId: String) {
        viewModelScope.launch {
            downloadManager.cancelDownload(downloadId)
        }
    }

    fun deleteDownload(audioBookId: String) {
        viewModelScope.launch {
            downloadManager.deleteDownload(audioBookId)
        }
    }

    fun clearCompleted() {
        viewModelScope.launch {
            val completed = _uiState.value.completedDownloads
            completed.forEach { item ->
                downloadItemDao.deleteById(item.download.id)
            }
        }
    }

    /** Queue a new download for an audiobook. */
    fun queueDownload(audioBook: AudioBook) {
        viewModelScope.launch {
            downloadManager.queueDownload(audioBook)
        }
    }
}
