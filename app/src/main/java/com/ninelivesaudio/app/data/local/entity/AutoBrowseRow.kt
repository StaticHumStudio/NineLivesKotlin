package com.ninelivesaudio.app.data.local.entity

/**
 * One Android Auto browse or search row: what a car list shows and where its
 * cover lives. Description, track list, chapters and tags stay out, so a page
 * of 50 reads 50 small rows instead of decoding whole books.
 */
data class AutoBrowseRow(
    val id: String,
    val title: String,
    val author: String? = null,
    val narrator: String? = null,
    val coverPath: String? = null,
    val localCoverPath: String? = null,
    val genresJson: String? = null,
)
