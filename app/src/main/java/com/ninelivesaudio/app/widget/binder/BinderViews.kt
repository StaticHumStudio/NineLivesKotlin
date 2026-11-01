package com.ninelivesaudio.app.widget.binder

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.ninelivesaudio.app.R
import com.ninelivesaudio.app.widget.WidgetCopy
import com.ninelivesaudio.app.widget.WidgetLaunch
import com.ninelivesaudio.app.widget.putWidgetLaunch
import com.ninelivesaudio.app.widget.widgetActionIntent
import com.ninelivesaudio.app.widget.widgetMainIntent
import android.appwidget.AppWidgetManager

/**
 * A Binder with nothing to flip through: the eye, the hinge, and one line.
 * Locked shows the lock and never any book data. The whole widget is one tap.
 */
internal fun binderMessageViews(
    context: Context,
    appWidgetId: Int,
    line: String,
    locked: Boolean,
    launch: WidgetLaunch,
): RemoteViews = RemoteViews(context.packageName, R.layout.widget_binder_message).apply {
    setTextViewText(R.id.binder_message, line)
    setViewVisibility(R.id.binder_lock, if (locked) View.VISIBLE else View.GONE)
    setContentDescription(R.id.binder_message_root, if (locked) WidgetCopy.CD_LOCKED else line)
    val pending = PendingIntent.getActivity(
        context,
        appWidgetId,
        widgetMainIntent(context, launch),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    setOnClickPendingIntent(android.R.id.background, pending)
}

/** A Binder with books: the StackView fed by [BinderRemoteViewsService], one factory per widget. */
internal fun binderStackViews(context: Context, appWidgetId: Int): RemoteViews =
    RemoteViews(context.packageName, R.layout.widget_binder).apply {
        val serviceIntent = Intent(context, BinderRemoteViewsService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            // A distinct data URI per widget, so two Binders never share a factory.
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        @Suppress("DEPRECATION") // The collection items API is API 31+, and the factory streams covers.
        setRemoteAdapter(R.id.binder_stack, serviceIntent)
        setEmptyView(R.id.binder_stack, R.id.binder_stack_empty)
        // Mutable is required so each sleeve's fill in intent can add its extras.
        // The intent is explicit, so nothing else can receive it.
        val template = PendingIntent.getActivity(
            context,
            appWidgetId,
            widgetActionIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        setPendingIntentTemplate(R.id.binder_stack, template)
    }

/** What fills one sleeve. */
internal sealed interface SleeveFill {
    data object Icon : SleeveFill
    data class Cover(val bitmap: Bitmap, val title: String, val launch: WidgetLaunch) : SleeveFill
    data class TitleOnly(val title: String, val launch: WidgetLaunch) : SleeveFill
    data object Empty : SleeveFill
}

/** One spread in the stack. */
internal fun binderSpreadViews(context: Context, left: SleeveFill, right: SleeveFill): RemoteViews =
    RemoteViews(context.packageName, R.layout.widget_binder_spread).apply {
        fill(this, left, R.id.binder_left, R.id.binder_left_cover, R.id.binder_left_title, context)
        fill(this, right, R.id.binder_right, R.id.binder_right_cover, R.id.binder_right_title, context)
    }

private fun fill(views: RemoteViews, fill: SleeveFill, sleeve: Int, cover: Int, title: Int, context: Context) {
    val padPx = context.resources.getDimensionPixelSize(R.dimen.widget_eye_padding)
    when (fill) {
        SleeveFill.Icon -> {
            views.setInt(sleeve, "setBackgroundResource", R.drawable.widget_sleeve)
            views.setViewVisibility(cover, View.VISIBLE)
            views.setImageViewResource(cover, R.drawable.widget_eye)
            views.setViewPadding(cover, padPx, padPx, padPx, padPx)
            views.setViewVisibility(title, View.GONE)
            views.setContentDescription(sleeve, WidgetCopy.CD_OPEN_APP)
            views.setOnClickFillInIntent(sleeve, Intent().putWidgetLaunch(WidgetLaunch.OpenApp))
        }
        is SleeveFill.Cover -> {
            views.setInt(sleeve, "setBackgroundResource", R.drawable.widget_sleeve)
            views.setViewVisibility(cover, View.VISIBLE)
            views.setImageViewBitmap(cover, fill.bitmap)
            views.setViewPadding(cover, 0, 0, 0, 0)
            views.setViewVisibility(title, View.GONE)
            views.setContentDescription(sleeve, fill.title)
            views.setOnClickFillInIntent(sleeve, Intent().putWidgetLaunch(fill.launch))
        }
        is SleeveFill.TitleOnly -> {
            views.setInt(sleeve, "setBackgroundResource", R.drawable.widget_sleeve_plain)
            views.setViewVisibility(cover, View.GONE)
            views.setViewVisibility(title, View.VISIBLE)
            views.setTextViewText(title, fill.title)
            views.setContentDescription(sleeve, fill.title)
            views.setOnClickFillInIntent(sleeve, Intent().putWidgetLaunch(fill.launch))
        }
        SleeveFill.Empty -> {
            views.setInt(sleeve, "setBackgroundResource", R.drawable.widget_sleeve_empty)
            views.setViewVisibility(cover, View.GONE)
            views.setViewVisibility(title, View.GONE)
            views.setContentDescription(sleeve, null)
        }
    }
}
