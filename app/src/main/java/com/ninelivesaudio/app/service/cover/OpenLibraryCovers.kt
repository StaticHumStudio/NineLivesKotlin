package com.ninelivesaudio.app.service.cover

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl

/**
 * Open Library, the one cover source that passed the terms check. Only cover id URLs are
 * used (those are outside the per IP cap for ISBN lookups), one search per tap.
 */

const val OPEN_LIBRARY_SEARCH_LIMIT = 30

fun openLibrarySearchUrl(query: String): HttpUrl = HttpUrl.Builder()
    .scheme("https")
    .host("openlibrary.org")
    .addPathSegment("search.json")
    .addQueryParameter("q", query)
    .addQueryParameter("fields", "key,title,author_name,cover_i")
    .addQueryParameter("limit", OPEN_LIBRARY_SEARCH_LIMIT.toString())
    .build()

/** `default=false` makes a missing cover a 404 instead of a blank placeholder image. */
fun openLibraryCoverUrl(coverId: Long, size: Char): String =
    "https://covers.openlibrary.org/b/id/$coverId-$size.jpg?default=false"

@Serializable
private data class OpenLibrarySearchBody(
    val numFound: Int,
    val docs: List<OpenLibraryDoc> = emptyList(),
)

@Serializable
private data class OpenLibraryDoc(
    val key: String? = null,
    val title: String? = null,
    @SerialName("author_name") val authors: List<String>? = null,
    @SerialName("cover_i") val coverId: Long? = null,
)

private val openLibraryJson = Json { ignoreUnknownKeys = true }

/** The candidates in a search.json body, or null when the body is not search json at all. */
fun parseOpenLibrarySearch(body: String): List<CoverCandidate>? {
    val parsed = runCatching { openLibraryJson.decodeFromString(OpenLibrarySearchBody.serializer(), body) }
        .getOrNull() ?: return null
    val seen = HashSet<Long>()
    return parsed.docs.mapNotNull { doc ->
        val id = doc.coverId ?: return@mapNotNull null
        if (id <= 0 || !seen.add(id)) return@mapNotNull null
        CoverCandidate(
            source = CoverSourceId.OPEN_LIBRARY,
            key = "ol:$id",
            thumbnailUrl = openLibraryCoverUrl(id, 'M'),
            fullUrl = openLibraryCoverUrl(id, 'L'),
            title = doc.title,
            author = doc.authors?.firstOrNull(),
        )
    }
}
