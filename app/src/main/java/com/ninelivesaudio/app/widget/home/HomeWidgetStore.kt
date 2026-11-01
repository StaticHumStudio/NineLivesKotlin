package com.ninelivesaudio.app.widget.home

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.isInActiveLibrary
import com.ninelivesaudio.app.entitlement.EffectiveSettingsRepository
import com.ninelivesaudio.app.service.PlaybackManager
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.widget.HOME_CURRENT_COVER_PX
import com.ninelivesaudio.app.widget.HOME_LIFE_COVER_PX
import com.ninelivesaudio.app.widget.WidgetAccess
import com.ninelivesaudio.app.widget.WidgetBookSource
import com.ninelivesaudio.app.widget.WidgetContent
import com.ninelivesaudio.app.widget.WidgetCoverLoader
import com.ninelivesaudio.app.widget.WidgetPlayback
import com.ninelivesaudio.app.widget.widgetAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/** Cover art for one draw. One bitmap per cover, shared by every size Glance lays out. */
internal data class HomeWidgetArt(val current: Bitmap?, val lives: Map<String, Bitmap>)

internal data class HomeWidgetSnapshot(val model: HomeWidgetModel, val art: HomeWidgetArt)

/**
 * What the Nine Lives widget shows, kept in process for Glance to draw.
 * [refresh] runs on the refresher's pushes and when a widget session starts
 * cold. It never triggers a push itself, so it cannot loop.
 */
@Singleton
class HomeWidgetStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsManager: SettingsManager,
    private val effectiveSettings: EffectiveSettingsRepository,
    private val source: WidgetBookSource,
    private val playbackManager: PlaybackManager,
    private val audioBookRepository: AudioBookRepository,
    private val widgetPlayback: WidgetPlayback,
    private val coverLoader: WidgetCoverLoader,
) {
    private val _state = MutableStateFlow<HomeWidgetSnapshot?>(null)
    internal val state: StateFlow<HomeWidgetSnapshot?> = _state.asStateFlow()

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var artJob: Job? = null
    private var generation = 0L

    /**
     * Reads everything again. The access check comes first, and a locked,
     * signed out or empty widget reads no books and no covers. Covers on disk
     * draw at once. Missing ones download after, bounded and cancelled by the
     * next refresh, and redraw the widget only if one arrived.
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        lock.withLock {
            artJob?.cancel()
            val mine = ++generation
            withTimeoutOrNull(5.seconds) { settingsManager.isLoaded.first { it } }
            val settings = settingsManager.currentSettings
            val access = widgetAccess(
                isUnlocked = effectiveSettings.isUnlockedNow,
                settings = settings,
                hasAuthToken = settingsManager.hasAuthToken.value,
            )
            val inputs = loadHomeWidgetInputs(access, source)
            val library = access as? WidgetAccess.Library

            // The player's book, else the one the app will restore, never another library's.
            val playerBook = playbackManager.currentBook.value
                ?.takeIf { library != null && it.libraryId == library.libraryId && it.isInActiveLibrary(settings) }
            val current = playerBook ?: if (library != null && inputs.folderAccessible) {
                settingsManager.getCurrentPlaybackBookId()
                    ?.let { audioBookRepository.getById(it) }
                    ?.takeIf { it.libraryId == library.libraryId && it.isInActiveLibrary(settings) && !it.isArchived }
            } else {
                null
            }
            val liveChapter = if (playerBook != null) {
                playbackManager.currentChapter.value?.title
            } else {
                current?.let { chapterTitleAt(it.chapters, it.currentTime.toDouble(DurationUnit.SECONDS)) }
            }
            val shownPlayable = if (current == null && library != null) {
                inputs.recent.firstOrNull()?.let { widgetPlayback.isPlayable(it, settle = false) } ?: false
            } else {
                true
            }
            val model = buildHomeWidgetModel(
                access = access,
                folderAccessible = inputs.folderAccessible,
                bookCount = inputs.bookCount,
                recent = inputs.recent,
                current = current,
                liveChapterTitle = liveChapter,
                state = playbackManager.playbackState.value,
                bookLoading = playbackManager.loadingBook.value != null,
                shownPlayable = shownPlayable,
            )
            if (model.content != WidgetContent.READY) {
                publish(HomeWidgetSnapshot(model, HomeWidgetArt(null, emptyMap())))
                return@withLock
            }

            val byId = (inputs.recent + listOfNotNull(current)).associateBy { it.id }
            val wanted = buildList {
                model.current?.let { c -> byId[c.bookId]?.let { add(it to HOME_CURRENT_COVER_PX) } }
                model.lives.forEach { l -> byId[l.bookId]?.let { add(it to HOME_LIFE_COVER_PX) } }
            }
            val cached = wanted.associate { (book, px) -> book.id to coverLoader.cached(book, px) }
            publish(HomeWidgetSnapshot(model, art(model, cached)))

            val missing = wanted.filter { (book, _) -> cached[book.id] == null && book.effectiveCoverPath != null }
            if (missing.isNotEmpty()) {
                artJob = scope.launch { fetchMissing(mine, model, cached, missing) }
            }
        }
    }

    private suspend fun fetchMissing(
        mine: Long,
        model: HomeWidgetModel,
        cached: Map<String, Bitmap?>,
        missing: List<Pair<AudioBook, Int>>,
    ) {
        val fetched = withTimeoutOrNull(COVER_FETCH_DEADLINE) {
            missing.map { (book, px) ->
                async {
                    try {
                        book.id to coverLoader.load(book, px)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "cover failed ${book.id}: ${e.message}")
                        book.id to null
                    }
                }
            }.awaitAll()
        }.orEmpty().filter { it.second != null }
        if (fetched.isEmpty()) return
        lock.withLock {
            if (generation != mine) return
            publish(HomeWidgetSnapshot(model, art(model, cached + fetched)))
        }
        // A running Glance session redraws from the state flow. One that has
        // closed is started again, and its cold start finds the covers on disk.
        runCatching { NineLivesHomeWidget().updateAll(context) }
            .onFailure { Log.w(TAG, "redraw after covers failed: ${it.message}") }
    }

    private fun art(model: HomeWidgetModel, covers: Map<String, Bitmap?>) = HomeWidgetArt(
        current = model.current?.let { covers[it.bookId] },
        lives = model.lives.mapNotNull { l -> covers[l.bookId]?.let { l.bookId to it } }.toMap(),
    )

    private fun publish(snapshot: HomeWidgetSnapshot) {
        Log.d(TAG, "state ${snapshot.model.content} current=${snapshot.model.current?.button} lives=${snapshot.model.lives.size} art=${snapshot.art.current != null}/${snapshot.art.lives.size}")
        _state.value = snapshot
    }

    private companion object {
        const val TAG = "NineLivesHomeWidget"
        val COVER_FETCH_DEADLINE = 8.seconds
    }
}
