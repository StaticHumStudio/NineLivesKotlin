package com.ninelivesaudio.app.data.local.entity

/**
 * Result type for the local catalog query: which LOCAL book exists, in which
 * library, whether it is archived, and the fields the Library shelf shows,
 * searches, sorts, or groups on. Progress and position stay out, so playback
 * saves leave it unchanged while a rescan that retags a book does not.
 */
data class LocalCatalogEntry(
    val id: String,
    val libraryId: String?,
    val isArchived: Boolean,
    val title: String = "",
    val author: String? = null,
    val narrator: String? = null,
    val coverPath: String? = null,
    val localCoverPath: String? = null,
    val durationSeconds: Double = 0.0,
    val addedAt: String? = null,
    val seriesName: String? = null,
    val seriesSequence: String? = null,
    val genresJson: String? = null,
)
