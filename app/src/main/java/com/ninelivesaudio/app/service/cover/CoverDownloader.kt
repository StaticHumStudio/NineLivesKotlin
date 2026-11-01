package com.ninelivesaudio.app.service.cover

import com.ninelivesaudio.app.BuildConfig
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** Fetches cover images from the cover services on the cover client (no server sign in). */
@Singleton
class CoverDownloader @Inject constructor(
    appClient: OkHttpClient,
    private val fitter: CoverImageFitter,
) {
    private val client = coverSearchHttpClient(appClient, coverSearchUserAgent(BuildConfig.VERSION_NAME))

    /** The grid thumbnail with its size, or null on any failure (that tile just drops out). */
    suspend fun thumbnail(candidate: CoverCandidate): CoverThumb? = try {
        val bytes = client.getCapped(candidate.thumbnailUrl, MAX_THUMBNAIL_BYTES)
        fitter.decodeSize(bytes)?.let { (w, h) -> CoverThumb(bytes, w, h) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** The large image to save, or null on any failure. */
    suspend fun full(candidate: CoverCandidate): ByteArray? = try {
        client.getCapped(candidate.fullUrl, MAX_COVER_DOWNLOAD_BYTES)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
