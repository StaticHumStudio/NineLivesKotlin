package com.ninelivesaudio.app.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.io.IOException

/** Full server route, including a reverse-proxy path. Never contains credentials. */
internal class ServerRoute private constructor(val url: String) {
    private val parsed = requireNotNull(url.toHttpUrlOrNull())

    internal fun asHttpUrl(): okhttp3.HttpUrl = parsed

    fun contains(candidate: okhttp3.HttpUrl): Boolean {
        if (ServerOrigin.from(parsed) != ServerOrigin.from(candidate)) return false
        val baseSegments = parsed.encodedPathSegments.filter { it.isNotEmpty() }
        val candidateSegments = candidate.encodedPathSegments.filter { it.isNotEmpty() }
        return candidateSegments.size >= baseSegments.size &&
            candidateSegments.take(baseSegments.size) == baseSegments
    }

    override fun equals(other: Any?): Boolean = other is ServerRoute && url == other.url

    override fun hashCode(): Int = url.hashCode()

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

    override fun toString(): String = "RemoteOwner(redacted)"
}

internal data class RemoteTarget(val owner: RemoteOwner, val authGeneration: Long) {
    override fun toString(): String = "RemoteTarget(redacted)"
}

/** Runtime-only bearer snapshot. It must never appear in a URL, log, or durable row. */
internal data class FrozenBearer internal constructor(
    internal val token: String,
    val route: ServerRoute,
    val authGeneration: Long,
) {
    override fun toString(): String = "FrozenBearer(redacted)"
}

/** Immutable dispatch identity for one Retrofit operation. */
internal class FrozenRemoteRequest internal constructor(
    val route: ServerRoute,
    val owner: RemoteTarget?,
    internal val bearer: FrozenBearer,
    val routeRevision: Long,
) {
    override fun toString(): String = "FrozenRemoteRequest(redacted)"
}

/**
 * Owner-scoped remote identity captured for one catalog or download operation.
 *
 * Its request is intentionally retained so C3 callers can delegate dispatch and
 * currentness to C1b's exact route, bearer, generation, and route-revision fence.
 * It is runtime-only and must never reach a durable row or diagnostic string.
 */
internal class ActiveRemoteScope internal constructor(
    internal val frozenRequest: FrozenRemoteRequest,
    internal val committedAuthFence: Long = 0L,
) {
    val target: RemoteTarget = requireNotNull(frozenRequest.owner) {
        "An active remote scope requires a confirmed owner"
    }
    val ownerKey: String get() = target.owner.key
    val idPrefix: String get() = RemoteIdCodec.ownerPrefix(ownerKey)

    fun encodeIncoming(rawAbsId: String): String = RemoteIdCodec.encode(ownerKey, rawAbsId)

    fun decodeForEgress(encodedId: String): String? =
        RemoteIdCodec.decodeForOwner(encodedId, ownerKey)

    override fun toString(): String = "ActiveRemoteScope(redacted)"
}

/** Retrofit request tag carrying a frozen route and optional frozen bearer. */
class RemoteDispatchTag private constructor(
    internal val route: ServerRoute,
    internal val bearer: FrozenBearer?,
    internal val routeRevision: Long?,
) {
    companion object {
        internal fun noBearer(route: ServerRoute, routeRevision: Long? = null): RemoteDispatchTag =
            RemoteDispatchTag(route, null, routeRevision)

        internal fun frozen(request: FrozenRemoteRequest): RemoteDispatchTag =
            RemoteDispatchTag(request.route, request.bearer, request.routeRevision)
    }

    override fun toString(): String = "RemoteDispatchTag(redacted)"
}

/** A captured request became stale before its next network exchange. */
internal class StaleRemoteRequestException : IOException("Remote request scope changed")

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

/** Durable disposition for raw remote rows that predate owner-scoped IDs. */
internal sealed interface LegacyRemoteCacheState {
    data class PendingRestoredSession(
        val canonicalRoute: String,
        internal val credentialFingerprint: String,
    ) : LegacyRemoteCacheState {
        override fun toString(): String = "PendingRestoredSession(redacted)"
    }

    data class PendingRestoredOwner(
        val canonicalRoute: String,
        val accountId: String,
    ) : LegacyRemoteCacheState {
        override fun toString(): String = "PendingRestoredOwner(redacted)"
    }

    data object Quarantined : LegacyRemoteCacheState
    data object Claimed : LegacyRemoteCacheState
}

internal fun restoredCredentialFingerprint(token: String): String = MessageDigest.getInstance("SHA-256")
    .digest(token.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

internal fun LegacyRemoteCacheState.PendingRestoredSession.matchesRestoredRecord(
    record: StoredAuthRecord,
): Boolean {
    val route = ServerRoute.parse(record.serverUrl) ?: return false
    return record.accountId.isNullOrBlank() && route.url == canonicalRoute &&
        MessageDigest.isEqual(
            credentialFingerprint.toByteArray(Charsets.US_ASCII),
            restoredCredentialFingerprint(record.token).toByteArray(Charsets.US_ASCII),
        )
}

internal fun LegacyRemoteCacheState.canBeClaimedBy(owner: RemoteOwner): Boolean =
    this is LegacyRemoteCacheState.PendingRestoredOwner &&
        canonicalRoute == owner.route.url && accountId == owner.accountId
