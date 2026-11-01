package com.ninelivesaudio.app.service.cover

import android.content.Context
import android.net.Uri
import android.util.Log
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.service.PlaybackManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class CoverSaveResult { SAVED, NOT_SAVED }

/**
 * Saves the cover a user chose (a search result or their own photo) as the book's cover,
 * and puts the scan's own art back on Use the original cover. Free for every user.
 */
@Singleton
class CoverPicker @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: AudioBookRepository,
    private val downloader: CoverDownloader,
    private val fitter: CoverImageFitter,
    private val playbackManager: PlaybackManager,
) {

    private val _changeCount = MutableStateFlow(0L)

    /**
     * Counts every saved pick and every undo, for any book, so home screen
     * widgets redraw with the new cover at once instead of at the next sync.
     */
    val changeCount: StateFlow<Long> = _changeCount.asStateFlow()

    private fun coverChanged() {
        _changeCount.value = _changeCount.value + 1
    }

    /** The large image, or the thumbnail the user saw when the large one fails. */
    suspend fun saveFromSearch(bookId: String, result: CoverResult): CoverSaveResult {
        val bytes = downloader.full(result.candidate) ?: result.thumb.bytes
        return save(bookId, bytes)
    }

    /** An image from the system photo picker. Nothing about it leaves the phone. */
    suspend fun saveFromPhoto(bookId: String, photo: Uri): CoverSaveResult {
        val bytes = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(photo)?.let { readCapped(it, MAX_PHOTO_BYTES) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "photo read failed: ${e.javaClass.simpleName}")
                null
            }
        } ?: return CoverSaveResult.NOT_SAVED
        return save(bookId, bytes)
    }

    /** Use the original cover. Never gated. True when a pick was removed. */
    suspend fun useOriginal(bookId: String): Boolean {
        val changed = repository.clearUserCover(bookId)
        if (changed) {
            coverChanged()
            playbackManager.onCoverChanged(bookId)
        }
        return changed
    }

    private suspend fun save(bookId: String, bytes: ByteArray): CoverSaveResult {
        val fitted = withContext(Dispatchers.Default) { fitter.fitToSquareJpeg(bytes) }
            ?: return CoverSaveResult.NOT_SAVED
        if (!repository.setUserCover(bookId, fitted)) return CoverSaveResult.NOT_SAVED
        coverChanged()
        playbackManager.onCoverChanged(bookId)
        return CoverSaveResult.SAVED
    }

    private companion object {
        const val TAG = "CoverPicker"
    }
}
