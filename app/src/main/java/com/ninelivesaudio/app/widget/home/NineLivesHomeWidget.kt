package com.ninelivesaudio.app.widget.home

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
 * Nine Lives, the large home screen widget: a small Home screen. Home's altar
 * card, the Lives grid with the current book as LIFE I carrying play and
 * pause, and the title and chapter where Home has its mini player. Always the
 * Noir palette (plan Design delta 10). The model and covers come from
 * [HomeWidgetStore], so drawing never reads the database and never touches
 * the network.
 *
 * SizeMode.Exact, so the face is laid out for the launcher's real size
 * ([homePlanFor]) instead of being stretched from the nearest bucket.
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

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).homeWidgetStore()
        if (store.state.value == null) store.refresh()
        provideContent {
            val snapshot by store.state.collectAsState()
            HomeWidgetFace(snapshot)
        }
    }
}

private const val TAG = "NineLivesHomeWidget"

private val textPrimary = ColorProvider(NoirColors.archiveTextPrimary)
private val textSecondary = ColorProvider(NoirColors.archiveTextSecondary)
private val textMuted = ColorProvider(NoirColors.archiveTextMuted)
private val gold = ColorProvider(NoirColors.goldFilament)

@Composable
private fun HomeWidgetFace(snapshot: HomeWidgetSnapshot?) {
    val context = LocalContext.current
    val size = LocalSize.current
    val model = snapshot?.model
    val rootLaunch = if (model?.content == WidgetContent.LOCKED) WidgetLaunch.OpenUnlock else WidgetLaunch.OpenApp
    val rootLabel = when (model?.content) {
        WidgetContent.LOCKED -> WidgetCopy.CD_LOCKED
        null, WidgetContent.READY -> WidgetCopy.CD_OPEN_APP
        else -> WidgetCopy.emptyLine(model.content, model.isLocalMode) ?: WidgetCopy.CD_OPEN_APP
    }
    val tiles = when (model?.content) {
        WidgetContent.READY -> (if (model.current != null) 1 else 0) + model.lives.size
        // The locked face lays out a full grid of empty Lives, the same plan a full history gets.
        else -> MAX_LIFE_ROWS * LIFE_COLUMNS
    }
    val plan = homePlanFor(size.width.value, size.height.value, tiles)
    Log.d(TAG, "face ${size.width.value}x${size.height.value} tiles=$tiles $plan")
    val rootAction = actionStartActivity(widgetMainIntent(context, rootLaunch))
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(ImageProvider(R.drawable.widget_home_frame))
            .cornerRadius(16.dp)
            .clickable(rootAction)
            .semantics { contentDescription = rootLabel }
            .padding(HOME_PAD_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            model == null -> Message(line = null, locked = false, plan = plan, action = rootAction)
            model.content == WidgetContent.LOCKED && plan.face == HomeFace.STACKED -> LockedFace(plan, rootAction)
            model.content == WidgetContent.LOCKED -> Message(WidgetCopy.HOME_LOCKED, locked = true, plan = plan, action = rootAction)
            model.content != WidgetContent.READY -> Message(WidgetCopy.emptyLine(model.content, model.isLocalMode), locked = false, plan = plan, action = rootAction)
            else -> Ready(model, snapshot.art, plan)
        }
    }
}

/**
 * Signed out, empty, and a locked widget too small for [LockedFace]: the eye,
 * the wordmark and one calm line. Never a book, a cover or a rank.
 */
