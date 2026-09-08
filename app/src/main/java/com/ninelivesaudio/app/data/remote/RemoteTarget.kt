package com.ninelivesaudio.app.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Full server route, including a reverse-proxy path. Never contains credentials. */
internal data class ServerRoute private constructor(val url: String) {
    companion object {
        fun parse(value: String?): ServerRoute? {
            val parsed = value?.trim()?.toHttpUrlOrNull() ?: return null
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
                parsed.query != null || parsed.fragment != null) return null
            return ServerRoute(parsed.toString().trimEnd('/'))
        }
    }
}

/** One secure-preferences record. A null account is explicitly unowned. */
internal data class StoredAuthRecord(val token: String, val serverUrl: String?, val accountId: String? = null) {
    override fun toString(): String = "StoredAuthRecord(redacted, ownerKnown=${!accountId.isNullOrBlank()})"
}

internal data class RemoteOwner(val route: ServerRoute, val accountId: String) {
    val key: String by lazy { MessageDigest.getInstance("SHA-256").run {
        // Length prefixes keep arbitrary account IDs unambiguous.
        for (part in listOf(route.url, accountId)) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            update(bytes)
        }
        digest().joinToString("") { "%02x".format(it) }
    } }
}

internal data class RemoteTarget(val owner: RemoteOwner, val authGeneration: Long)

internal fun captureRemoteTarget(
    record: StoredAuthRecord?,
    selectedServerUrl: String,
    authGeneration: Long,
    runtimeAuthMatches: Boolean,
): RemoteTarget? {
    if (!runtimeAuthMatches || record == null || record.token.isBlank()) return null
    val route = ServerRoute.parse(record.serverUrl) ?: return null
    if (route != ServerRoute.parse(selectedServerUrl)) return null
    val account = record.accountId?.takeIf { it.isNotBlank() } ?: return null
    return RemoteTarget(RemoteOwner(route, account), authGeneration)
}
