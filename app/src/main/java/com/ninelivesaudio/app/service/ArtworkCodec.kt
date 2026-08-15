package com.ninelivesaudio.app.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Shared cover-art decode/downscale/compress pipeline.
 *
 * Used by both PlaybackManager (now-playing metadata, embedded ~512px) and
 * MediaBrowseTree (browse-row thumbnails, embedded ~256px) so both surfaces
 * hand Android the raw JPEG bytes instead of a URI pointing at the ABS
 * server. Android Auto's MediaDataLoader rejects cleartext http outright,
 * and can't carry the Authorization header a token-protected server needs
 * even over https, so a server URL as artworkUri is deterministically dead
 * (issue #89). Local file:// and content:// covers have the same problem
 * out-of-process (scoped storage), so this pipeline is the fix for both
 * sources.
 */
internal object ArtworkCodec {

    // Only the image header is needed for a bounds-only decode. Marking the
    // FULL download budget here used to mean BufferedInputStream retained up
    // to ~3MB per in-flight decode just so the bounds pass COULD rewind that
    // far, when it never actually needed to — up to ~12MB transient across 3
    // concurrent browse fetches plus the now-playing embed, all inside a
    // foreground media service.
    //
    // NOT 64KB, which is a trap: a single maximal JPEG APP1/EXIF segment is
    // 65533 bytes on its own, so a cover carrying a full EXIF block or a fat
    // ICC profile pushes SOF0 past a 64KB mark, invalidates it, and forces
    // the re-open path below — which for a remote cover means a SECOND full
    // HTTP GET, on exactly the metered connection the epoch gate exists to
    // protect. 256KB clears that class of header outright while still being
    // a 12x improvement on the ~3MB it replaced.
    private const val BOUNDS_MARK_LIMIT = 256 * 1024

    /**
     * Opens a stream via [openStream], downscales so neither dimension
     * exceeds [maxDimension], and JPEG-compresses under [maxEmbedBytes] by
     * stepping quality down to [minQuality]. Returns null on any decode
     * failure, or if the size cap still can't be hit at [minQuality] —
     * artwork is always optional, never worth failing the caller over.
     *
     * [openStream] is a factory rather than an already-open stream because
     * the bounds-only pass marks a bounded (256KB, see [BOUNDS_MARK_LIMIT])
     * rewind budget: if the header decode reads past it (rare — a
     * multi-segment ICC profile of exotic size), the mark is invalidated and
     * the source is re-opened from scratch rather than dropping
     * otherwise-decodable artwork. The caller supplies fresh bytes on demand
     * instead of this function needing to know how to rewind a file, a
     * content:// URI, and an HTTP response body.
     *
     * Worst-case retention is the mark limit per in-flight decode, not per
     * call: reset() does not drop the mark, so BufferedInputStream keeps
     * doubling up to the limit for any source larger than it. That is ~1MB
     * across the 3 browse permits plus the now-playing embed, against ~12MB
     * before the limit was introduced.
     */
    fun decodeAndCompress(
        openStream: () -> InputStream,
        maxDimension: Int,
        maxEmbedBytes: Int,
        minQuality: Int,
    ): ByteArray? {
        var stream = try {
            BufferedInputStream(openStream())
        } catch (e: Exception) {
            return null
        }

        // Everything from here on is wrapped so that ANY throwable closes
        // whichever stream is currently open. The previous shape
        // (okHttpClient...execute().use {} / BufferedInputStream(x).use {})
        // got that unconditionally from `use`; taking a factory instead means
        // this function now owns the lifetime, and the bounds pass below is
        // the one decode with no close of its own. An OutOfMemoryError there
        // is not an Exception, so the caller's catch would not have caught it
        // either — the OkHttp connection would have stayed pinned until a
        // finalizer reclaimed it, up to 3 at a time under the browse
        // semaphore.
        val bitmap = try {
            stream.mark(BOUNDS_MARK_LIMIT)
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, boundsOptions)

            val width = boundsOptions.outWidth
            val height = boundsOptions.outHeight
            if (width <= 0 || height <= 0) {
                stream.close()
                return null
            }

            try {
                stream.reset()
            } catch (e: Exception) {
                // The bounds-only mark was exceeded, so this stream can no
                // longer be rewound. Re-open a fresh one from the source
                // instead of failing outright.
                stream.close()
                stream = try {
                    BufferedInputStream(openStream())
                } catch (e2: Exception) {
                    return null
                }
            }

            val sampleSize = calculateInSampleSize(width, height, maxDimension)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            try {
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } finally {
                stream.close()
            }
        } catch (t: Throwable) {
            runCatching { stream.close() }
            throw t
        }
        if (bitmap == null) return null

        val compressed = ByteArrayOutputStream()
        var quality = 85
        do {
            compressed.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, compressed)
            quality -= 10
        } while (compressed.size() > maxEmbedBytes && quality >= minQuality)
        bitmap.recycle()

        return compressed.toByteArray().takeIf { it.size <= maxEmbedBytes }
    }

    /**
     * Largest power-of-two [BitmapFactory.Options.inSampleSize] that still
     * leaves both dimensions at or under [maxDimension]. Pure and testable
     * without touching android.graphics.
     */
    internal fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        var sampledWidth = width
        var sampledHeight = height

        while (sampledWidth > maxDimension || sampledHeight > maxDimension) {
            sampledWidth /= 2
            sampledHeight /= 2
            sample *= 2
        }

        return sample
    }
}

/**
 * Wraps an [InputStream] and enforces a hard byte limit.
 * After [maxBytes] have been read, further reads return EOF (-1).
 * Prevents unbounded memory allocation when Content-Length is unknown
 * (e.g. chunked transfer encoding).
 */
internal class BoundedInputStream(
    stream: InputStream,
    private val maxBytes: Long,
) : FilterInputStream(stream) {
    private var bytesRead: Long = 0

    override fun read(): Int {
        if (bytesRead >= maxBytes) return -1
        val b = super.read()
        if (b != -1) bytesRead++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (bytesRead >= maxBytes) return -1
        val allowed = len.toLong().coerceAtMost(maxBytes - bytesRead).toInt()
        if (allowed <= 0) return -1
        val n = super.read(b, off, allowed)
        if (n > 0) bytesRead += n
        return n
    }
}
