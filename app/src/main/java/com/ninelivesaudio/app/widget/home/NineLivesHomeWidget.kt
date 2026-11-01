package com.ninelivesaudio.app.widget.home

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ninelivesaudio.app.R
import com.ninelivesaudio.app.ui.theme.NoirColors
import com.ninelivesaudio.app.widget.WidgetContent
import com.ninelivesaudio.app.widget.WidgetCopy
import com.ninelivesaudio.app.widget.WidgetLaunch
import com.ninelivesaudio.app.widget.widgetActionIntent
import com.ninelivesaudio.app.widget.widgetMainIntent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Nine Lives, the large home screen widget: Home's rank card, the current
 * book with play and pause, and the next Lives. Always the Noir palette
 * (plan Design delta 10). The model and covers come from [HomeWidgetStore],
 * so drawing never reads the database and never touches the network.
 *
 * Every element that can be tapped in some state is given its action on
 * every draw. A launcher can reapply a new draw onto the old views, and a
 * view left without an action keeps the one it had.
 */
class NineLivesHomeWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun homeWidgetStore(): HomeWidgetStore
    }

    override val sizeMode: SizeMode =
        SizeMode.Responsive(HOME_WIDGET_SIZES.map { (w, h) -> DpSize(w.dp, h.dp) }.toSet())

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).homeWidgetStore()
        if (store.state.value == null) store.refresh()
        provideContent {
            val snapshot by store.state.collectAsState()
            HomeWidgetFace(snapshot)
        }
    }
}

private val textPrimary = ColorProvider(NoirColors.archiveTextPrimary)
private val textSecondary = ColorProvider(NoirColors.archiveTextSecondary)
private val textMuted = ColorProvider(NoirColors.archiveTextMuted)
private val gold = ColorProvider(NoirColors.goldFilament)

@Composable
private fun HomeWidgetFace(snapshot: HomeWidgetSnapshot?) {
    val context = LocalContext.current
    val size = LocalSize.current
    val layout = homeLayoutFor(size.width.value, size.height.value)
    val model = snapshot?.model
    val rootLaunch = if (model?.content == WidgetContent.LOCKED) WidgetLaunch.OpenUnlock else WidgetLaunch.OpenApp
    val rootLabel = when (model?.content) {
        WidgetContent.LOCKED -> WidgetCopy.CD_LOCKED
        null, WidgetContent.READY -> WidgetCopy.CD_OPEN_APP
        else -> WidgetCopy.emptyLine(model.content, model.isLocalMode) ?: WidgetCopy.CD_OPEN_APP
    }
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(ImageProvider(R.drawable.widget_frame))
            .cornerRadius(16.dp)
            .clickable(actionStartActivity(widgetMainIntent(context, rootLaunch)))
            .semantics { contentDescription = rootLabel }
            .padding(if (layout == HomeLayout.CURRENT_ONLY) 10.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            model == null -> Message(line = null, locked = false, layout = layout)
            model.content == WidgetContent.LOCKED -> Message(WidgetCopy.HOME_LOCKED, locked = true, layout = layout)
            model.content != WidgetContent.READY -> Message(WidgetCopy.emptyLine(model.content, model.isLocalMode), locked = false, layout = layout)
            else -> Ready(model, snapshot.art, layout, size.width.value, isTallCurrentOnly(size.width.value, size.height.value))
        }
    }
}

