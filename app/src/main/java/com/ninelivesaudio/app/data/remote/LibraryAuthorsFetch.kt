package com.ninelivesaudio.app.data.remote

import com.ninelivesaudio.app.data.remote.dto.LibraryAuthorsResponse
import com.ninelivesaudio.app.domain.model.LibraryAuthor
import retrofit2.Response

/**
 * A library's author list from GET /api/libraries/{id}/authors (issue #87).
 * Anything that is not a whole list (an HTTP error, a 404 from a server
 * without the route, a body with no `authors` key) is [RemoteResult.Failed],
 * never an empty list, so the caller keeps the list it already stored. An
 * entry with no id or no name is dropped, and names are trimmed.
 */
internal fun libraryAuthorsResult(response: Response<LibraryAuthorsResponse>): RemoteResult<List<LibraryAuthor>> {
    if (!response.isSuccessful) return RemoteResult.Failed("authors: HTTP ${response.code()}")
    val authors = response.body()?.authors ?: return RemoteResult.Failed("authors: no author list in the reply")
    return RemoteResult.Ok(
        authors.mapNotNull { author ->
            val id = author.id?.trim().orEmpty()
            val name = author.name?.trim().orEmpty()
            if (id.isEmpty() || name.isEmpty()) null else LibraryAuthor(id, name)
        }
    )
}
