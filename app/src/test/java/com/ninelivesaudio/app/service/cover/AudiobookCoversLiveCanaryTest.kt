package com.ninelivesaudio.app.service.cover

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live checks: the audiobookcovers.com book search page, which the app reads as a web
 * page because the site's json api has no title match. If the site changes that page this
 * fails, so the change gets noticed before users see an empty grid. And an Open Library
 * cover, whose real redirects to archive.org must pass the cover host check.
 *
 * It reaches the real site, so CI skips it. Run it by hand:
 *   NL_LIVE_COVER_CANARY=1 ./gradlew testDebugUnitTest --tests '*AudiobookCoversLiveCanaryTest*'
 */
class AudiobookCoversLiveCanaryTest {

    @Test fun `the live book search still finds project hail mary`() = runBlocking {
        assumeTrue("live canary, set NL_LIVE_COVER_CANARY=1 to run", System.getenv("NL_LIVE_COVER_CANARY") == "1")
        val client = coverSearchHttpClient(OkHttpClient(), coverSearchUserAgent("canary"))
        val found = AudiobookCoversSource.onCoverClient(client)
            .search(CoverQuery("Project Hail Mary Andy Weir", "Project Hail Mary", "Andy Weir"))
        assertTrue("expected at least 1 cover, got ${found.size}", found.isNotEmpty())
    }

    @Test fun `an open library cover still arrives through its archive redirects`() = runBlocking {
        assumeTrue("live canary, set NL_LIVE_COVER_CANARY=1 to run", System.getenv("NL_LIVE_COVER_CANARY") == "1")
        val client = coverSearchHttpClient(OkHttpClient(), coverSearchUserAgent("canary"))
        val bytes = client.getCapped(openLibraryCoverUrl(12216503, 'M'), MAX_THUMBNAIL_BYTES)
        assertTrue("expected a jpeg, got ${bytes.size} bytes", bytes.size > 1000 && bytes[0] == 0xFF.toByte())
    }
}
