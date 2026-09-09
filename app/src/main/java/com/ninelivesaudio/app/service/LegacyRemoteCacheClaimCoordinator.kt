package com.ninelivesaudio.app.service

import androidx.room.withTransaction
import com.ninelivesaudio.app.data.local.AppDatabase
import com.ninelivesaudio.app.data.local.converter.rewriteLegacyFolderLibraryIds
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.data.local.entity.DownloadItemEntity
import com.ninelivesaudio.app.data.local.entity.LibraryEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.data.remote.ActiveRemoteScope
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.remote.LegacyRemoteCacheState
import com.ninelivesaudio.app.data.remote.canBeClaimedBy
import com.ninelivesaudio.app.entitlement.EntitlementCachePrefs
import com.ninelivesaudio.app.entitlement.ScopedPreferenceWrite
import javax.inject.Inject
import javax.inject.Singleton

/** Converts only the cache proven to belong to the cold-restored remote owner. */
@Singleton
class LegacyRemoteCacheClaimCoordinator @Inject constructor(
    private val database: AppDatabase,
    private val settingsManager: SettingsManager,
    private val apiService: ApiService,
    private val entitlementCachePrefs: EntitlementCachePrefs,
) {
    suspend fun claimIfEligible(): Boolean {
        var scope = apiService.captureActiveRemoteScope()
        if (scope == null) {
            apiService.resolveRemoteTarget()
            scope = apiService.captureActiveRemoteScope()
        }
        scope ?: return false

        val marker = settingsManager.getLegacyRemoteCacheState()
            as? LegacyRemoteCacheState.PendingRestoredOwner ?: return false
        if (!marker.canBeClaimedBy(scope.target.owner) || !apiService.isCurrentActiveRemoteScope(scope)) {
            return false
        }
        if (!convertGraph(scope)) return false
        if (!repairSelectedLibrary(scope)) return false
        if (!repairSlotWinner(scope)) return false
        return settingsManager.claimLegacyRemoteCacheStateIfCurrent(marker) { commit ->
            apiService.commitIfCurrentActiveRemoteScope(scope, commit)
        }
    }

    private suspend fun convertGraph(scope: ActiveRemoteScope): Boolean = database.withTransaction {
        val libraryDao = database.libraryDao()
        val audioBookDao = database.audioBookDao()
        val playbackProgressDao = database.playbackProgressDao()
        val downloadItemDao = database.downloadItemDao()
        val libraries = libraryDao.getAll()
        val rawLibraries = libraries.filter { it.isLocal == 0 && it.id.isRawLegacyId() }
        val scopedLibraries = mutableListOf<LibraryEntity>()
        for (library in rawLibraries) {
            val folders = rewriteLegacyFolderLibraryIds(library.foldersJson, library.id, scope.encodeIncoming(library.id))
            if (library.foldersJson != null && folders == null) return@withTransaction false
            scopedLibraries += library.copy(id = scope.encodeIncoming(library.id), foldersJson = folders)
        }
        val libraryIds = rawLibraries.mapTo(mutableSetOf()) { it.id }
        val books = audioBookDao.getAll()
        val rawBooks = books.filter {
            it.isLocal == 0 && it.id.isRawLegacyId() && it.libraryId in libraryIds
        }
        val scopedBooks = rawBooks.map {
            it.copy(id = scope.encodeIncoming(it.id), libraryId = scope.encodeIncoming(requireNotNull(it.libraryId)))
        }
        val bookIds = rawBooks.mapTo(mutableSetOf()) { it.id }
        val progress = playbackProgressDao.getAll()
        val rawProgress = progress.filter { it.audioBookId in bookIds }
        val scopedProgress = rawProgress.map { it.copy(audioBookId = scope.encodeIncoming(it.audioBookId)) }
        val downloads = downloadItemDao.getAll()
        val rawDownloads = downloads.filter { it.id.isRawLegacyId() && it.audioBookId in bookIds }
        val scopedDownloads = rawDownloads.map {
            it.copy(id = scope.encodeIncoming(it.id), audioBookId = scope.encodeIncoming(it.audioBookId))
        }

        if (hasDuplicateIds(scopedLibraries) { it.id } || hasDuplicateIds(scopedBooks) { it.id } ||
            hasDuplicateIds(scopedProgress) { it.audioBookId } || hasDuplicateIds(scopedDownloads) { it.id }
        ) return@withTransaction false
        for (library in scopedLibraries) if (libraryDao.getById(library.id) != null) return@withTransaction false
        for (book in scopedBooks) if (audioBookDao.getById(book.id) != null) return@withTransaction false
        for (item in scopedProgress) if (playbackProgressDao.getByAudioBookId(item.audioBookId) != null) return@withTransaction false
        for (download in scopedDownloads) if (downloadItemDao.getById(download.id) != null) return@withTransaction false

        for (library in scopedLibraries) libraryDao.upsert(library)
        for (book in scopedBooks) audioBookDao.upsert(book)
        for (item in scopedProgress) playbackProgressDao.upsert(item)
        for (download in scopedDownloads) downloadItemDao.upsert(download)
        for (download in rawDownloads) downloadItemDao.deleteById(download.id)
        for (item in rawProgress) playbackProgressDao.deleteByAudioBookId(item.audioBookId)
        for (book in rawBooks) audioBookDao.deleteById(book.id)
        for (library in rawLibraries) libraryDao.deleteById(library.id)
        true
    }

    private suspend fun repairSelectedLibrary(scope: ActiveRemoteScope): Boolean {
        val raw = settingsManager.currentSettings.selectedLibraryId ?: return true
        if (!raw.isRawLegacyId()) return true
        val replacement = scope.encodeIncoming(raw)
        if (database.libraryDao().getById(replacement) == null) return false
        return settingsManager.replaceSelectedLibraryIfCurrent(raw, replacement) { commit ->
            apiService.commitIfCurrentActiveRemoteScope(scope, commit)
        }.isSuccessfulRepair()
    }

    private suspend fun repairSlotWinner(scope: ActiveRemoteScope): Boolean {
        val raw = entitlementCachePrefs.slotWinnerAudioBookId ?: return true
        if (!raw.isRawLegacyId()) return true
        val replacement = scope.encodeIncoming(raw)
        if (database.audioBookDao().getById(replacement) == null) return false
        return entitlementCachePrefs.replaceSlotWinnerIfCurrent(raw, replacement) { commit ->
            apiService.commitIfCurrentActiveRemoteScope(scope, commit)
        }.isSuccessfulRepair()
    }

    private fun String.isRawLegacyId(): Boolean = !startsWith("nlr1:")

    private fun ScopedPreferenceWrite.isSuccessfulRepair(): Boolean =
        this == ScopedPreferenceWrite.APPLIED || this == ScopedPreferenceWrite.UNCHANGED

    private fun <T> hasDuplicateIds(items: List<T>, id: (T) -> String): Boolean =
        items.map(id).toSet().size != items.size
}
