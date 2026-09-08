package com.ninelivesaudio.app.data.remote

import java.util.Base64

/** Opaque, versioned remote identity. C0 defines it but has no live caller yet. */
internal object RemoteIdCodec {
    private const val VERSION = "nlr1"

    fun encode(ownerKey: String, rawId: String): String {
        require(ownerKey.isNotBlank()) { "Remote owner key is required" }
        require(rawId.isNotEmpty()) { "Remote ID is required" }
        return "$VERSION:${encodePart(ownerKey)}:${encodePart(rawId)}"
    }

    fun ownerPrefix(ownerKey: String): String {
        require(ownerKey.isNotBlank()) { "Remote owner key is required" }
        return "$VERSION:${encodePart(ownerKey)}:"
    }

    fun decodeForOwner(encoded: String, ownerKey: String): String? {
        if (ownerKey.isBlank()) return null
        val parts = encoded.split(':', limit = 3)
        if (parts.size != 3 || parts[0] != VERSION || parts[1].isEmpty() || parts[2].isEmpty()) return null
        val encodedOwner = decodePart(parts[1]) ?: return null
        if (encodedOwner != ownerKey) return null
        return decodePart(parts[2])?.takeIf { it.isNotEmpty() }
    }

    private fun encodePart(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodePart(value: String): String? = runCatching {
        val decoded = Base64.getUrlDecoder().decode(value)
        val normalized = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)
        if (normalized != value) return@runCatching null
        String(decoded, Charsets.UTF_8)
    }.getOrNull()
}
