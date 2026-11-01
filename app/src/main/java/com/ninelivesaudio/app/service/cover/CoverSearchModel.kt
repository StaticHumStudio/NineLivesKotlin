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

    /** It answered, but with a page this app no longer understands. Dropped like a failure. */
    data class Unparseable(override val source: CoverSourceId) : SourceOutcome
}

/**
 * What one Search tap asks for. [text] is the whole box (Open Library searches it as is).
 * [title] and [author] are for a source that matches on each (audiobookcovers.com).
 */
data class CoverQuery(val text: String, val title: String, val author: String?)

/** A source whose answer arrived but no longer has a shape this app recognises. */
class UnparseableCoverPageException : Exception("cover page not recognised")

enum class CoverSearchPhase { RESULTS, NO_MATCHES, UNREACHABLE }

class CoverSearchOutcome(val phase: CoverSearchPhase, val results: List<CoverResult>)

/** A cover service. A throw from [search] means the source failed and drops out. */
interface CoverSearchSource {
    val id: CoverSourceId
    suspend fun search(query: CoverQuery): List<CoverCandidate>
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

private fun realAuthor(author: String?): String? =
    author?.let { normalizedSearchText(it) }?.takeUnless { it.equals(UNKNOWN_AUTHOR_PLACEHOLDER, ignoreCase = true) }

/** What the search box starts with: the book's title and author. */
fun prefillSearchText(title: String, author: String?): String =
    normalizedSearchText("$title ${realAuthor(author).orEmpty()}").orEmpty()

/**
 * The query for what is in the box. Untouched (still the prefill, spacing aside), it sends
 * the book's own title and author tags. Edited, the whole box is the title and no author
 * is sent, since there is no telling which words the user meant as the author.
 * Null when the box is blank.
 */
fun coverQueryFor(boxText: String, bookTitle: String, bookAuthor: String?): CoverQuery? {
    val text = normalizedSearchText(boxText) ?: return null
    val untouched = text == prefillSearchText(bookTitle, bookAuthor)
    val title = if (untouched) normalizedSearchText(bookTitle) else null
    return if (title != null) CoverQuery(text, title, realAuthor(bookAuthor)) else CoverQuery(text, text, null)
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

/**
 * Source order first (audiobookcovers.com title matches, then Open Library), then square
 * covers within a source (they fit the player as they are), otherwise the order they came in.
 */
fun rankCoverResults(results: List<CoverResult>): List<CoverResult> =
    results.sortedWith(compareBy({ it.candidate.source.ordinal }, { if (isSquare(it.thumb.width, it.thumb.height)) 0 else 1 }))

/**
 * Results when any came back. No matches only when every source that answered said "nothing".
 * Otherwise unreachable: every source failed, or every thumbnail did, and saying
 * "No covers found" then would be a lie.
 */
fun coverSearchPhase(outcomes: List<SourceOutcome>, results: List<CoverResult>): CoverSearchPhase = when {
    results.isNotEmpty() -> CoverSearchPhase.RESULTS
    outcomes.filterIsInstance<SourceOutcome.Found>().let { found ->
        found.isNotEmpty() && found.all { it.candidates.isEmpty() }
    } -> CoverSearchPhase.NO_MATCHES
    else -> CoverSearchPhase.UNREACHABLE
}
