package com.ninelivesaudio.app.data.local.entity

/**
 * The four AudioBooks fields the free tier's download slot reads. Kept small
 * so building the slot candidates never decodes descriptions, track lists or
 * chapters.
 */
data class SlotBookRow(
    val id: String,
    val isLocal: Int,
    val isDownloaded: Int,
    val localPath: String?,
)
