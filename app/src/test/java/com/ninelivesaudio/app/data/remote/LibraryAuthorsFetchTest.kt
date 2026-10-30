package com.ninelivesaudio.app.data.remote

import com.ninelivesaudio.app.data.remote.dto.ApiAuthor
import com.ninelivesaudio.app.data.remote.dto.LibraryAuthorsResponse
import com.ninelivesaudio.app.domain.model.LibraryAuthor
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Author view groups by the library's Audiobookshelf authors (issue #87).
 * A reply that is not a whole author list must come back as a failure, so the
 * caller keeps the list it already has instead of storing an empty one.
 */
class LibraryAuthorsFetchTest {

    @Test
    fun `an author list keeps each author's id and name`() {
        val result = libraryAuthorsResult(
            Response.success(LibraryAuthorsResponse(listOf(ApiAuthor("au_1", "Smith, John"), ApiAuthor("au_2", "Neil Gaiman"))))
        )

        assertEquals(
            RemoteResult.Ok(listOf(LibraryAuthor("au_1", "Smith, John"), LibraryAuthor("au_2", "Neil Gaiman"))),
            result,
        )
    }

    @Test
    fun `entries with no id or no name are skipped`() {
        val result = libraryAuthorsResult(
            Response.success(
                LibraryAuthorsResponse(listOf(ApiAuthor(null, "X"), ApiAuthor("au_3", " "), ApiAuthor("au_4", " Ann Lee ")))
            )
        )

        assertEquals(RemoteResult.Ok(listOf(LibraryAuthor("au_4", "Ann Lee"))), result)
    }

    @Test
    fun `a reply without an authors list is a failure, not an empty library`() {
        val result = libraryAuthorsResult(Response.success(LibraryAuthorsResponse(authors = null)))

        assertTrue(result.toString(), result is RemoteResult.Failed)
    }

    @Test
    fun `an old server that answers 404 is a failure`() {
        val result = libraryAuthorsResult(Response.error(404, "".toResponseBody()))

        assertTrue(result.toString(), result is RemoteResult.Failed)
    }

    @Test
    fun `a missing body is a failure`() {
        val result = libraryAuthorsResult(Response.success<LibraryAuthorsResponse>(null))

        assertTrue(result.toString(), result is RemoteResult.Failed)
    }

    @Test
    fun `an empty author list is a real answer`() {
        val result = libraryAuthorsResult(Response.success(LibraryAuthorsResponse(authors = emptyList())))

        assertEquals(RemoteResult.Ok(emptyList<LibraryAuthor>()), result)
    }

    @Test
    fun `the shape Audiobookshelf sends decodes`() {
        // The flags NetworkModule gives Retrofit's converter.
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            encodeDefaults = true
            explicitNulls = false
        }
        val body = json.decodeFromString(
            LibraryAuthorsResponse.serializer(),
            """{"authors":[{"id":"aut_1","asin":null,"name":"Smith, John","description":null,"imagePath":null,""" +
                """"addedAt":1,"updatedAt":1,"numBooks":2,"lastFirst":"John, Smith"}]}""",
        )

        assertEquals(RemoteResult.Ok(listOf(LibraryAuthor("aut_1", "Smith, John"))), libraryAuthorsResult(Response.success(body)))
    }
}
