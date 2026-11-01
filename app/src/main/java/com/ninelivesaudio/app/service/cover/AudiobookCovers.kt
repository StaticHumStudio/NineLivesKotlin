package com.ninelivesaudio.app.service.cover

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * audiobookcovers.com: square audiobook art made by the r/audiobookcovers community, each
 * matched to an Open Library book. Its search is a similarity search that always answers
 * with its closest images, however loose, so only the closest few become tiles.
 */

const val AUDIOBOOK_COVERS_MAX_RESULTS = 8

fun audiobookCoversSearchUrl(query: String): HttpUrl = HttpUrl.Builder()
    .scheme("https")
    .host("audiobookcovers.com")
    .addPathSegments("api/search")
    .addQueryParameter("q", query)
    .build()

@Serializable
private data class AudiobookCoversBody(val results: List<AudiobookCoversResult>)

@Serializable
private data class AudiobookCoversResult(
    val id: String? = null,
    val url: String? = null,
    val images: AudiobookCoversImages? = null,
)

@Serializable
private data class AudiobookCoversImages(val jpeg: AudiobookCoversSizes? = null)

@Serializable
private data class AudiobookCoversSizes(
    @SerialName("320") val small: String? = null,
    @SerialName("640") val medium: String? = null,
    @SerialName("1280") val large: String? = null,
)

private val audiobookCoversJson = Json { ignoreUnknownKeys = true }

private fun httpsOrNull(url: String?): String? =
    url?.toHttpUrlOrNull()?.takeIf { it.isHttps }?.toString()

/** The candidates in a search answer, or null when the body is not search json at all. */
fun parseAudiobookCoversSearch(body: String): List<CoverCandidate>? {
    val parsed = runCatching { audiobookCoversJson.decodeFromString(AudiobookCoversBody.serializer(), body) }
        .getOrNull() ?: return null
    return parsed.results.mapNotNull { r ->
        val id = r.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val jpeg = r.images?.jpeg
        val original = httpsOrNull(r.url)
        val thumb = httpsOrNull(jpeg?.small) ?: httpsOrNull(jpeg?.medium) ?: original ?: return@mapNotNull null
        val full = httpsOrNull(jpeg?.large) ?: original ?: httpsOrNull(jpeg?.medium) ?: thumb
        CoverCandidate(
            source = CoverSourceId.AUDIOBOOK_COVERS,
            key = "abc:$id",
            thumbnailUrl = thumb,
            fullUrl = full,
            title = null,
            author = null,
        )
    }.distinctBy { it.fullUrl }.take(AUDIOBOOK_COVERS_MAX_RESULTS)
}

/** audiobookcovers.com as a [CoverSearchSource]: one search request per Search tap. */
@Singleton
class AudiobookCoversSource private constructor(
    private val client: OkHttpClient,
    @Suppress("UNUSED_PARAMETER") ready: Unit,
) : CoverSearchSource {

    @Inject
    constructor(appClient: OkHttpClient) :
        this(coverSearchHttpClient(appClient, coverSearchUserAgent(com.ninelivesaudio.app.BuildConfig.VERSION_NAME)), Unit)

    internal companion object {
        /** A source on a client that is already the cover client (tests). */
        fun onCoverClient(client: OkHttpClient) = AudiobookCoversSource(client, Unit)
    }

    override val id = CoverSourceId.AUDIOBOOK_COVERS

    override suspend fun search(query: String): List<CoverCandidate> {
        val body = client.getCapped(audiobookCoversSearchUrl(query).toString(), MAX_SEARCH_BODY_BYTES)
        val candidates = parseAudiobookCoversSearch(body.toString(Charsets.UTF_8))
            ?: throw IOException("not a search body")
        // Never log the search text: it is the user's book.
        runCatching { android.util.Log.d("CoverSearch", "audiobookcovers search: ${candidates.size} candidates") }
        return candidates
    }
}
