package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.local.dao.LocalListeningSessionDao
import com.ninelivesaudio.app.data.local.entity.LocalListeningSessionEntity
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.ListeningSession
import com.ninelivesaudio.app.service.SettingsManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Single source of listening sessions for the Nightwatch Dossier.
 *
 * In LOCAL mode, reads from the local LocalListeningSessions table populated by
 * PlaybackManager during scanned-library playback.
 *
 * In AUDIOBOOKSHELF mode, delegates to ApiService which paginates server sessions.
 *
 * Write methods (start/update) are LOCAL-only — server sessions are owned by the
 * Audiobookshelf backend via ApiService.startPlaybackSession / syncSessionProgress.
 */
@Singleton
class ListeningSessionRepository @Inject constructor(
    private val sessionDao: LocalListeningSessionDao,
    private val apiService: ApiService,
    private val settingsManager: SettingsManager,
) {
    suspend fun getAllSessions(): List<ListeningSession> {
        return if (settingsManager.currentSettings.appMode == AppMode.LOCAL) {
            sessionDao.getAll().map { it.toDomain() }
        } else {
            apiService.getAllListeningSessions()
        }
    }

    /**
     * Sessions scoped to a single book — backs the Book Detail "Listening history"
     * panel. LOCAL mode reads from Room; AUDIOBOOKSHELF mode hits the paginated
     * server endpoint that filters by libraryItemId.
     */
    suspend fun getSessionsForBook(audioBookId: String): List<ListeningSession> {
        return if (settingsManager.currentSettings.appMode == AppMode.LOCAL) {
            sessionDao.getByAudioBookId(audioBookId).map { it.toDomain() }
        } else {
            apiService.getListeningSessions(audioBookId)
        }
    }

    /**
     * Insert a new local-mode session row. Returns the row id so callers can
     * accumulate timeListening / currentTime against it on each playback heartbeat.
     */
    suspend fun startLocalSession(
        audioBookId: String,
        libraryId: String,
        displayTitle: String?,
        startPositionSec: Double,
    ): Long {
        val now = System.currentTimeMillis()
        return sessionDao.insert(
            LocalListeningSessionEntity(
                audioBookId = audioBookId,
                libraryId = libraryId,
                startedAt = now,
                updatedAt = now,
                timeListening = 0.0,
                currentTime = startPositionSec,
                displayTitle = displayTitle,
            )
        )
    }

    suspend fun updateLocalSession(
        id: Long,
        timeListeningSec: Double,
        currentTimeSec: Double,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        sessionDao.updateProgress(
            id = id,
            timeListening = timeListeningSec,
            currentTime = currentTimeSec,
            updatedAt = updatedAt,
        )
    }

    private fun LocalListeningSessionEntity.toDomain(): ListeningSession = ListeningSession(
        id = "local-$id",
        libraryItemId = audioBookId,
        currentTime = currentTime.seconds,
        timeListening = timeListening.seconds,
        startedAt = startedAt,
        updatedAt = updatedAt,
        displayTitle = displayTitle,
    )
}
