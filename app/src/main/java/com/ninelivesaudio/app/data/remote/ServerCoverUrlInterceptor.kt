package com.ninelivesaudio.app.data.remote

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Sends every Audiobookshelf item cover request to the server the app is using
 * right now, whatever host the stored URL names.
 *
 * Book rows keep an absolute cover URL from whichever server address synced
 * them. Switching between a LAN and a VPN address left every cover pointing at
 * the old host, which either does not answer on the new network or gets no
 * sign-in token (the token only goes to the current server), so the whole
 * shelf fell back to placeholders until a full resync rewrote the rows. The
 * URL is now rebuilt from the current server and the item id at request time,
 * so existing rows work as they are and no resync is needed.
 *
 * Covers stay cached under the stored URL, so a switch back and forth does not
 * download the library's covers again. The query (the `width` that list rows
 * and Android Auto ask for) is kept.
 */
class ServerCoverUrlInterceptor(
    private val serverUrl: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val server = validatedServerBaseUrl(serverUrl()) ?: return chain.proceed(request)
        val target = currentServerCoverUrl(request.url, server)
        if (target == null || target == request.url) return chain.proceed(request)
        return chain.proceed(request.newBuilder().url(target).build())
    }
}

/**
 * [url] moved onto [server] when it is an item cover (`.../api/items/{id}/cover`),
 * with the server's own base path and the original query. Null for anything
 * else: audio files, other API calls, other paths.
 */
internal fun currentServerCoverUrl(url: HttpUrl, server: HttpUrl): HttpUrl? {
    val segments = url.encodedPathSegments
    val n = segments.size
    if (n < 4) return null
    if (segments[n - 4] != "api" || segments[n - 3] != "items" || segments[n - 1] != "cover") return null
    val itemId = segments[n - 2].takeIf { it.isNotEmpty() } ?: return null

    val builder = HttpUrl.Builder()
        .scheme(server.scheme)
        .host(server.host)
        .port(server.port)
    server.encodedPathSegments.filter { it.isNotEmpty() }.forEach { builder.addEncodedPathSegment(it) }
    builder.addEncodedPathSegment("api")
        .addEncodedPathSegment("items")
        .addEncodedPathSegment(itemId)
        .addEncodedPathSegment("cover")
        .encodedQuery(url.encodedQuery)
    return builder.build()
}
