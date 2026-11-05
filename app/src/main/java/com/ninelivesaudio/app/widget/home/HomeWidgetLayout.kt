package com.ninelivesaudio.app.widget.home

import com.ninelivesaudio.app.widget.HOME_CURRENT_COVER_PX
import com.ninelivesaudio.app.widget.HOME_LIFE_COVER_PX
import com.ninelivesaudio.app.widget.rgb565Bytes
import kotlin.math.ceil
import kotlin.math.floor

/*
 * The Nine Lives widget is a small Home screen: the altar card, the Lives
 * grid with the current book as LIFE I, and one line with the title and
 * chapter where Home has its mini player. The widget runs in SizeMode.Exact,
 * so [homePlanFor] gets the launcher's real size and decides everything from
 * it. As the widget shrinks it drops, in order: the altar line, the tall
 * altar (for the compact one), rows of Lives, the Lives beyond the current
 * book, the eye, and the altar itself. The current book with play and pause
 * is in every plan.
 */

/** The widget's overall arrangement. */
internal enum class HomeFace {
    /** Altar on top, the Lives grid, the title line. */
    STACKED,

    /** Two cells wide: a mini altar, LIFE I large, the title and chapter under it. */
    NARROW,

    /** One cell high: LIFE I, the title and chapter beside it, a small rank block if it fits. */
    ROW,
}

/** How the altar card is drawn. */
internal enum class AltarStyle {
    /** Home's: rank, the eye above the wordmark, total time. */
    TALL,

    /** The eye beside a stack of rank, wordmark and total time, still centered. */
    COMPACT,

    /** Rank, wordmark and total time, no eye. */
    MINI,

    /** No altar at all. */
    NONE,
}

/**
 * Everything the face needs to know about its size, in dp. [rows] times three
 * is how many Lives slots the grid has (NARROW and ROW show LIFE I only).
 */
internal data class HomePlan(
    val face: HomeFace,
    val altar: AltarStyle,
    val rows: Int,
    val tileDp: Int,
    val eyeDp: Int,
    val showPhrase: Boolean,
) {
    val slots: Int get() = if (face == HomeFace.STACKED) rows * LIFE_COLUMNS else 1

    /** The grid's width, which the altar card and the title line share so all three line up. */
    val gridWidthDp: Int get() = if (face == HomeFace.STACKED) tileDp * LIFE_COLUMNS + LIFE_GAP_DP * (LIFE_COLUMNS - 1) else tileDp
}

/** Padding on every edge, enough for a tile corner to clear One UI's round widget corners. */
internal const val HOME_PAD_DP = 14

internal const val LIFE_COLUMNS = 3
internal const val LIFE_GAP_DP = 8
internal const val MAX_LIFE_ROWS = 3

/** Tiles stop growing here, so a very wide widget gets margins, not huge covers. */
internal const val MAX_TILE_DP = 116

/** Below this a tile is too small to read, and the plan gives up a row or the grid. */
internal const val MIN_TILE_DP = 52

/** Gap under the altar card. */
internal const val SECTION_GAP_DP = 10

/** Gap above the title line. */
internal const val LINE_GAP_DP = 6

/** The title line (12sp, one line). */
internal const val TITLE_LINE_DP = 17

/** The two line title block of the NARROW face. */
internal const val TITLE_BLOCK_DP = 32

/**
 * The tall altar's eye: [MIN_EYE_DP] when the tiles need the height, growing
 * with what they leave (see [tallAltarExtras]).
 */
internal const val MAX_EYE_DP = 56
internal const val MID_EYE_DP = 48
internal const val MIN_EYE_DP = 32
internal const val COMPACT_EYE_DP = 44

/** The altar line costs this much height, and shows when the slack covers it. */
internal const val PHRASE_DP = 17

/**
 * How much the tall altar's covered area counts for against the compact one's.
 * It wins when it costs the Lives less than about a tenth of their size.
 */
internal const val TALL_ALTAR_BONUS = 1.2f

