package com.ninelivesaudio.app.widget

import java.security.MessageDigest

/*
 * Cover art is the second trap for widgets (issue #8). Every bitmap in a
 * RemoteViews is parcelled into one binder transaction, and that transaction
 * is capped at 1 MB for the whole process. A full size cover (1000 px or more,
 * ARGB) is 4 MB on its own. So widget covers are small, square, and decoded as
 * RGB_565 (covers are opaque, so the alpha byte buys nothing and halves the
 * cost), and every payload is checked against a budget well under the cap.
 */

/** One Binder sleeve, in pixels. */
internal const val BINDER_COVER_PX = 192

/** The large widget's current book cover (W2). */
internal const val HOME_CURRENT_COVER_PX = 192

/** One of the large widget's next Lives tiles (W2). */
internal const val HOME_LIFE_COVER_PX = 96

/** Bitmap bytes one RemoteViews payload may carry, well under the 1 MB transaction. */
internal const val WIDGET_BITMAP_BUDGET_BYTES = 600 * 1024

/** Bytes for one square opaque RGB_565 cover. */
internal fun rgb565Bytes(px: Int): Int = px * px * 2

/** One Binder spread carries two sleeves. */
internal fun binderSpreadBitmapBytes(): Int = 2 * rgb565Bytes(BINDER_COVER_PX)

/** Disk cache name: SHA 1 of the cover's version key, plus the size. A new cover gets a new name. */
internal fun widgetCoverFileName(versionKey: String, px: Int): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(versionKey.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) } + "_$px.jpg"
}

/**
 * Which cached covers to delete: names not in [keep], oldest first, only as
 * many as it takes to bring the count down to [cap]. [files] is name to
 * last modified time.
 */
internal fun coversToPrune(files: List<Pair<String, Long>>, keep: Set<String>, cap: Int = 160): List<String> {
    val excess = files.size - cap
    if (excess <= 0) return emptyList()
    return files.filter { it.first !in keep }
        .sortedBy { it.second }
        .take(excess)
        .map { it.first }
}
