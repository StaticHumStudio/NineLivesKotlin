package com.ninelivesaudio.app.data.remote

import com.ninelivesaudio.app.data.remote.dto.*
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit interface for the Audiobookshelf REST API.
 * All 23+ endpoints from the C# AudioBookshelfApiService.
 */
interface AudiobookshelfApi {

    // ─── Auth ────────────────────────────────────────────────────────────

    @POST("login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    /** Lightweight authenticated check for token validity (no profile payload). */
    @GET("api/authorize")
    suspend fun authorize(): Response<Unit>

    // ─── User / Me ───────────────────────────────────────────────────────

    /** Validate token + get user info, all progress, and bookmarks. */
    @GET("api/me")
    suspend fun getMe(): Response<ApiMeResponse>

    /** Get progress for a specific item. */
    @GET("api/me/progress/{itemId}")
    suspend fun getUserProgress(@Path("itemId") itemId: String): Response<ApiUserProgress>

    /** Get paginated listening sessions for the authenticated user. */
    @GET("api/me/listening-sessions")
    suspend fun getListeningSessions(
        @Query("itemsPerPage") itemsPerPage: Int = 50,
        @Query("page") page: Int = 0,
    ): Response<ListeningSessionsResponse>

    /** Update progress for a specific item. */
    @PATCH("api/me/progress/{itemId}")
    suspend fun updateProgress(
        @Path("itemId") itemId: String,
        @Body request: UpdateProgressRequest,
    ): Response<Unit>

    // ─── Libraries ───────────────────────────────────────────────────────

    @GET("api/libraries")
    suspend fun getLibraries(): Response<LibrariesResponse>

    /** Get paginated library items. */
    @GET("api/libraries/{libraryId}/items")
    suspend fun getLibraryItems(
        @Path("libraryId") libraryId: String,
        @Query("limit") limit: Int = 100,
        @Query("page") page: Int = 0,
        @Query("minified") minified: Int = 0,
    ): Response<LibraryItemsResponse>

    // ─── Items ───────────────────────────────────────────────────────────

    /** Get expanded item details (full metadata, audio files, chapters). */
    @GET("api/items/{itemId}")
    suspend fun getItem(
        @Path("itemId") itemId: String,
        @Query("expanded") expanded: Int = 1,
    ): Response<ApiLibraryItem>

    /** Get cover image. */
    @GET("api/items/{itemId}/cover")
    suspend fun getCoverImage(
        @Path("itemId") itemId: String,
        @Query("width") width: Int? = null,
        @Query("height") height: Int? = null,
    ): Response<ResponseBody>

    /** Stream audio file for download. */
    @Streaming
    @GET("api/items/{itemId}/file/{fileIno}")
    suspend fun getAudioFileStream(
        @Path("itemId") itemId: String,
        @Path("fileIno") fileIno: String,
    ): Response<ResponseBody>

    // ─── Playback Session ────────────────────────────────────────────────

    /** Start a new playback session (returns audio tracks with streaming URLs). */
    @POST("api/items/{itemId}/play")
    suspend fun startPlaybackSession(
        @Path("itemId") itemId: String,
        @Body request: StartPlaybackRequest,
    ): Response<ApiPlaybackSession>

    /** Sync session progress (every 12s during playback). */
    @POST("api/session/{sessionId}/sync")
    suspend fun syncSessionProgress(
        @Path("sessionId") sessionId: String,
        @Body request: SyncSessionRequest,
    ): Response<Unit>

    /** Close a playback session. */
    @POST("api/session/{sessionId}/close")
    suspend fun closeSession(
        @Path("sessionId") sessionId: String,
        @Body body: Map<String, String> = emptyMap(),
    ): Response<Unit>

    // ─── Bookmarks ───────────────────────────────────────────────────────

    @POST("api/me/item/{itemId}/bookmark")
    suspend fun createBookmark(
        @Path("itemId") itemId: String,
        @Body request: CreateBookmarkRequest,
    ): Response<Unit>

    @DELETE("api/me/item/{itemId}/bookmark/{time}")
    suspend fun deleteBookmark(
        @Path("itemId") itemId: String,
        @Path("time") time: Double,
    ): Response<Unit>
}
