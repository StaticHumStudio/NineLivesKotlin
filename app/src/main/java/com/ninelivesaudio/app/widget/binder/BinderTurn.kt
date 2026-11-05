package com.ninelivesaudio.app.widget.binder

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/*
 * The page turn arrows. Each page carries its own arrows, and each arrow
 * knows the page it turns to, because only the page knows where the stack is:
 * a fling turns the stack inside the launcher and the app never hears of it.
 *
 * RemoteViews.showNext and showPrevious would be the obvious way, but Android
 * drops them from a partial update (the framework merges a partial update
 * into the widget's saved views and ignores those two actions), so they never
 * reach the launcher. An arrow instead goes through the widget's tap template
 * (the only click a StackView page can carry) to WidgetActionActivity, and the
 * Binder redraws with setDisplayedChild on the arrow's target page.
 */

/** The page an arrow tap turns its Binder to. */
internal const val EXTRA_BINDER_TURN_TO = "com.ninelivesaudio.app.widget.binder.TURN_TO"

/** The fill in an arrow on a page carries. */
internal fun binderTurnFillIn(appWidgetId: Int, page: Int): Intent = Intent()
    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    .putExtra(EXTRA_BINDER_TURN_TO, page)

/** A page turn read back from the tap, or null when the tap is not one. */
internal data class BinderTurn(val appWidgetId: Int, val page: Int)

/** Null unless [hasTurn], with a real widget id and a page that is not negative. */
internal fun binderTurnOf(hasTurn: Boolean, appWidgetId: Int, page: Int): BinderTurn? =
    if (hasTurn && appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && page >= 0) BinderTurn(appWidgetId, page) else null

/** Copy only the Binder uses. Draft, pending Jeff. */
internal object BinderCopy {
    const val CD_PREVIOUS = "Previous page"
    const val CD_NEXT = "Next page"

    val all: List<String> = listOf(CD_PREVIOUS, CD_NEXT)
}

/**
 * Called first by WidgetActionActivity. True when the tap was an arrow: the
 * Binder turns, then [activity] finishes. A cold process waits for settings
 * before it draws, so the activity stays up (invisible, passing touches
 * through) until the redraw is sent, capped at a few seconds.
 */
internal fun <A> handleBinderPageTurn(activity: A, intent: Intent): Boolean where A : Activity, A : LifecycleOwner {
    val hasTurn = intent.hasExtra(EXTRA_BINDER_TURN_TO)
    if (!hasTurn) return false
    val turn = binderTurnOf(
        hasTurn,
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
        intent.getIntExtra(EXTRA_BINDER_TURN_TO, -1),
    )
    if (turn == null) {
        activity.finish()
        return true
    }
    val updater = EntryPointAccessors
        .fromApplication(activity.applicationContext, BinderWidgetProvider.Deps::class.java)
        .binderUpdater()
    activity.lifecycleScope.launch {
        try {
            // Longer than the updater's own 5 s wait for settings, so a slow cold start still turns.
            withTimeoutOrNull(8.seconds) { updater.turnTo(turn.appWidgetId, turn.page) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w("BinderWidget", "page turn failed: ${e.message}")
        } finally {
            activity.finish()
        }
    }
    return true
}
