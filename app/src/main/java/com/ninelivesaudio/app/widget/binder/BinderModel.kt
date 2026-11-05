package com.ninelivesaudio.app.widget.binder

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.widget.WidgetAccess
import com.ninelivesaudio.app.widget.WidgetBookSource
import com.ninelivesaudio.app.widget.WidgetContent
import com.ninelivesaudio.app.widget.widgetContent

/**
 * Everything one Binder draws. [books] is empty unless [content] is READY.
 * Pages are cut from [books] at draw time, because how many pockets a page
 * holds depends on the widget's size (see [binderLayoutFor]).
 */
internal data class BinderModel(
    val content: WidgetContent,
    val isLocalMode: Boolean,
    val books: List<AudioBook>,
)

/**
 * Builds a Binder. Locked, signed out and no library return before any read.
 * A local library checks its folder grant before it reads the shelf.
 */
internal suspend fun loadBinderModel(
    access: WidgetAccess,
    config: BinderConfig,
    source: WidgetBookSource,
): BinderModel {
    val library = access as? WidgetAccess.Library
        ?: return BinderModel(
            content = widgetContent(access, folderAccessible = true, bookCount = 0, playedCount = 0, needsPlayed = false),
            isLocalMode = (access as? WidgetAccess.NoLibrary)?.isLocalMode ?: false,
            books = emptyList(),
        )
    if (library.isLocalMode && !source.folderAccessible(library.libraryId)) {
        return BinderModel(WidgetContent.FOLDER_LOST, true, emptyList())
    }
    val books = binderBooks(source.shelf(library.libraryId), config.sort)
    val content = widgetContent(access, folderAccessible = true, bookCount = books.size, playedCount = 0, needsPlayed = false)
    return BinderModel(
        content = content,
        isLocalMode = library.isLocalMode,
        books = if (content == WidgetContent.READY) books else emptyList(),
    )
}