/** Locked, signed out, empty: the eye and one calm line. Never a book, a cover or a rank. */
@Composable
private fun Message(line: String?, locked: Boolean, layout: HomeLayout) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(R.drawable.widget_eye),
            contentDescription = null,
            modifier = GlanceModifier.size(if (layout == HomeLayout.CURRENT_ONLY) 40.dp else 56.dp),
        )
        if (line != null) {
            Spacer(GlanceModifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (locked) {
                    Image(
                        provider = ImageProvider(R.drawable.widget_lock),
                        contentDescription = null,
                        modifier = GlanceModifier.size(16.dp),
                    )
                    Spacer(GlanceModifier.width(6.dp))
                }
                Text(
                    text = line,
                    maxLines = 3,
                    style = TextStyle(color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
                )
            }
        }
        if (locked && layout == HomeLayout.FULL) {
            // An altar waiting for its Lives: empty sleeves, generic art only.
            Spacer(GlanceModifier.height(14.dp))
            Row {
                repeat(3) { i ->
                    if (i > 0) Spacer(GlanceModifier.width(10.dp))
                    Image(
                        provider = ImageProvider(R.drawable.widget_sleeve_empty),
                        contentDescription = null,
                        modifier = GlanceModifier.size(40.dp),
                    )
                }
            }
        }
    }
}

/**
 * The rank card anchors to the top and the Lives to the bottom, with the
 * current book between them, and equal weighted gaps take whatever height the
 * launcher gives. Without Lives the current book sits in the middle of the
 * space under the rank card. Only the one row face stays centered.
 */
@Composable
private fun Ready(model: HomeWidgetModel, art: HomeWidgetArt, layout: HomeLayout, widthDp: Float, tall: Boolean) {
    val context = LocalContext.current
    if (layout == HomeLayout.CURRENT_ONLY) {
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = if (tall) Alignment.Top else Alignment.CenterVertically,
        ) {
            model.current?.let {
                if (tall) CurrentBookStacked(it, art.current) else CurrentBook(it, art.current, compact = true)
            }
        }
        return
    }
    Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.Top) {
        model.rank?.let { RankSlab(it, actionStartActivity(widgetMainIntent(context, WidgetLaunch.OpenApp))) }
        Spacer(GlanceModifier.defaultWeight())
        model.current?.let { CurrentBook(it, art.current, compact = false) }
        Spacer(GlanceModifier.defaultWeight())
        val slots = livesSlotsFor(widthDp)
        val lives = model.lives.take(slots)
        if (layout == HomeLayout.FULL && lives.isNotEmpty()) {
            Image(
                provider = ImageProvider(R.drawable.widget_home_rule),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxWidth().height(1.dp),
            )
            Spacer(GlanceModifier.height(LIFE_GAP_DP.dp))
            // Every slot is laid out, the first and last flush with the edges and
            // equal weighted gaps between, so the tiles span the full width at any
            // real width. A slot without a Life stays empty, as on Home's grid.
            val tile = lifeTileDp(widthDp, slots).toInt()
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                repeat(slots) { i ->
                    if (i > 0) Spacer(GlanceModifier.defaultWeight())
                    val life = lives.getOrNull(i)
                    if (life != null) {
                        Cover(
                            bitmap = art.lives[life.bookId],
                            label = life.title,
                            sizeDp = tile,
                            action = openBook(context, life.bookId),
                        )
                    } else {
                        Spacer(GlanceModifier.size(tile.dp))
                    }
                }
            }
        }
    }
}

/** Home's altar card, small: the eye, the epithet, and the total time given. Opens Home. */
@Composable
private fun RankSlab(rank: RankCard, action: Action) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(ImageProvider(R.drawable.widget_home_slab))
            .cornerRadius(12.dp)
            .clickable(action)
            .semantics { contentDescription = rank.epithet + ". " + rank.totalTimeText }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(R.drawable.widget_eye),
            contentDescription = null,
            modifier = GlanceModifier.size(40.dp),
        )
        Spacer(GlanceModifier.width(10.dp))
        Column {
            Text(
                text = rank.epithet,
                maxLines = 1,
                style = TextStyle(color = textMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = rank.totalTimeText,
                maxLines = 1,
                style = TextStyle(color = gold, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            )
        }
    }
}

