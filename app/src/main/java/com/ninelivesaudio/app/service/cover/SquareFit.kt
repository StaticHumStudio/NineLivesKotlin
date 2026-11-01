package com.ninelivesaudio.app.service.cover

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal const val COVER_MAX_SIDE = 1024
internal const val COVER_FILL_DARKEN = 0.45f
internal const val COVER_JPEG_QUALITY = 90

/** Where the art sits on its square: never cropped, centered, filled around when it is not square. */
internal data class SquareFit(
    val side: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val needsFill: Boolean,
)

/** The square for an image: its own long side up to [maxSide], never larger than the image itself. */
internal fun squareFit(srcWidth: Int, srcHeight: Int, maxSide: Int = COVER_MAX_SIDE): SquareFit? {
    if (srcWidth <= 0 || srcHeight <= 0) return null
    val long = max(srcWidth, srcHeight)
    val side = min(long, maxSide)
    val scale = side.toDouble() / long
    val width = (srcWidth * scale).roundToInt().coerceIn(1, side)
    val height = (srcHeight * scale).roundToInt().coerceIn(1, side)
    return SquareFit(
        side = side,
        left = (side - width) / 2,
        top = (side - height) / 2,
        width = width,
        height = height,
        needsFill = width != side || height != side,
    )
}

/** The size to decode an image at so its long side lands on the cap and no more pixels load. */
internal fun coverDecodeTarget(srcWidth: Int, srcHeight: Int, maxSide: Int = COVER_MAX_SIDE): Pair<Int, Int> {
    val fit = squareFit(srcWidth, srcHeight, maxSide) ?: return srcWidth to srcHeight
    return fit.width to fit.height
}