@Composable
private fun Message(line: String?, locked: Boolean, plan: HomePlan, action: Action) {
    val roomy = plan.face == HomeFace.STACKED
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = GlanceModifier
                .then(if (roomy) GlanceModifier.width(plan.gridWidthDp.dp) else GlanceModifier.fillMaxWidth())
                .then(if (roomy) GlanceModifier.background(ImageProvider(R.drawable.widget_home_altar)).cornerRadius(14.dp) else GlanceModifier)
                .clickable(action)
                .padding(horizontal = 10.dp, vertical = if (roomy) 12.dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (plan.face != HomeFace.ROW) {
                Image(
                    provider = ImageProvider(R.drawable.widget_eye),
                    contentDescription = null,
                    modifier = GlanceModifier.size(if (roomy) 48.dp else 36.dp),
                )
                Spacer(GlanceModifier.height(4.dp))
            }
            Wordmark(if (roomy) 18 else 14)
            if (line != null) {
                Spacer(GlanceModifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (locked) {
                        Image(
                            provider = ImageProvider(R.drawable.widget_lock),
                            contentDescription = null,
                            modifier = GlanceModifier.size(14.dp),
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
        }
    }
}

/**
 * Locked, with room: the same plan as a full widget, laid out empty. The altar
 * with the eye and the wordmark, the grid of empty Lives with a lock where
 * LIFE I's play disc goes, and the lock line where the title goes. Generic
 * art only, never a book, a cover or a rank. Any tap opens the unlock.
 */
@Composable
private fun LockedFace(plan: HomePlan, unlock: Action) {
    Column(modifier = GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        val card = GlanceModifier
            .width(plan.gridWidthDp.dp)
            .background(ImageProvider(R.drawable.widget_home_altar))
            .cornerRadius(14.dp)
            .clickable(unlock)
        if (plan.altar == AltarStyle.TALL) {
            Column(modifier = card.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(provider = ImageProvider(R.drawable.widget_eye), contentDescription = null, modifier = GlanceModifier.size(plan.eyeDp.dp))
                Spacer(GlanceModifier.height(4.dp))
                Wordmark(19)
            }
        } else {
            Row(
                modifier = card.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(provider = ImageProvider(R.drawable.widget_eye), contentDescription = null, modifier = GlanceModifier.size(COMPACT_EYE_DP.dp))
                Spacer(GlanceModifier.width(12.dp))
                Wordmark(17)
            }
        }
        Spacer(GlanceModifier.height(SECTION_GAP_DP.dp))
        Spacer(GlanceModifier.defaultWeight())
        Column {
            repeat(plan.rows) { r ->
                if (r > 0) Spacer(GlanceModifier.height(LIFE_GAP_DP.dp))
                Row {
                    repeat(LIFE_COLUMNS) { c ->
                        if (c > 0) Spacer(GlanceModifier.width(LIFE_GAP_DP.dp))
                        val i = r * LIFE_COLUMNS + c
                        Box(
                            modifier = GlanceModifier
                                .size(plan.tileDp.dp)
                                .background(ImageProvider(if (i == 0) R.drawable.widget_life_ring_current else R.drawable.widget_life_ring))
                                .clickable(unlock),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (i == 0) {
                                Box(modifier = GlanceModifier.fillMaxSize().padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
                                    Image(
                                        provider = ImageProvider(R.drawable.widget_lock),
                                        contentDescription = null,
                                        modifier = GlanceModifier.size(badgeDp(plan.tileDp).dp),
                                    )
                                }
                            }
                            Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                                LifeLabel(lifeLabel(i), plan.tileDp, dim = true)
                            }
                        }
                    }
                }
            }
        }
        Spacer(GlanceModifier.height(LINE_GAP_DP.dp))
        Spacer(GlanceModifier.defaultWeight())
        Text(
            text = WidgetCopy.HOME_LOCKED,
            maxLines = 1,
            modifier = GlanceModifier.clickable(unlock),
            style = TextStyle(color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
        )
    }
}

@Composable
private fun Ready(model: HomeWidgetModel, art: HomeWidgetArt, plan: HomePlan) {
    val context = LocalContext.current
    val current = model.current ?: return
    val openApp = actionStartActivity(widgetMainIntent(context, WidgetLaunch.OpenApp))
    when (plan.face) {
        HomeFace.ROW -> RowFace(model, art, plan, current, openApp)
        HomeFace.NARROW -> NarrowFace(model, art, plan, current, openApp)
        HomeFace.STACKED -> StackedFace(model, art, plan, current, openApp)
    }
}

/**
 * Home, small: the altar card on top, the Lives grid, the title line at the
 * bottom. All three share the grid's width so their edges line up, and the
 * height left over goes into the two gaps between them.
 */
@Composable
private fun StackedFace(model: HomeWidgetModel, art: HomeWidgetArt, plan: HomePlan, current: CurrentCard, openApp: Action) {
    Column(modifier = GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        model.rank?.let { Altar(it, plan, openApp) }
        Spacer(GlanceModifier.height(SECTION_GAP_DP.dp))
        Spacer(GlanceModifier.defaultWeight())
        LivesGrid(model, art, plan, current)
        Spacer(GlanceModifier.height(LINE_GAP_DP.dp))
        Spacer(GlanceModifier.defaultWeight())
        TitleLine(current, plan.gridWidthDp)
    }
}

/** Two cells wide: a mini altar, LIFE I large with its disc, the title and chapter under it. */
@Composable
private fun NarrowFace(model: HomeWidgetModel, art: HomeWidgetArt, plan: HomePlan, current: CurrentCard, openApp: Action) {
    Column(modifier = GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (plan.altar == AltarStyle.MINI) {
            model.rank?.let { MiniAltar(it, openApp) }
            Spacer(GlanceModifier.height(SECTION_GAP_DP.dp))
        }
        Spacer(GlanceModifier.defaultWeight())
        Life(art.current, current.title, lifeLabel(0), plan.tileDp, current)
        Spacer(GlanceModifier.height(LINE_GAP_DP.dp))
        Spacer(GlanceModifier.defaultWeight())
        Column(
            modifier = GlanceModifier.fillMaxWidth().clickable(openBook(LocalContext.current, current.bookId)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = current.title,
                maxLines = 1,
                style = TextStyle(color = textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
            )
            current.chapterTitle?.let {
                Text(text = it, maxLines = 1, style = TextStyle(color = textSecondary, fontSize = 11.sp, textAlign = TextAlign.Center))
            }
        }
    }
}

/** One cell high: LIFE I, the title and chapter beside it, and the rank at the far end if it fits. */
@Composable
private fun RowFace(model: HomeWidgetModel, art: HomeWidgetArt, plan: HomePlan, current: CurrentCard, openApp: Action) {
    val context = LocalContext.current
    val width = LocalSize.current.width.value - 2 * HOME_PAD_DP
    Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Life(art.current, current.title, lifeLabel(0), plan.tileDp, current)
        Spacer(GlanceModifier.width(10.dp))
        Column(modifier = GlanceModifier.defaultWeight().clickable(openBook(context, current.bookId))) {
            Text(
                text = current.title,
                maxLines = 2,
                style = TextStyle(color = textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            )
            current.chapterTitle?.let {
                Spacer(GlanceModifier.height(2.dp))
                Text(text = it, maxLines = 1, style = TextStyle(color = textSecondary, fontSize = 11.sp))
            }
        }
        val rank = model.rank
        if (rank != null && width - plan.tileDp - 10 >= ROW_RANK_MIN_DP) {
            Spacer(GlanceModifier.width(10.dp))
            Column(
                modifier = GlanceModifier.clickable(openApp).semantics { contentDescription = rank.epithet + ". " + rank.totalTimeText },
                horizontalAlignment = Alignment.End,
            ) {
                Text(text = trackedCaps(rank.epithet), maxLines = 1, style = TextStyle(color = textMuted, fontSize = 8.sp, fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.height(2.dp))
                Wordmark(12)
                Spacer(GlanceModifier.height(2.dp))
                Text(text = rank.totalTimeText, maxLines = 1, style = TextStyle(color = gold, fontSize = 10.sp, fontWeight = FontWeight.Medium))
            }
        }
    }
}

/** Width the row face needs beside LIFE I before it also shows the rank block. */
private const val ROW_RANK_MIN_DP = 200

/**
 * Home's altar card. TALL is Home's own order: rank in tracked caps, the eye,
 * the wordmark, the altar line when there is room, the total time given.
 * COMPACT keeps the same words with the eye beside them, centered. Opens Home.
 */
@Composable
private fun Altar(rank: RankCard, plan: HomePlan, openApp: Action) {
    val card = GlanceModifier
        .width(plan.gridWidthDp.dp)
        .background(ImageProvider(R.drawable.widget_home_altar))
        .cornerRadius(14.dp)
        .clickable(openApp)
        .semantics { contentDescription = rank.epithet + ". " + rank.totalTimeText }
    if (plan.altar == AltarStyle.TALL) {
        Column(modifier = card.padding(horizontal = 10.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = trackedCaps(rank.epithet), maxLines = 1, style = TextStyle(color = textMuted, fontSize = 9.sp, fontWeight = FontWeight.Medium))
            Spacer(GlanceModifier.height(4.dp))
            Image(provider = ImageProvider(R.drawable.widget_eye), contentDescription = null, modifier = GlanceModifier.size(plan.eyeDp.dp))
            Spacer(GlanceModifier.height(4.dp))
            Wordmark(19)
            if (plan.showPhrase && rank.phrase.isNotBlank()) {
                Spacer(GlanceModifier.height(2.dp))
                Text(text = rank.phrase, maxLines = 1, style = TextStyle(color = textSecondary, fontSize = 11.sp, textAlign = TextAlign.Center))
            }
            Spacer(GlanceModifier.height(2.dp))
            Text(text = rank.totalTimeText, maxLines = 1, style = TextStyle(color = gold, fontSize = 11.sp, fontWeight = FontWeight.Medium))
        }
    } else {
        Row(
            modifier = card.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(provider = ImageProvider(R.drawable.widget_eye), contentDescription = null, modifier = GlanceModifier.size(COMPACT_EYE_DP.dp))
            Spacer(GlanceModifier.width(12.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = trackedCaps(rank.epithet), maxLines = 1, style = TextStyle(color = textMuted, fontSize = 9.sp, fontWeight = FontWeight.Medium))
                Wordmark(17)
                Text(text = rank.totalTimeText, maxLines = 1, style = TextStyle(color = gold, fontSize = 11.sp, fontWeight = FontWeight.Medium))
            }
        }
    }
}

/** The two cell altar: rank, wordmark, total time. No eye, the cover needs the height. */
@Composable
private fun MiniAltar(rank: RankCard, openApp: Action) {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(ImageProvider(R.drawable.widget_home_altar))
            .cornerRadius(14.dp)
            .clickable(openApp)
            .semantics { contentDescription = rank.epithet + ". " + rank.totalTimeText }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = trackedCaps(rank.epithet), maxLines = 1, style = TextStyle(color = textMuted, fontSize = 8.sp, fontWeight = FontWeight.Medium))
        Wordmark(14)
        Text(text = rank.totalTimeText, maxLines = 1, style = TextStyle(color = gold, fontSize = 10.sp, fontWeight = FontWeight.Medium))
    }
}

/** NINE LIVES in Home's bold tracked gold. Text, not an image, so it follows the system font like the app does. */
@Composable
private fun Wordmark(sizeSp: Int) {
    Text(
        text = WORDMARK,
        maxLines = 1,
        style = TextStyle(color = gold, fontSize = sizeSp.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
    )
}

/** Home's grid: three columns, the current book first, empty slots left empty as on Home. */
@Composable
private fun LivesGrid(model: HomeWidgetModel, art: HomeWidgetArt, plan: HomePlan, current: CurrentCard) {
    val context = LocalContext.current
    val books = listOf(current.bookId to current.title) + model.lives.map { it.bookId to it.title }
    Column {
        repeat(plan.rows) { r ->
            if (r > 0) Spacer(GlanceModifier.height(LIFE_GAP_DP.dp))
            Row {
                repeat(LIFE_COLUMNS) { c ->
                    if (c > 0) Spacer(GlanceModifier.width(LIFE_GAP_DP.dp))
                    val i = r * LIFE_COLUMNS + c
                    val book = books.getOrNull(i)
                    when {
                        book == null -> Spacer(GlanceModifier.size(plan.tileDp.dp))
                        i == 0 -> Life(art.current, current.title, lifeLabel(0), plan.tileDp, current)
                        else -> Life(art.lives[book.first], book.second, lifeLabel(i), plan.tileDp, null, openBook(context, book.first))
                    }
                }
            }
        }
    }
}

/**
 * One Lives tile, after Home's: the ring, the cover inset in it, the LIFE
 * numeral at the bottom. Tapping it opens the book. LIFE I ([current] set)
 * has the full gold ring and the play or pause disc in its middle.
 */
@Composable
private fun Life(
    bitmap: Bitmap?,
    title: String,
    label: String,
    sizeDp: Int,
    current: CurrentCard?,
    action: Action? = null,
) {
    val context = LocalContext.current
    val open = action ?: openBook(context, current?.bookId.orEmpty())
    val coverDp = sizeDp - 2 * TILE_INSET_DP
    Box(
        modifier = GlanceModifier
            .size(sizeDp.dp)
            .background(ImageProvider(if (current != null) R.drawable.widget_life_ring_current else R.drawable.widget_life_ring))
            .clickable(open)
            .semantics { contentDescription = "$label. $title" },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = GlanceModifier.size(coverDp.dp),
            )
        } else {
            // No cover yet: the title on a plain slot, as Home's placeholder does.
            Box(
                modifier = GlanceModifier.size(coverDp.dp).background(ImageProvider(R.drawable.widget_life_placeholder)).padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    maxLines = 3,
                    style = TextStyle(color = textSecondary, fontSize = if (sizeDp >= 80) 11.sp else 9.sp, textAlign = TextAlign.Center),
                )
            }
        }
        Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            LifeLabel(label, sizeDp, dim = false)
        }
        if (current != null) {
            val disc = badgeDp(sizeDp)
            // A little above the middle, so the disc clears the LIFE plate on small tiles.
            Box(modifier = GlanceModifier.fillMaxSize().padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = GlanceModifier.size((disc + 6).dp).background(ImageProvider(R.drawable.widget_badge_halo)),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayButton(current, open, disc)
                }
            }
        }
    }
}