@Composable
private fun CurrentBook(current: CurrentCard, cover: android.graphics.Bitmap?, compact: Boolean) {
    val context = LocalContext.current
    val open = openBook(context, current.bookId)
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Cover(bitmap = cover, label = current.title, sizeDp = if (compact) 48 else 64, action = open)
        Spacer(GlanceModifier.width(if (compact) 8.dp else 10.dp))
        Column(
            modifier = GlanceModifier.defaultWeight().clickable(open),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = current.title,
                maxLines = 2,
                style = TextStyle(color = textPrimary, fontSize = if (compact) 13.sp else 14.sp, fontWeight = FontWeight.Medium),
            )
            current.chapterTitle?.let {
                Spacer(GlanceModifier.height(2.dp))
                Text(text = it, maxLines = 1, style = TextStyle(color = textSecondary, fontSize = 12.sp))
            }
        }
        Spacer(GlanceModifier.width(if (compact) 6.dp else 8.dp))
        PlayButton(current, open, if (compact) 40 else 44)
    }
}

/** A 2x2: cover and play side by side at the top, the title and chapter anchored to the bottom. */
@Composable
private fun CurrentBookStacked(current: CurrentCard, cover: android.graphics.Bitmap?) {
    val context = LocalContext.current
    val open = openBook(context, current.bookId)
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Cover(bitmap = cover, label = current.title, sizeDp = 72, action = open)
            Spacer(GlanceModifier.defaultWeight())
            PlayButton(current, open, 44)
        }
        Spacer(GlanceModifier.height(10.dp))
        Spacer(GlanceModifier.defaultWeight())
        Column(modifier = GlanceModifier.fillMaxWidth().clickable(open)) {
            Text(
                text = current.title,
                maxLines = 2,
                style = TextStyle(color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
            current.chapterTitle?.let {
                Spacer(GlanceModifier.height(2.dp))
                Text(text = it, maxLines = 1, style = TextStyle(color = textSecondary, fontSize = 12.sp))
            }
        }
    }
}

@Composable
private fun PlayButton(current: CurrentCard, open: Action, sizeDp: Int) {
    val context = LocalContext.current
    val (icon, label, action) = when (current.button) {
        CurrentButton.PLAY -> Triple(R.drawable.widget_home_play, WidgetCopy.CD_PLAY, toggle(context, current.bookId))
        CurrentButton.PAUSE -> Triple(R.drawable.widget_home_pause, WidgetCopy.CD_PAUSE, toggle(context, current.bookId))
        // Loading: the app ignores a tap now, and so does this one (the plan says IGNORE),
        // but the button still carries an action so it never keeps an old one.
        CurrentButton.WAIT -> Triple(R.drawable.widget_home_wait, WidgetCopy.CD_PLAY, toggle(context, current.bookId))
        CurrentButton.OPEN -> Triple(R.drawable.widget_home_open, current.title, open)
    }
    Image(
        provider = ImageProvider(icon),
        contentDescription = label,
        modifier = GlanceModifier.size(sizeDp.dp).clickable(action),
    )
}

/**
 * A square cover, fitted whole: the bitmap is already square with its
 * corners rounded on the widget's own surface colour (WidgetCoverLoader), so
 * nothing is cropped and the corners match on every API level.
 */
@Composable
private fun Cover(bitmap: android.graphics.Bitmap?, label: String, sizeDp: Int, action: Action) {
    Image(
        provider = bitmap?.let { ImageProvider(it) } ?: ImageProvider(R.drawable.widget_sleeve_empty),
        contentDescription = label,
        contentScale = ContentScale.Fit,
        modifier = GlanceModifier.size(sizeDp.dp).clickable(action),
    )
}

/** Open its page goes through the trampoline, which drops a stale tap from another library. */
private fun openBook(context: Context, bookId: String): Action =
    actionStartActivity(widgetActionIntent(context, WidgetLaunch.OpenBook(bookId)))

/** Play or pause, through the trampoline that holds until the service is in the foreground. */
private fun toggle(context: Context, bookId: String): Action =
    actionStartActivity(widgetActionIntent(context, WidgetLaunch.TogglePlay(bookId)))
