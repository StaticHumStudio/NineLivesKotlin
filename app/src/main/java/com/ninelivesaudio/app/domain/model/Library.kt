package com.ninelivesaudio.app.domain.model

data class Library(
    val id: String = "",
    val name: String = "",
    val folders: List<Folder> = emptyList(),
    val displayOrder: Int = 0,
    val icon: String = "audiobook",
    val mediaType: String = "book",
)

data class Folder(
    val id: String = "",
    val fullPath: String = "",
    val libraryId: String = "",
)
