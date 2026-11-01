package com.ninelivesaudio.app.service.cover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverSearchModelTest {

    private fun cand(key: String) = CoverCandidate(CoverSourceId.OPEN_LIBRARY, key, "t/$key", "u/$key", null, null)
    private fun res(key: String, w: Int, h: Int) = CoverResult(cand(key), CoverThumb(ByteArray(1), w, h))

    @Test fun `the search box starts with title and author`() =
        assertEquals("Dracula Test Bram Stoker", prefillSearchText("Dracula Test", "Bram Stoker"))

    @Test fun `a missing author leaves just the title`() {
        assertEquals("Plain Test", prefillSearchText("  Plain   Test ", null))
        assertEquals("Plain Test", prefillSearchText("Plain Test", ""))
    }

    @Test fun `the scan's unknown author placeholder is not searched for`() =
        assertEquals("Plain Test", prefillSearchText("Plain Test", "Unknown Author"))

    @Test fun `a blank box cannot search`() = assertNull(normalizedSearchText(" \n\t "))

    @Test fun `runaway text is cut at 200 characters`() = assertEquals(200, normalizedSearchText("x".repeat(500))!!.length)

    @Test fun `near square counts as square, a book cover does not`() {
        assertTrue(isSquare(500, 500))
        assertTrue(isSquare(500, 520))
        assertFalse(isSquare(318, 500))
        assertFalse(isSquare(0, 0))
    }

    @Test fun `square covers come first and keep their order`() {
        val ranked = rankCoverResults(listOf(res("p1", 318, 500), res("s1", 500, 500), res("p2", 180, 283), res("s2", 600, 610)))
        assertEquals(listOf("s1", "s2", "p1", "p2"), ranked.map { it.candidate.key })
    }

    private fun abcRes(key: String, w: Int, h: Int) =
        CoverResult(CoverCandidate(CoverSourceId.AUDIOBOOK_COVERS, key, "t/$key", "u/$key", null, null), CoverThumb(ByteArray(1), w, h))

    @Test fun `audiobookcovers title matches come before every open library cover, square or not`() {
        val ranked = rankCoverResults(listOf(res("olSquare", 500, 500), res("olBook", 318, 500), abcRes("abcOdd", 500, 700), abcRes("abc", 640, 640)))
        assertEquals(listOf("abc", "abcOdd", "olSquare", "olBook"), ranked.map { it.candidate.key })
    }

    @Test fun `the untouched box searches the book's own title and author tags`() {
        val q = coverQueryFor("Project Hail Mary Andy Weir", "Project Hail Mary", "Andy Weir")!!
        assertEquals(CoverQuery("Project Hail Mary Andy Weir", "Project Hail Mary", "Andy Weir"), q)
    }

    @Test fun `spacing alone does not count as an edit`() =
        assertEquals("Andy Weir", coverQueryFor("  Project  Hail Mary Andy Weir ", "Project Hail Mary", "Andy Weir")!!.author)

    @Test fun `an edited box is the title and sends no author`() =
        assertEquals(CoverQuery("carmilla le fanu", "carmilla le fanu", null), coverQueryFor("carmilla le fanu", "Carmilla (Unabridged)", "Le Fanu, J. S."))

    @Test fun `the unknown author placeholder is never sent as an author`() =
        assertEquals(CoverQuery("Plain Test", "Plain Test", null), coverQueryFor("Plain Test", "Plain Test", "Unknown Author"))

    @Test fun `a blank box has no query`() = assertNull(coverQueryFor("  ", "Plain Test", null))

    @Test fun `an unparseable source drops out like a failed one`() {
        val merged = mergeCoverSources(listOf(SourceOutcome.Unparseable(CoverSourceId.AUDIOBOOK_COVERS), SourceOutcome.Found(CoverSourceId.OPEN_LIBRARY, listOf(cand("a")))))
        assertEquals(listOf("a"), merged.map { it.key })
        assertEquals(CoverSearchPhase.NO_MATCHES, coverSearchPhase(listOf(
            SourceOutcome.Unparseable(CoverSourceId.AUDIOBOOK_COVERS),
            SourceOutcome.Found(CoverSourceId.OPEN_LIBRARY, emptyList()),
        ), emptyList()))
    }

    @Test fun `both sources answering with nothing reads as no covers found`() =
        assertEquals(CoverSearchPhase.NO_MATCHES, coverSearchPhase(listOf(
            SourceOutcome.Found(CoverSourceId.AUDIOBOOK_COVERS, emptyList()),
            SourceOutcome.Found(CoverSourceId.OPEN_LIBRARY, emptyList()),
        ), emptyList()))

    @Test fun `a failed source drops out and the rest still show`() {
        val merged = mergeCoverSources(listOf(SourceOutcome.Failed(CoverSourceId.OPEN_LIBRARY), SourceOutcome.Found(CoverSourceId.OPEN_LIBRARY, listOf(cand("a")))))
        assertEquals(listOf("a"), merged.map { it.key })
    }

    @Test fun `audiobookcovers comes before open library whatever order they answered in`() {
        val abc = CoverCandidate(CoverSourceId.AUDIOBOOK_COVERS, "x", "t/x", "u/x", null, null)
        val merged = mergeCoverSources(listOf(
            SourceOutcome.Found(CoverSourceId.OPEN_LIBRARY, listOf(cand("a"))),
            SourceOutcome.Found(CoverSourceId.AUDIOBOOK_COVERS, listOf(abc)),
        ))
        assertEquals(listOf("x", "a"), merged.map { it.key })
    }

    @Test fun `one source answering with nothing while the other failed reads as no matches`() =
        assertEquals(CoverSearchPhase.NO_MATCHES, coverSearchPhase(listOf(
            SourceOutcome.Failed(CoverSourceId.AUDIOBOOK_COVERS),
            SourceOutcome.Found(CoverSourceId.OPEN_LIBRARY, emptyList()),
        ), emptyList()))

    @Test fun `the same cover twice shows once`() =
        assertEquals(1, mergeCoverSources(listOf(SourceOutcome.Found(CoverSourceId.OPEN_LIBRARY, listOf(cand("a"), cand("a"))))).size)

    @Test fun `results stop at twenty`() =
        assertEquals(20, mergeCoverSources(listOf(SourceOutcome.Found(CoverSourceId.OPEN_LIBRARY, (1..40).map { cand("c$it") }))).size)

    @Test fun `results win even when a source failed`() =
        assertEquals(CoverSearchPhase.RESULTS, coverSearchPhase(listOf(SourceOutcome.Failed(CoverSourceId.OPEN_LIBRARY)), listOf(res("a", 1, 1))))

    @Test fun `a source that answered with nothing reads as no matches`() =
        assertEquals(CoverSearchPhase.NO_MATCHES, coverSearchPhase(listOf(SourceOutcome.Found(CoverSourceId.OPEN_LIBRARY, emptyList())), emptyList()))

    @Test fun `every source failing reads as unreachable, not as no matches`() =
        assertEquals(CoverSearchPhase.UNREACHABLE, coverSearchPhase(listOf(SourceOutcome.Failed(CoverSourceId.OPEN_LIBRARY)), emptyList()))

    @Test fun `matches whose every thumbnail failed read as unreachable`() =
        assertEquals(CoverSearchPhase.UNREACHABLE, coverSearchPhase(listOf(SourceOutcome.Found(CoverSourceId.OPEN_LIBRARY, listOf(cand("a")))), emptyList()))

    @Test fun `covers that came back but whose thumbnails all failed read as unreachable, not no matches`() =
        assertEquals(CoverSearchPhase.UNREACHABLE, coverSearchPhase(listOf(
            SourceOutcome.Found(CoverSourceId.AUDIOBOOK_COVERS, emptyList()),
            SourceOutcome.Found(CoverSourceId.OPEN_LIBRARY, listOf(cand("a"), cand("b"))),
        ), emptyList()))
}