/** Heights of the altar styles, in dp, without the altar line. */
internal fun altarHeightDp(style: AltarStyle, eyeDp: Int = MIN_EYE_DP): Int = when (style) {
    // padding 10 + rank 13 + 4 + eye + 4 + wordmark 24 + 2 + total 16 + padding 10
    AltarStyle.TALL -> 83 + eyeDp
    // padding 8 + max(eye 44, rank 13 + wordmark 22 + total 16) + padding 8
    AltarStyle.COMPACT -> 16 + maxOf(COMPACT_EYE_DP, 51)
    // padding 8 + rank 12 + wordmark 20 + total 15 + padding 8
    AltarStyle.MINI -> 63
    AltarStyle.NONE -> 0
}

/**
 * Share of the reported height the plan keeps in reserve. Launchers report a
 * widget's size from their cell estimate, not its real bounds: Pixel Launcher
 * reports a 4x3 as 359dp high when it is drawn 344dp high. The reserve lands in
 * the face's weighted gaps when the report is honest.
 */
internal const val REPORTED_SIZE_SLACK = 0.05f

/** The face is one cell high below this inner height. */
internal const val ROW_FACE_MAX_INNER_DP = 132

/** The face is two cells wide below this inner width. */
internal const val NARROW_FACE_MAX_INNER_DP = 200

/**
 * The plan for a widget of [widthDp] by [heightDp] (the launcher's real size)
 * showing [tiles] books, the current one included. [tiles] only ever lowers
 * the row count, so a short history gets bigger tiles instead of empty rows.
 */
internal fun homePlanFor(widthDp: Float, heightDp: Float, tiles: Int = MAX_LIFE_ROWS * LIFE_COLUMNS): HomePlan {
    val w = widthDp - 2 * HOME_PAD_DP
    val h = heightDp * (1f - REPORTED_SIZE_SLACK) - 2 * HOME_PAD_DP

    if (h < ROW_FACE_MAX_INNER_DP) {
        val tile = h.toInt().coerceAtLeast(1)
        return HomePlan(HomeFace.ROW, AltarStyle.NONE, rows = 0, tileDp = tile, eyeDp = 0, showPhrase = false)
    }
    if (w < NARROW_FACE_MAX_INNER_DP) {
        val withAltar = h - altarHeightDp(AltarStyle.MINI) - SECTION_GAP_DP - LINE_GAP_DP - TITLE_BLOCK_DP
        val altar = if (withAltar >= MIN_TILE_DP + 20) AltarStyle.MINI else AltarStyle.NONE
        val room = if (altar == AltarStyle.MINI) withAltar else h - LINE_GAP_DP - TITLE_BLOCK_DP
        val tile = minOf(w, room, MAX_TILE_DP.toFloat()).toInt().coerceAtLeast(1)
        return HomePlan(HomeFace.NARROW, altar, rows = 0, tileDp = tile, eyeDp = 0, showPhrase = false)
    }

    val tileByWidth = minOf(MAX_TILE_DP.toFloat(), (w - LIFE_GAP_DP * (LIFE_COLUMNS - 1)) / LIFE_COLUMNS)
    val maxRows = ceil(tiles.coerceAtLeast(1) / LIFE_COLUMNS.toFloat()).toInt().coerceIn(1, MAX_LIFE_ROWS)

    // Each altar and row count is scored by the cover area it fills, the tall
    // altar with a bonus for being Home's own. The tiles take the height first,
    // at the smallest eye, and whatever they leave grows the eye.
    var best: HomePlan? = null
    var bestScore = 0f
    for ((altar, rows) in ALTAR_CHOICES) {
        if (rows > maxRows) continue
        val eye = if (altar == AltarStyle.TALL) MIN_EYE_DP else COMPACT_EYE_DP
        val room = gridRoom(h, altarHeightDp(altar, eye))
        val tile = tileFor(room, tileByWidth, rows)
        if (tile < MIN_TILE_DP) continue
        val score = minOf(rows * LIFE_COLUMNS, tiles) * tile * tile * (if (altar == AltarStyle.TALL) TALL_ALTAR_BONUS else 1f)
        if (score <= bestScore) continue
        bestScore = score
        best = if (altar == AltarStyle.TALL) {
            val slack = (room - (tile * rows + LIFE_GAP_DP * (rows - 1))).toInt()
            val (grownEye, phrase) = tallAltarExtras(slack)
            HomePlan(HomeFace.STACKED, altar, rows, tile.toInt(), grownEye, phrase)
        } else {
            HomePlan(HomeFace.STACKED, altar, rows, tile.toInt(), eye, showPhrase = false)
        }
    }
    best?.let { return it }
    return HomePlan(HomeFace.ROW, AltarStyle.NONE, rows = 0, tileDp = h.toInt(), eyeDp = 0, showPhrase = false)
}