/** Space between a tile's edge and its cover, room for the ring. */
private const val TILE_INSET_DP = 7

/** The LIFE numeral on its small dark plate, above the tile's bottom edge. */
@Composable
private fun LifeLabel(label: String, sizeDp: Int, dim: Boolean) {
    Column(modifier = GlanceModifier.padding(bottom = if (sizeDp >= 80) 10.dp else 8.dp)) {
        Text(
            text = label,
            maxLines = 1,
            modifier = GlanceModifier
                .background(ImageProvider(R.drawable.widget_life_label))
                .cornerRadius(5.dp)
                .padding(horizontal = 5.dp, vertical = 1.dp),
            style = TextStyle(
                color = if (dim) textMuted else gold,
                fontSize = if (sizeDp >= 80) 9.sp else 8.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
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
 * Where Home has its mini player: the title on the left, the chapter on the
 * right, one line across the grid's width. Opens the book.
 */
@Composable
private fun TitleLine(current: CurrentCard, widthDp: Int) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.width(widthDp.dp).clickable(openBook(context, current.bookId)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = current.title,
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(color = textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        )
        current.chapterTitle?.let {
            Spacer(GlanceModifier.width(10.dp))
            Text(
                text = it,
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = textSecondary, fontSize = 11.sp, textAlign = TextAlign.End),
            )
        }
    }
}

/** Open its page goes through the trampoline, which drops a stale tap from another library. */
private fun openBook(context: Context, bookId: String): Action =
    actionStartActivity(widgetActionIntent(context, WidgetLaunch.OpenBook(bookId)))

/** Play or pause, through the trampoline that holds until the service is in the foreground. */
private fun toggle(context: Context, bookId: String): Action =
    actionStartActivity(widgetActionIntent(context, WidgetLaunch.TogglePlay(bookId)))
