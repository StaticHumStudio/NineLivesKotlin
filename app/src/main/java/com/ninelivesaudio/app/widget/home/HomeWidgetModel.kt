package com.ninelivesaudio.app.widget.home

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Chapter
import com.ninelivesaudio.app.service.PlaybackState
import com.ninelivesaudio.app.ui.home.AltarCopy
import com.ninelivesaudio.app.ui.home.RECENT_LIVES_LIMIT
import com.ninelivesaudio.app.ui.home.formatListeningTime
import com.ninelivesaudio.app.ui.home.totalTimeGivenSeconds
import com.ninelivesaudio.app.widget.WidgetAccess
import com.ninelivesaudio.app.widget.WidgetBookSource
import com.ninelivesaudio.app.widget.WidgetContent
import com.ninelivesaudio.app.widget.widgetContent
import kotlin.time.DurationUnit

/** The current book's button. WAIT is a dimmed play while a book loads, like the app. */
internal enum class CurrentButton { PLAY, PAUSE, WAIT, OPEN }

/** Home's altar card, the same words Home shows. */
internal data class RankCard(val epithet: String, val totalTimeText: String)

internal data class CurrentCard(
    val bookId: String,
    val title: String,
    val chapterTitle: String?,
    val button: CurrentButton,
)

internal data class LifeTile(val bookId: String, val title: String)

/**
 * Everything the Nine Lives widget draws, apart from cover art. Anything but
 * READY carries no rank, book or Lives, so a locked or empty widget cannot
 * show a stale one.
 */
internal data class HomeWidgetModel(
    val content: WidgetContent,
    val isLocalMode: Boolean,
    val rank: RankCard?,
    val current: CurrentCard?,
    val lives: List<LifeTile>,
)

/** The library reads one widget update makes. */
internal data class HomeWidgetInputs(
    val folderAccessible: Boolean,
    val bookCount: Int,
    val recent: List<AudioBook>,
)

/** The chapter playing at [positionSeconds]. Past the end is the last chapter. */
internal fun chapterTitleAt(chapters: List<Chapter>, positionSeconds: Double): String? {
    if (chapters.isEmpty()) return null
    val sorted = chapters.sortedBy { it.start }
    return (sorted.lastOrNull { it.start <= positionSeconds } ?: sorted.first()).title
}

/**
 * Builds the widget. The shown book is the one in the player, else the most
 * recently played. The rank card is Home's: the same nine recent books, the
 * same sum, the same words (plan Design delta 7).
 */
internal fun buildHomeWidgetModel(
    access: WidgetAccess,
    folderAccessible: Boolean,
    bookCount: Int,
    recent: List<AudioBook>,
    current: AudioBook?,
    liveChapterTitle: String?,
    state: PlaybackState,
    bookLoading: Boolean,
    shownPlayable: Boolean,
): HomeWidgetModel {
    val content = widgetContent(
        access,
        folderAccessible,
        bookCount,
        playedCount = recent.size + (if (current != null) 1 else 0),
        needsPlayed = true,
    )
    val isLocalMode = when (access) {
        is WidgetAccess.Library -> access.isLocalMode
        is WidgetAccess.NoLibrary -> access.isLocalMode
        else -> false
    }
    if (content != WidgetContent.READY) return HomeWidgetModel(content, isLocalMode, null, null, emptyList())

    val total = totalTimeGivenSeconds(recent.map { it.currentTime.toDouble(DurationUnit.SECONDS) })
    val rank = RankCard(AltarCopy.epithet(total), "Total time given: " + formatListeningTime(total))

    val shown = current ?: recent.first()
    val chapter = if (current != null) {
        liveChapterTitle
    } else {
        chapterTitleAt(shown.chapters, shown.currentTime.toDouble(DurationUnit.SECONDS))
    }?.takeIf { it.isNotBlank() }
    val audible = state == PlaybackState.PLAYING || state == PlaybackState.BUFFERING
    val button = when {
        bookLoading || state == PlaybackState.LOADING -> CurrentButton.WAIT
        current != null && audible -> CurrentButton.PAUSE
        current != null -> CurrentButton.PLAY
        !shownPlayable -> CurrentButton.OPEN
        else -> CurrentButton.PLAY
    }
    val lives = recent.filter { it.id != shown.id }.take(MAX_LIVES).map { LifeTile(it.id, it.title) }
    return HomeWidgetModel(
        content = content,
        isLocalMode = isLocalMode,
        rank = rank,
        current = CurrentCard(shown.id, shown.title, chapter, button),
        lives = lives,
    )
}

/**
 * The reads for one update, for the library [access] chose. Locked, signed
 * out and no library read nothing at all. A local library checks its folder
 * grant before it reads any book.
 */
internal suspend fun loadHomeWidgetInputs(access: WidgetAccess, source: WidgetBookSource): HomeWidgetInputs {
    val library = access as? WidgetAccess.Library ?: return HomeWidgetInputs(false, 0, emptyList())
    if (library.isLocalMode && !source.folderAccessible(library.libraryId)) {
        return HomeWidgetInputs(false, 0, emptyList())
    }
    return HomeWidgetInputs(
        folderAccessible = true,
        bookCount = source.countBooks(library.libraryId),
        recent = source.recent(library.libraryId, RECENT_LIVES_LIMIT),
    )
}
