package com.ninelivesaudio.app.widget

import android.content.Context
import android.content.Intent
import com.ninelivesaudio.app.MainActivity

/**
 * What a widget tap asks the app to do. Carried as two string extras, so a
 * launcher never needs to know the app's classes (plan Design delta 6).
 */
internal sealed interface WidgetLaunch {
    data object OpenApp : WidgetLaunch
    data class OpenBook(val bookId: String) : WidgetLaunch
    data class PlayBook(val bookId: String) : WidgetLaunch
    data object OpenUnlock : WidgetLaunch

    /** A tap on an empty sleeve. The trampoline finishes without launching anything. */
    data object Nothing : WidgetLaunch
}

internal const val EXTRA_WIDGET_ACTION = "com.ninelivesaudio.app.widget.ACTION"
internal const val EXTRA_WIDGET_BOOK_ID = "com.ninelivesaudio.app.widget.BOOK_ID"

internal fun WidgetLaunch.actionName(): String = when (this) {
    WidgetLaunch.OpenApp -> "open_app"
    is WidgetLaunch.OpenBook -> "open_book"
    is WidgetLaunch.PlayBook -> "play_book"
    WidgetLaunch.OpenUnlock -> "open_unlock"
    WidgetLaunch.Nothing -> "empty"
}

internal val WidgetLaunch.bookIdOrNull: String?
    get() = when (this) {
        is WidgetLaunch.OpenBook -> bookId
        is WidgetLaunch.PlayBook -> bookId
        else -> null
    }

/** Null for an unknown action, or a book action without a book. */
internal fun parseWidgetLaunch(action: String?, bookId: String?): WidgetLaunch? {
    val id = bookId?.takeIf { it.isNotBlank() }
    return when (action) {
        "open_app" -> WidgetLaunch.OpenApp
        "open_unlock" -> WidgetLaunch.OpenUnlock
        "empty" -> WidgetLaunch.Nothing
        "open_book" -> id?.let { WidgetLaunch.OpenBook(it) }
        "play_book" -> id?.let { WidgetLaunch.PlayBook(it) }
        else -> null
    }
}

/** The screen `MainActivity` opens for a widget launch. */
internal sealed interface WidgetRoute {
    data class Book(val bookId: String) : WidgetRoute
    data object Unlock : WidgetRoute
}

/**
 * Opening the app works like the launcher icon: the app comes forward where
 * it was left (Home on a cold start), so it never routes. Play never routes
 * either: the trampoline handles it and opens a page only on refusal.
 */
internal fun widgetRoute(launch: WidgetLaunch): WidgetRoute? = when (launch) {
    WidgetLaunch.OpenApp, WidgetLaunch.Nothing -> null
    is WidgetLaunch.OpenBook -> WidgetRoute.Book(launch.bookId)
    WidgetLaunch.OpenUnlock -> WidgetRoute.Unlock
    is WidgetLaunch.PlayBook -> null
}

internal fun Intent.putWidgetLaunch(launch: WidgetLaunch): Intent = apply {
    putExtra(EXTRA_WIDGET_ACTION, launch.actionName())
    launch.bookIdOrNull?.let { putExtra(EXTRA_WIDGET_BOOK_ID, it) }
}

internal fun Intent.widgetLaunchOrNull(): WidgetLaunch? =
    parseWidgetLaunch(getStringExtra(EXTRA_WIDGET_ACTION), getStringExtra(EXTRA_WIDGET_BOOK_ID))

/** Opens the app on the launch's screen, reusing a running `MainActivity`. */
internal fun widgetMainIntent(context: Context, launch: WidgetLaunch): Intent =
    Intent(context, MainActivity::class.java)
        .putWidgetLaunch(launch)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** The Binder's fill in template. Each cover adds its own extras. */
internal fun widgetActionIntent(context: Context): Intent =
    Intent(context, WidgetActionActivity::class.java)
