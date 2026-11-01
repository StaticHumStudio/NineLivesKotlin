package com.ninelivesaudio.app.service.cover

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * audiobookcovers.com: square audiobook art made by the r/audiobookcovers community, each
 * cover matched to its Open Library book. The site's json api is a picture similarity
 * search that always answers with something, however unrelated, so the app reads the
 * site's book search page instead: it matches the title and author against the book each
 * cover belongs to, and answers nothing when no cover matches.
 *
 * That page is a web page, not an api, so its shape can change. A page this parser does
 * not recognise is [AudiobookCoversPage.Unparseable], never an empty answer, so the grid
 * drops the source quietly and Open Library and photos keep working.
 */

/** The most matched covers one search shows from this source. */
const val AUDIOBOOK_COVERS_MAX_RESULTS = 12

/** The book search page carries every match plus a small placeholder image per cover. */
internal const val MAX_AUDIOBOOK_COVERS_PAGE_BYTES = 4L * 1024 * 1024

fun audiobookCoversSearchUrl(query: CoverQuery): HttpUrl = HttpUrl.Builder()
    .scheme("https")
    .host("audiobookcovers.com")
    .addPathSegment("search")
    .addQueryParameter("title", query.title)
    .apply { query.author?.takeIf { it.isNotBlank() }?.let { addQueryParameter("author", it) } }
    .build()

/** What the book search page said. */
sealed interface AudiobookCoversPage {
    /** The page's matched covers. Empty means the site really found none. */
    data class Covers(val candidates: List<CoverCandidate>) : AudiobookCoversPage

    /** Not a page this version understands. */
    data object Unparseable : AudiobookCoversPage
}

/**
 * The page embeds its search answer as serialized script data, which is steadier than
 * the markup: `images:$R[n]=[ {id:"<uuid>", ..., url:"https://images.audiobookcovers.com/original/<uuid>.png",
 * jpeg:$R[m]={320:"...", 640:"...", 1280:"..."}, ...}, ... ]`.
 */
private val RESULTS_START = Regex("""images:\${'$'}R\[\d+]=\[""")
private val RECORD_START = Regex("""\{id:"([0-9A-Za-z-]{8,64})",""")
private const val RESULTS_END_MARKER = "lastMatchId:"
private const val IMAGE_HOST = "https://images.audiobookcovers.com/"

/** The covers on one book search page, or [AudiobookCoversPage.Unparseable]. Pure, no network. */
fun parseAudiobookCoversPage(html: String): AudiobookCoversPage {
    val start = RESULTS_START.find(html) ?: return AudiobookCoversPage.Unparseable
    val listStart = start.range.last + 1
    if (html.getOrNull(listStart) == ']') return AudiobookCoversPage.Covers(emptyList())
    val listEnd = html.indexOf(RESULTS_END_MARKER, listStart).takeIf { it >= 0 } ?: html.length
    val list = html.substring(listStart, listEnd)
    val records = RECORD_START.findAll(list).toList()
    val candidates = records.mapIndexedNotNull { i, record ->
        val id = record.groupValues[1]
        val body = list.substring(record.range.first, records.getOrNull(i + 1)?.range?.first ?: list.length)
        candidateFrom(id, body)?.let { it to matchRank(body) }
    }.sortedBy { it.second }.map { it.first }.distinctBy { it.fullUrl }
    return if (candidates.isEmpty()) AudiobookCoversPage.Unparseable else AudiobookCoversPage.Covers(candidates)
}

private val MATCH_CONFIDENCE = Regex("""confidence:"([A-Z_]+)"""")

/**
 * How sure the site is that a cover belongs to the book it matched: checked by a person
 * or confirmed first, then likely, then uncertain or unstated. Ties keep the page order.
 */
private fun matchRank(record: String): Int = when (MATCH_CONFIDENCE.find(record)?.groupValues?.get(1)) {
    "HUMAN", "CONFIRMED" -> 0
    "LIKELY" -> 1
    else -> 2
}

private fun candidateFrom(id: String, record: String): CoverCandidate? {
    val quotedId = Regex.escape(id)
    fun sized(px: Int) = Regex("\"(${Regex.escape(IMAGE_HOST)}jpeg/$px/$quotedId\\.jpg)\"").find(record)?.groupValues?.get(1)
    val original = Regex("url:\"(${Regex.escape(IMAGE_HOST)}original/$quotedId\\.[A-Za-z]{3,4})\"").find(record)?.groupValues?.get(1)
    val small = sized(320)
    val medium = sized(640)
    val large = sized(1280)
    val thumb = small ?: medium ?: original ?: return null
    val full = large ?: original ?: medium ?: thumb
    return CoverCandidate(
        source = CoverSourceId.AUDIOBOOK_COVERS,
        key = "abc:$id",
        thumbnailUrl = thumb,
        fullUrl = full,
        title = null,
        author = null,
    )
}

/** audiobookcovers.com as a [CoverSearchSource]: one book search page per Search tap. */
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

    /** Throws [UnparseableCoverPageException] when the page is not one this version reads. */
    override suspend fun search(query: CoverQuery): List<CoverCandidate> {
        val body = client.getCapped(audiobookCoversSearchUrl(query).toString(), MAX_AUDIOBOOK_COVERS_PAGE_BYTES)
        val page = parseAudiobookCoversPage(body.toString(Charsets.UTF_8))
        val candidates = when (page) {
            is AudiobookCoversPage.Covers -> page.candidates.take(AUDIOBOOK_COVERS_MAX_RESULTS)
            AudiobookCoversPage.Unparseable -> throw UnparseableCoverPageException()
        }
        // Never log the search text: it is the user's book.
        runCatching { android.util.Log.d("CoverSearch", "audiobookcovers search: ${candidates.size} candidates") }
        return candidates
    }
}
