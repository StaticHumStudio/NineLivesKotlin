package com.ninelivesaudio.app.service.cover

/**
 * The rules of Find a cover, kept free of Android and the network so JVM tests pin them:
 * the search text, merging what each source sent, square covers first, and what the
 * screen says when nothing came back.
 */

/**
 * Where a cover came from. The order here is the order sources appear in the grid:
 * audiobookcovers.com first (square audiobook art), then Open Library (book covers).
 * A new source is a new value here plus a [CoverSearchSource].
 */
enum class CoverSourceId { AUDIOBOOK_COVERS, OPEN_LIBRARY }

/** One cover a source offered, before its thumbnail is fetched. */
data class CoverCandidate(
    val source: CoverSourceId,
    val key: String,
    val thumbnailUrl: String,
    val fullUrl: String,
    val title: String?,
    val author: String?,
)

/** A fetched thumbnail and its decoded size, which is what square first ranks on. */
class CoverThumb(val bytes: ByteArray, val width: Int, val height: Int)

/** A candidate whose thumbnail loaded, ready for the grid. */
class CoverResult(val candidate: CoverCandidate, val thumb: CoverThumb)

/** What one source did for one search. */
sealed interface SourceOutcome {
    val source: CoverSourceId

    data class Found(override val source: CoverSourceId, val candidates: List<CoverCandidate>) : SourceOutcome
    data class Failed(override val source: CoverSourceId) : SourceOutcome
}

enum class CoverSearchPhase { RESULTS, NO_MATCHES, UNREACHABLE }

class CoverSearchOutcome(val phase: CoverSearchPhase, val results: List<CoverResult>)

/** A cover service. A throw from [search] means the source failed and drops out. */
interface CoverSearchSource {
    val id: CoverSourceId
    suspend fun search(query: String): List<CoverCandidate>
}

/** At most this many thumbnails are fetched per search (Open Library asks for restraint). */
const val MAX_COVER_RESULTS = 20
const val MAX_SEARCH_TEXT = 200

private val WHITESPACE_RUN = Regex("\\s+")

/** Trimmed, single spaced and capped. Null when there is nothing to search for. */
fun normalizedSearchText(input: String): String? =
    input.trim().replace(WHITESPACE_RUN, " ").take(MAX_SEARCH_TEXT).trim().ifEmpty { null }

/** The author the folder scan writes when a book has no author tag. Not worth searching for. */
private const val UNKNOWN_AUTHOR_PLACEHOLDER = "Unknown Author"

/** What the search box starts with: the book's title and author. */
fun prefillSearchText(title: String, author: String?): String {
    val realAuthor = author?.takeUnless { it.trim().equals(UNKNOWN_AUTHOR_PLACEHOLDER, ignoreCase = true) }
    return normalizedSearchText("$title ${realAuthor.orEmpty()}").orEmpty()
}

/** Square, or within 5 percent of it. */
fun isSquare(width: Int, height: Int): Boolean {
    if (width <= 0 || height <= 0) return false
    val long = maxOf(width, height).toDouble()
    val short = minOf(width, height).toDouble()
    return long / short <= 1.05
}

/** Every source that answered, in source order then each source's own order, one tile per image. */
fun mergeCoverSources(outcomes: List<SourceOutcome>, max: Int = MAX_COVER_RESULTS): List<CoverCandidate> {
    val seen = HashSet<String>()
    return outcomes
        .filterIsInstance<SourceOutcome.Found>()
        .sortedBy { it.source.ordinal }
        .flatMap { it.candidates }
        .filter { seen.add(it.fullUrl) }
        .take(max)
}

/** Square covers first (they fit the player as they are), otherwise the order they came in. */
fun rankCoverResults(results: List<CoverResult>): List<CoverResult> =
    results.sortedBy { if (isSquare(it.thumb.width, it.thumb.height)) 0 else 1 }

/**
 * Results when any came back. No matches only when a source really answered "nothing".
 * Otherwise unreachable: every source failed, or every thumbnail did, and saying
 * "No covers found" then would be a lie.
 */
fun coverSearchPhase(outcomes: List<SourceOutcome>, results: List<CoverResult>): CoverSearchPhase = when {
    results.isNotEmpty() -> CoverSearchPhase.RESULTS
    outcomes.any { it is SourceOutcome.Found && it.candidates.isEmpty() } -> CoverSearchPhase.NO_MATCHES
    else -> CoverSearchPhase.UNREACHABLE
}
