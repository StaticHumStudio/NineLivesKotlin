package com.ninelivesaudio.app.data.local.entity

/**
 * Result type for the local catalog query: which LOCAL book exists, in which
 * library, and whether it is archived. Nothing else, so playback position
 * saves leave it unchanged.
 */
data class LocalBookMembership(
    val id: String,
    val libraryId: String?,
    val isArchived: Boolean,
)