/**
 * Spends the tall altar's [slack] (dp the grid does not need): the eye grows
 * to [MID_EYE_DP] first, then the altar line comes in, then the eye grows on
 * to [MAX_EYE_DP]. Returns the eye size and whether the line shows.
 */
internal fun tallAltarExtras(slack: Int): Pair<Int, Boolean> {
    var left = slack.coerceAtLeast(0)
    var eye = MIN_EYE_DP + minOf(left, MID_EYE_DP - MIN_EYE_DP)
    left -= eye - MIN_EYE_DP
    val phrase = left >= PHRASE_DP
    if (phrase) left -= PHRASE_DP
    eye += minOf(left, MAX_EYE_DP - eye)
    return eye to phrase
}

/**
 * The altar and row counts the stacked face chooses between. Three rows only
 * ever come with the tall altar, so a widget never trades Home's look for a
 * third row of small tiles.
 */
private val ALTAR_CHOICES: List<Pair<AltarStyle, Int>> = listOf(
    AltarStyle.TALL to 3,
    AltarStyle.TALL to 2,
    AltarStyle.COMPACT to 2,
    AltarStyle.TALL to 1,
    AltarStyle.COMPACT to 1,
)

/** Height left for the grid under an altar of [altarDp], above the title line. */
private fun gridRoom(innerHeight: Float, altarDp: Int): Float =
    innerHeight - altarDp - SECTION_GAP_DP - LINE_GAP_DP - TITLE_LINE_DP

/** The square tile for [rows] rows in [room], never wider than [tileByWidth]. */
private fun tileFor(room: Float, tileByWidth: Float, rows: Int): Float =
    floor(minOf(tileByWidth, (room - LIFE_GAP_DP * (rows - 1)) / rows))

/** The play or pause disc on LIFE I: about a third of the tile, never too small to hit. */
internal fun badgeDp(tileDp: Int): Int = (tileDp * 0.36f).toInt().coerceIn(MIN_BADGE_DP, MAX_BADGE_DP)

internal const val MIN_BADGE_DP = 26
internal const val MAX_BADGE_DP = 44

/** "LIFE I" to "LIFE IX", as Home labels its tiles. Past nine, plain digits. */
internal fun lifeLabel(index: Int): String = "LIFE " + lifeNumeral(index + 1)

private val NUMERALS = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX")

internal fun lifeNumeral(n: Int): String = NUMERALS.getOrNull(n - 1) ?: n.toString()

/**
 * Glance cannot set letter spacing, so tracked caps are spelled with a thin
 * space between letters. A thin space is about 0.2 em, the tracking Home
 * gives its wordmark (4sp at 20sp) and its rank label (2sp at 10sp). A word
 * gap keeps its normal space plus the tracking on both sides.
 */
internal fun trackedCaps(text: String): String =
    text.uppercase().toList().joinToString(THIN_SPACE)

internal const val THIN_SPACE = " "

/** Home's wordmark, tracked. */
internal val WORDMARK: String = trackedCaps("Nine Lives")

/**
 * The most cover bytes one update carries: LIFE I and the next Lives, one
 * bitmap each. Every size Glance lays out reuses the same bitmaps, and a
 * RemoteViews keeps one copy of a bitmap however many views show it.
 */
internal fun homeUpdateBitmapBytes(): Int =
    rgb565Bytes(HOME_CURRENT_COVER_PX) + MAX_LIVES * rgb565Bytes(HOME_LIFE_COVER_PX)
